package com.wavesplatform.state

import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto.bls.BlsPublicKey

/** A generator registering the keys it will generate and endorse with, for one generation period.
  *
  * Both keys have to be registered because a hearth key tree derives them independently of the account's signing key,
  * so neither can be recovered from a block header or a transaction sender.
  *
  * @param endorserPublicKey
  *   The BLS key this generator's endorsements are verified with
  * @param vrfPublicKey
  *   The VRF key this generator's block generation signatures are verified against
  */
case class GenerationCommitment(sender: PublicKey, endorserPublicKey: BlsPublicKey, vrfPublicKey: ByteStr) {
  def toCommittedGenerator: CommittedGenerator = CommittedGenerator(sender.toAddress, endorserPublicKey, vrfPublicKey)
}

/** A [[GenerationCommitment]] as the state holds it: the sender is only ever needed by address once committed. */
case class CommittedGenerator(address: Address, endorserPublicKey: BlsPublicKey, vrfPublicKey: ByteStr)
