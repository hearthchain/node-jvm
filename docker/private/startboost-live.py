#!/usr/bin/env python3
"""Drive the full StartBoost path against a freshly started private node over REST (stdlib only).

Usage: startboost-live.py <fixture-dir> [node-url]

<fixture-dir> holds quote.hex (a real TDX quote whose report_data[0:32] is this image's genesis block id and
report_data[32:64] the enclave key to register) and collateral/ with rootca.crl.der, pck-ca-issuer-chain.pem,
pckcrl-platform.pem or pckcrl-processor.pem (PCK_CRL=platform|processor picks which, default platform: it must be the
CA that issued the quote's PCK leaf), tcb-signing-issuer-chain.pem, tcbinfo.json, qeidentity.json. Run it within the
first minutes of the chain: the quote is fresh only while height < 101 (StartBoostTransactionDiff.FreshnessWindowBlocks).
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

FIX = sys.argv[1]
BASE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:6869"
API_KEY = os.environ.get("API_KEY", "hearth-private-node")
# The funded, pre-committed generator of hearth.custom.conf; the node's wallet controls it (nonce 0 of wallet.seed).
FUNDED = os.environ.get("FUNDED", "phrth1gxv7se8ueq623ukgwxmesapatdmhay84f0sfk0")
PCK_CRL = os.environ.get("PCK_CRL", "platform")
if PCK_CRL not in ("platform", "processor"):
    sys.exit("PCK_CRL must be platform or processor")
FRESHNESS_WINDOW = 100  # StartBoostTransactionDiff.FreshnessWindowBlocks
# TransactionType ids
TX_COMMIT_TO_GENERATION, TX_START_BOOST, TX_UPDATE_COLLATERAL = 6, 7, 12


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
    return req("GET", path)


def height():
    return get("/blocks/height")[1]["height"]


def hexfile(name, der=False):
    p = os.path.join(FIX, "collateral", name)
    if der:  # CRL fields are DER; Intel PCS serves PCK CRLs as PEM
        return subprocess.run(["openssl", "crl", "-in", p, "-outform", "DER"], capture_output=True, check=True).stdout.hex()
    with open(p, "rb") as f:
        return f.read().hex()


def sign_and_broadcast(label, tx):
    s, signed = req("POST", "/transactions/sign", tx, auth=True)
    if s != 200:
        sys.exit(f"[{label}] sign failed {s}: {signed}")
    s, res = req("POST", "/transactions/broadcast", signed)
    if s != 200:
        sys.exit(f"[{label}] broadcast failed {s}: {res}")
    log(f"[{label}] broadcast id={res['id']} at height {height()}")
    return res["id"]


def wait_confirmed(label, txid, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        s, info = get(f"/transactions/info/{txid}")
        if s == 200 and "height" in info:
            log(f"[{label}] confirmed in block {info['height']}")
            return info
        time.sleep(2)
    sys.exit(f"[{label}] not confirmed after {timeout}s; utx: {get('/transactions/unconfirmed')[1]}")


for _ in range(120):
    try:
        s, st = get("/node/status")
        if s == 200:
            break
    except OSError:  # port mapped but nothing listening yet
        pass
    time.sleep(2)
else:
    sys.exit("node never came up")
log("node:", st)

# The node generates the wallet's first account (nonce 0, the funded generator) itself at startup.
s, accs = get("/addresses")
if FUNDED not in accs:
    sys.exit(f"{FUNDED} is not in the node's wallet: {accs}")
addr = FUNDED
log("sender/validator:", addr, "balance:", get(f"/addresses/balance/{addr}")[1]["balance"])
next_start = get("/blockchain/finality")[1]["nextGenerationPeriod"]["start"]


def check_fresh():
    h = height()
    # One block of margin: the window is checked again when the transaction is applied, a block or two after broadcast.
    if h >= FRESHNESS_WINDOW:
        sys.exit(f"height {h} is past the freshness window; restart the node from a fresh volume and rerun")
    return h


check_fresh()

# Root CA CRL first: every issuer-chain check below resolves revocation against what is already on chain.
wait_confirmed("rootCaCrl", sign_and_broadcast("rootCaCrl", {"type": TX_UPDATE_COLLATERAL, "sender": addr, "rootCaCrl": hexfile("rootca.crl.der")}))
wait_confirmed("collateral", sign_and_broadcast("collateral", {
    "type": TX_UPDATE_COLLATERAL, "sender": addr,
    "pckCaIssuerChain": hexfile("pck-ca-issuer-chain.pem"),
    "pckCrl": hexfile(f"pckcrl-{PCK_CRL}.pem", der=True),
    "tcbSigningIssuerChain": hexfile("tcb-signing-issuer-chain.pem"),
    "tcbInfo": hexfile("tcbinfo.json"),
    "qeIdentity": hexfile("qeidentity.json"),
}))

# Commit the generator for the next period (the node fills in its generator keys and proofs of possession).
wait_confirmed("commitToGeneration", sign_and_broadcast("commitToGeneration", {"type": TX_COMMIT_TO_GENERATION, "sender": addr}))
fin = get("/blockchain/finality")[1]
if not any(g["address"] == addr for g in fin["nextGenerators"]):
    sys.exit(f"{addr} is not a committed generator of the next period: {fin}")

h = check_fresh()
with open(os.path.join(FIX, "quote.hex")) as f:
    quote_hex = f.read().strip()
quote = bytes.fromhex(quote_hex)
if quote[4:8] != b"\x81\x00\x00\x00":  # header tee_type, little endian; only a TDX body has report_data where we slice it
    sys.exit("quote.hex is not a TDX quote")
tx = {"type": TX_START_BOOST, "sender": addr, "validator": addr, "tdxQuote": quote_hex, "generationPeriodStart": next_start}
info = wait_confirmed("startBoost", sign_and_broadcast("startBoost", tx))
log("startBoost:", {k: v for k, v in info.items() if k != "tdxQuote"})

REPORT_DATA_OFFSET = 48 + 520  # quote header + TD report body up to report_data (see DcapQuote)
enclave_key = quote[REPORT_DATA_OFFSET + 32 : REPORT_DATA_OFFSET + 64].hex()
fin = get("/blockchain/finality")[1]
regs = [r for r in fin["nextRegisteredEnclaves"] if r["enclavePublicKey"] == enclave_key]
if not regs:
    sys.exit(f"enclave {enclave_key} is not registered: {fin}")
if any(r["operator"] != addr or r["validator"] != addr for r in regs):
    sys.exit(f"registered with an unexpected operator/validator: {regs}")
log("registered for period", fin["nextGenerationPeriod"], "->", regs)
