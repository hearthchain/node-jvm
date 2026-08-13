---
purpose: provenance note for vendored Intel DCAP PKI test fixtures
---

`root.der`, `root_crl.der`, `signing.der` are real Intel DCAP PKI artifacts (Root CA certificate, Root CA CRL,
TCB Signing CA certificate), copied unmodified from `automata-network/automata-dcap-attestation`
(`solana/automata-dcap-framework/zk/p256-sp1/lib/src/tests/samples/`, MIT licensed), commit as of 2026-08-13.

`root.der`'s public key matches `IntelPki.rootCaPublicKey` exactly, and `root_crl.der`/`signing.der` both verify
against it (confirmed with `openssl crl -CAfile`/`openssl verify` before vendoring) - these let `IntelPkiTest`
exercise the real accept path against production code's actual pinned key, not just a synthetic throwaway one.

`root_crl.der` is valid 2024-03-20 to 2025-04-03; `signing.der` is valid 2018-05-21 to 2025-05-21. Tests using
these fixtures pin `atTime` to a fixed timestamp inside both windows rather than `System.currentTimeMillis()`.
