package tech.hearth.mining

import tech.hearth.account.PublicKey
import tech.hearth.crypto.bls.BlsKeyPair
import tech.hearth.crypto.{Address, SigningKey, VrfKey}

/** The three keys an account generates with: the signing key a block header is signed by, the VRF key its generation
  * signature is proved with, and the BLS key its endorsements are signed by. They are configured together, in
  * `hearth.miner.accounts`, and every one of them is committed on chain by a CommitToGenerationTransaction.
  */
class MiningAccount(val signingKey: SigningKey, val vrfKey: VrfKey, val blsKey: BlsKeyPair) {
  lazy val address: Address     = signingKey.toAddress
  lazy val publicKey: PublicKey = PublicKey(signingKey.publicKey())
}
