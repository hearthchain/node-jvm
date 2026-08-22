#!/usr/bin/env python3
"""Drive the full StartBoost path against a freshly started private node on localhost:6869 over REST (stdlib only).

Usage: startboost-live.py <fixture-dir>

<fixture-dir> holds quote.hex (a TDX v4 quote whose report_data[0:32] is this image's genesis block id and
report_data[32:64] the enclave key to register) and collateral/ with rootca.crl.der, pck-ca-issuer-chain.pem,
pckcrl-platform.pem or pckcrl-processor.pem (PCK_CRL=platform|processor picks which, default platform: it must be the
CA that issued the quote's PCK leaf), tcb-signing-issuer-chain.pem, tcbinfo.json, qeidentity.json. Run it within the
first minutes of the chain: the quote is fresh only while height < 101 (StartBoostTransactionDiff.FreshnessWindowBlocks).
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:6869"
API_KEY = "hearth-private-node"  # hearth.custom.conf api-key-hash
FUNDED = "phrth1gxv7se8ueq623ukgwxmesapatdmhay84f0sfk0"  # the pre-committed generator, nonce 0 of wallet.seed
# Node-side definitions mirrored by hand (see README "Live StartBoost check").
FRESHNESS_WINDOW = 100  # StartBoostTransactionDiff.FreshnessWindowBlocks
REPORT_DATA_OFFSET = 48 + 520  # quote header + TD10 report body up to report_data (DcapQuote)
TX_COMMIT_TO_GENERATION, TX_START_BOOST, TX_UPDATE_COLLATERAL = 6, 7, 12  # TransactionType ids

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


def hexfile(name):
    with open(os.path.join(FIX, "collateral", name), "rb") as f:
        raw = f.read()
    if raw.lstrip().startswith(b"-----BEGIN"):  # Intel PCS serves PCK CRLs as PEM; the field is DER
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
        s, info = req("GET", f"/transactions/info/{txid}")
        if s == 200 and "height" in info:
            log(f"[{label}] confirmed in block {info['height']}")
            return info
        time.sleep(2)
    sys.exit(f"[{label}] not confirmed after 180s; utx: {get('/transactions/unconfirmed')}")


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
    "pckCrl": hexfile(f"pckcrl-{PCK_CRL}.pem"),
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

check_fresh()
with open(os.path.join(FIX, "quote.hex")) as f:
    quote_hex = f.read().strip()
quote = bytes.fromhex(quote_hex)
# Header version 4 and tee_type TDX (little endian): REPORT_DATA_OFFSET is the TD10 layout, nothing else is sliced.
if quote[0:2] != b"\x04\x00" or quote[4:8] != b"\x81\x00\x00\x00":
    sys.exit("quote.hex is not a TDX v4 quote")
tx = {"type": TX_START_BOOST, "sender": addr, "validator": addr, "tdxQuote": quote_hex, "generationPeriodStart": next_start}
info = wait_confirmed("startBoost", sign_and_broadcast("startBoost", tx))
log("startBoost:", {k: v for k, v in info.items() if k != "tdxQuote"})

enclave_key = quote[REPORT_DATA_OFFSET + 32 : REPORT_DATA_OFFSET + 64].hex()
fin = get("/blockchain/finality")
regs = [r for r in fin["nextRegisteredEnclaves"] if r["enclavePublicKey"] == enclave_key]
if not regs:
    sys.exit(f"enclave {enclave_key} is not registered: {fin}")
if any(r["operator"] != addr or r["validator"] != addr for r in regs):
    sys.exit(f"registered with an unexpected operator/validator: {regs}")
log("registered for period", fin["nextGenerationPeriod"], "->", regs)
