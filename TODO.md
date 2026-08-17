[ ] When generator set is empty for some reason, allow a non-committed generator to forge a block. They put their
    VRF public key in a block, and that key is implicitly committed for the current generating period only. They can 
    forge blocks in the current generating period as long as generator set is empty. Once the set is non-empty, only
    committed generators can forge blocks.

[ ] Cleanup transaction meta. Transfers no longer have aliases, so there's no need to resolve addresses.
