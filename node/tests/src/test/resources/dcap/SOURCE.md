---
purpose: provenance note for vendored Intel DCAP PKI test fixtures
---

`root.der`, `root_crl.der`, `signing.der`, `tcb_info_v3_sgx.json`, `qe_identity.json` are real Intel DCAP PKI
artifacts (Root CA certificate, Root CA CRL, TCB Signing CA certificate, a real TCB Info V3 and Enclave Identity V2
response), copied unmodified from `automata-network/automata-dcap-attestation`
(`solana/automata-dcap-framework/zk/p256-sp1/lib/src/tests/samples/`, MIT licensed), commit as of 2026-08-13.

`root.der`'s public key matches `IntelPki.rootCaPublicKey` exactly, and `root_crl.der`/`signing.der` both verify
against it; `tcb_info_v3_sgx.json`/`qe_identity.json`'s own `"signature"` fields both verify against `signing.der`'s
public key over the raw bytes of their nested `"tcbInfo"`/`"enclaveIdentity"` object (confirmed with
`openssl crl -CAfile`/`openssl verify`/`openssl dgst -verify` before vendoring) - these let IntelPkiTest and
UpdateCollateralTransactionDiffTest exercise the real accept path against production code's actual pinned key, not
just a synthetic throwaway one.

Validity windows (tests pin `atTime` to a fixed timestamp inside the relevant intersection, never
`System.currentTimeMillis()`):
- `root_crl.der`: 2024-03-20 to 2025-04-03
- `signing.der`: 2018-05-21 to 2025-05-21
- `tcb_info_v3_sgx.json`: 2024-08-26 to 2024-09-25 (fmspc `00A067110000`, tcbEvaluationDataNumber 16)
- `qe_identity.json`: 2024-09-10 to 2024-10-10 (tcbEvaluationDataNumber 16)
