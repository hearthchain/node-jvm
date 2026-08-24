#!/usr/bin/env python3
"""Drive the full TEE-miner cycle against a freshly started private node on localhost:6869 over REST (stdlib only).

Usage: startboost-live.py <fixture-dir>

<fixture-dir> holds quote.hex (a TDX v4 quote whose report_data[0:32] is this image's genesis block id and
report_data[32:64] the enclave key to register) and collateral/ with rootca.crl.der, pck-ca-issuer-chain.pem,
pckcrl-platform.pem or pckcrl-processor.pem (PCK_CRL=platform|processor picks which, default platform: it must be the
CA that issued the quote's PCK leaf), tcb-signing-issuer-chain.pem, tcbinfo.json, qeidentity.json. Run it within the
first minutes of the chain: the quote is fresh only while height < 101 (StartBoostTransactionDiff.FreshnessWindowBlocks).
"""
import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

# The openly committed integration demo seed: its Ed25519 key is the fixture quote's enclave key, so this runbook
# holds the private half and can sign Settle batches itself. NEVER use this key outside tests.
DEMO_SEED = b"hearth-integration-demo-seed-001"
# ed25519.Sign(DEMO_SEED key, DEMO_MESSAGE), a committed cross-language vector; sanity-checks ed25519_sign below.
DEMO_MESSAGE = b"hearth demo settlement v0: epoch 42, client 0x0102030405060708, spent 123456"
DEMO_SIGNATURE = (
    "1fb7407c5eafd14abd3cd256b319d6d314d1f09db7ef22b3d46d85ae821d7b03"
    "78b2b0b6fe2b19c6f5ac923a9af4b757e1e347ff1b327089965c6bab17c9770b"
)
# HPKE v0 api-key envelope (enc(32) || ChaCha20-Poly1305 ciphertext) sealed to the demo key's X25519 image,
# from the same committed vector set; the node stores it opaquely, so any well-formed blob exercises BindApiKey.
DEMO_ENVELOPE = (
    "8f779588d219fb25de2ad323732d301756721878a6deefbc05c6f80d660e5f62"
    "2af1a82c6381cf4e2d9f1cc3b9c3899cc5aa99c8aa93cd1d394217d3d3e95ec80475d8059968963e800b0fd4f66864"
)


# Reference (slow) ed25519 on stdlib hashlib, RFC 8032: fine for a test runbook signing two 60-byte messages with a
# public seed; never reuse for anything real.
_P = 2**255 - 19
_L = 2**252 + 27742317777372353535851937790883648493
_D = -121665 * pow(121666, _P - 2, _P) % _P
_B = (
    15112221349535400772501151409588531511454012693041857206046113283949847762202,
    46316835694926478169428394003475163141307993866256225615783033603165251855960,
)


def _add(p, q):
    (x1, y1), (x2, y2) = p, q
    t = _D * x1 * x2 * y1 * y2
    return (
        (x1 * y2 + x2 * y1) * pow(1 + t, _P - 2, _P) % _P,
        (y1 * y2 + x1 * x2) * pow(1 - t, _P - 2, _P) % _P,
    )


def _mul(k, p):
    r = (0, 1)
    while k:
        if k & 1:
            r = _add(r, p)
        p, k = _add(p, p), k >> 1
    return r


def _enc(p):
    return (p[1] | (p[0] & 1) << 255).to_bytes(32, "little")


def _scalars(seed):
    h = hashlib.sha512(seed).digest()
    return (int.from_bytes(h[:32], "little") & 2**254 - 8) | 2**254, h[32:]


def ed25519_pubkey(seed):
    return _enc(_mul(_scalars(seed)[0], _B))


def ed25519_sign(seed, msg):
    if seed != DEMO_SEED:  # variable-time reference code, public seed only: never let it touch a real key
        sys.exit("ed25519_sign is demo-only")
    a, prefix = _scalars(seed)
    pub = _enc(_mul(a, _B))
    r = int.from_bytes(hashlib.sha512(prefix + msg).digest(), "little") % _L
    rp = _enc(_mul(r, _B))
    k = int.from_bytes(hashlib.sha512(rp + pub + msg).digest(), "little") % _L
    return rp + ((r + k * a) % _L).to_bytes(32, "little")


_B32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def bech32m_decode(addr):
    hrp, data = addr.rsplit("1", 1)
    vals = [_B32.index(c) for c in data]
    poly = 1
    for v in [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp] + vals:
        top, poly = poly >> 25, (poly & 0x1FFFFFF) << 5 ^ v
        for i, g in enumerate((0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3)):
            poly ^= g * (top >> i & 1)
    if poly != 0x2BC830A3:
        sys.exit(f"bad bech32m checksum in {addr}")
    acc = bits = 0
    out = bytearray()
    for v in vals[:-6]:
        acc, bits = acc << 5 | v, bits + 5
        if bits >= 8:
            bits -= 8
            out.append(acc >> bits & 255)
    return bytes(out)


BASE = "http://localhost:6869"
API_KEY = "hearth-private-node"  # hearth.custom.conf api-key-hash
FUNDED = "phrth1gxv7se8ueq623ukgwxmesapatdmhay84f0sfk0"  # the pre-committed generator, nonce 0 of wallet.seed
# Node-side definitions mirrored by hand (see README "Live StartBoost check").
FRESHNESS_WINDOW = 100  # StartBoostTransactionDiff.FreshnessWindowBlocks
REPORT_DATA_OFFSET = 48 + 520  # quote header + TD10 report body up to report_data (DcapQuote)
TX_TRANSFER, TX_COMMIT_TO_GENERATION, TX_START_BOOST = 2, 6, 7  # TransactionType ids
TX_RESERVE, TX_BIND_API_KEY, TX_SETTLE, TX_UPDATE_COLLATERAL = 8, 9, 10, 12
FEE = 100_000  # sign fills this default for Reserve/BindApiKey/Settle/StartBoost/UpdateCollateral (CommitToGeneration is 100 units; Transfer needs explicit fee)
TRANSFER_FEE = 200_000  # Transfer needs explicit fee (min: base + 1 unit per 2 transfers) and timestamp
# Multiples of 10 so the 30%/60% Fraction splits (delta/10*3) stay exact in balance asserts.
RESERVE, S1, S2 = 1_000_000, 600_000, 800_000

# Fail fast before touching the node: the hand-rolled crypto must reproduce committed vectors.
if ed25519_sign(DEMO_SEED, DEMO_MESSAGE).hex() != DEMO_SIGNATURE:
    sys.exit("self-check failed: ed25519_sign does not reproduce the committed demo signature")
if bech32m_decode(FUNDED).hex() != "4199e864fcc834a8f2c871b798743d5b777e90f5":
    sys.exit("self-check failed: bech32m_decode does not reproduce the funded address hash")

if len(sys.argv) != 2:
    sys.exit(__doc__)
FIX = sys.argv[1]
PCK_CRL = os.environ.get("PCK_CRL", "platform")
if PCK_CRL not in ("platform", "processor"):
    sys.exit("PCK_CRL must be platform or processor")


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def req(method, path, body=None, auth=False):
    r = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, method=method)
    r.add_header("Content-Type", "application/json")
    if auth:
        r.add_header("X-API-Key", API_KEY)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw.decode(errors="replace")


def get(path):
    s, body = req("GET", path)
    if s != 200:
        sys.exit(f"GET {path} -> {s}: {body}")
    return body


def height():
    return get("/blocks/height")["height"]


def hexfile(name, der=False):
    # Field formats differ on purpose: CRL fields are DER while issuer chains stay PEM, so only a CRL served as
    # PEM by Intel PCS gets decoded (first block; a CRL file has exactly one).
    with open(os.path.join(FIX, "collateral", name), "rb") as f:
        raw = f.read()
    if der and raw.lstrip().startswith(b"-----BEGIN"):
        raw = base64.b64decode(b"".join(raw.split(b"-----")[2].split()))
    return raw.hex()


def sign_and_broadcast(label, tx):
    s, signed = req("POST", "/transactions/sign", tx, auth=True)
    if s != 200:
        sys.exit(f"[{label}] sign failed {s}: {signed}")
    s, res = req("POST", "/transactions/broadcast", signed)
    if s != 200:
        sys.exit(f"[{label}] broadcast failed {s}: {res}")
    log(f"[{label}] broadcast id={res['id']} at height {height()}")
    return res["id"]


def wait_confirmed(label, txid):
    deadline = time.time() + 180
    while time.time() < deadline:
        try:
            s, info = req("GET", f"/transactions/info/{txid}")
        except urllib.error.URLError:  # transient during a block; keep polling to the deadline
            time.sleep(2)
            continue
        if s == 200 and "height" in info:
            log(f"[{label}] confirmed in block {info['height']}")
            return info
        time.sleep(2)
    sys.exit(f"[{label}] not confirmed after 180s; utx: {get('/transactions/unconfirmed')}")


def balance(a):
    return get(f"/addresses/balance/{a}")["balance"]


def new_account():
    return req("POST", "/addresses", {}, auth=True)[1]["address"]


def expect_rejected(label, tx, needle):
    s, signed = req("POST", "/transactions/sign", tx, auth=True)
    if s != 200:
        sys.exit(f"[{label}] sign failed {s}: {signed}")
    s, res = req("POST", "/transactions/broadcast", signed)
    if s == 200:
        sys.exit(f"[{label}] was accepted but must be rejected: {res}")
    if needle not in json.dumps(res):
        sys.exit(f"[{label}] rejected with an unexpected error: {res}")
    log(f"[{label}] rejected as expected: {needle}")


def check_fresh():
    h = height()
    # The window is checked again when the transaction is applied, a block or two after broadcast.
    if h >= FRESHNESS_WINDOW:
        sys.exit(f"height {h} is past the freshness window; restart the node from a fresh volume and rerun")


for _ in range(120):
    try:
        s, st = req("GET", "/node/status")
        if s == 200:
            break
    except OSError:  # port mapped but nothing listening yet
        pass
    time.sleep(2)
else:
    sys.exit("node never came up")
log("node:", st)

# The node generates the wallet's first account (nonce 0, the funded generator) itself at startup.
if FUNDED not in get("/addresses"):
    sys.exit(f"{FUNDED} is not in the node's wallet")
addr = FUNDED
log("sender/validator:", addr, "balance:", get(f"/addresses/balance/{addr}")["balance"])
next_start = get("/blockchain/finality")["nextGenerationPeriod"]["start"]
check_fresh()

# Root CA CRL first: every issuer-chain check below resolves revocation against what is already on chain.
root_crl = {"type": TX_UPDATE_COLLATERAL, "sender": addr, "rootCaCrl": hexfile("rootca.crl.der")}
wait_confirmed("rootCaCrl", sign_and_broadcast("rootCaCrl", root_crl))
collateral = {
    "type": TX_UPDATE_COLLATERAL,
    "sender": addr,
    "pckCaIssuerChain": hexfile("pck-ca-issuer-chain.pem"),
    "pckCrl": hexfile(f"pckcrl-{PCK_CRL}.pem", der=True),
    "tcbSigningIssuerChain": hexfile("tcb-signing-issuer-chain.pem"),
    "tcbInfo": hexfile("tcbinfo.json"),
    "qeIdentity": hexfile("qeidentity.json"),
}
wait_confirmed("collateral", sign_and_broadcast("collateral", collateral))

# Commit the generator for the next period (the node fills in its generator keys and proofs of possession).
wait_confirmed("commitToGeneration", sign_and_broadcast("commitToGeneration", {"type": TX_COMMIT_TO_GENERATION, "sender": addr}))
fin = get("/blockchain/finality")
if not any(g["address"] == addr for g in fin["nextGenerators"]):
    sys.exit(f"{addr} is not a committed generator of the next period: {fin}")

# Client and operator are fresh wallet accounts (nonce 1 and 2): unlike the funded generator, no block rewards or
# fees land on them, so every balance assert below is exact.
client, miner_op = new_account(), new_account()
for who, amount in ((client, 1_500_000), (miner_op, 500_000)):
    # Unlike the other types, TransferRequest demands explicit fee and timestamp; sign still resolves the key
    # from "sender" and injects senderPublicKey itself.
    tx = {
        "type": TX_TRANSFER,
        "sender": addr,
        "transfers": [{"recipient": who, "amount": amount}],
        "fee": TRANSFER_FEE,
        "timestamp": int(time.time() * 1000),
    }
    wait_confirmed("transfer", sign_and_broadcast("transfer", tx))
if balance(client) != 1_500_000 or balance(miner_op) != 500_000:
    sys.exit(f"funding balances off: {balance(client)}, {balance(miner_op)}")

check_fresh()
with open(os.path.join(FIX, "quote.hex")) as f:
    quote_hex = f.read().strip()
quote = bytes.fromhex(quote_hex)
# Header version 4 and tee_type TDX (little endian): REPORT_DATA_OFFSET is the TD10 layout, nothing else is sliced.
if quote[0:2] != b"\x04\x00" or quote[4:8] != b"\x81\x00\x00\x00":
    sys.exit("quote.hex is not a TDX v4 quote")
enclave_key = quote[REPORT_DATA_OFFSET + 32 : REPORT_DATA_OFFSET + 64].hex()
if ed25519_pubkey(DEMO_SEED).hex() != enclave_key:
    sys.exit("quote's enclave key does not derive from the demo seed; Settle below could not be signed")

# The operator (StartBoost sender) and the validator are distinct parties, as in production.
tx = {"type": TX_START_BOOST, "sender": miner_op, "validator": addr, "tdxQuote": quote_hex, "generationPeriodStart": next_start}
info = wait_confirmed("startBoost", sign_and_broadcast("startBoost", tx))
log("startBoost:", {k: v for k, v in info.items() if k != "tdxQuote"})

fin = get("/blockchain/finality")
regs = [r for r in fin["nextRegisteredEnclaves"] if r["enclavePublicKey"] == enclave_key]
if not regs:
    sys.exit(f"enclave {enclave_key} is not registered: {fin}")
if any(r["operator"] != miner_op or r["validator"] != addr for r in regs):
    sys.exit(f"registered with an unexpected operator/validator: {regs}")
log("registered for period", fin["nextGenerationPeriod"], "->", regs)

# --- Reserve -> BindApiKey -> Settle, all against the enclave registered above ---
reserve = {"type": TX_RESERVE, "sender": client, "amount": RESERVE, "miner": miner_op}
wait_confirmed("reserve", sign_and_broadcast("reserve", reserve))
if balance(client) != 1_500_000 - RESERVE - FEE:
    sys.exit(f"client balance after Reserve: {balance(client)}")

bind = {"type": TX_BIND_API_KEY, "sender": client, "enclavePublicKey": enclave_key, "encryptedApiKey": DEMO_ENVELOPE}
wait_confirmed("bindApiKey", sign_and_broadcast("bindApiKey", bind))
if balance(client) != 1_500_000 - RESERVE - 2 * FEE:
    sys.exit(f"client balance after BindApiKey: {balance(client)}")


def settle_tx(cumulative):
    # The enclave-signed message: client(20) ++ assetId(32, zeros for Hearth) ++ cumulativeSpent(8 BE) per settlement.
    msg = bech32m_decode(client) + bytes(32) + cumulative.to_bytes(8, "big")
    return {
        "type": TX_SETTLE,
        "sender": miner_op,
        "enclavePublicKey": enclave_key,
        "settlements": [{"client": client, "cumulativeSpent": cumulative}],
        "enclaveSignature": ed25519_sign(DEMO_SEED, msg).hex(),
    }


expected = balance(miner_op)
for cum, prev in ((S1, 0), (S2, S1)):
    wait_confirmed(f"settle:{cum}", sign_and_broadcast(f"settle:{cum}", settle_tx(cum)))
    expected += (cum - prev) // 10 * 3 - FEE  # operator keeps the 30% node share, minus the fee
    if balance(miner_op) != expected:
        sys.exit(f"operator balance after settle {cum}: {balance(miner_op)}, expected {expected}")

# A replayed (or withheld-then-resent) old batch must fail the non-decreasing counter, and a counter can never
# outgrow what the client reserved.
expect_rejected("settle:replay", settle_tx(S1), "would decrease")
expect_rejected("settle:over-reserve", settle_tx(RESERVE + 10), "exceeds total reserved")
log("full cycle done: reserve", RESERVE, "settled", S2, "operator credited", S2 // 10 * 3)
