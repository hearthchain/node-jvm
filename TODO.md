[x] When no committed generator of the period can generate (poor or in conflict), allow a non-committed generator
    to forge a block. Its first block in the period carries its VRF public key, and that key is implicitly committed
    for the current generating period only. It can keep forging blocks for the rest of the period, even after
    committed generators recover. Other uncommitted generators may join only while no committed generator can generate.

[ ] Cleanup transaction meta. Transfers no longer have aliases, so there's no need to resolve addresses.
