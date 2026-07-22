package com.wavesplatform.mining

import com.wavesplatform.account.PublicKey
import tech.hearth.crypto.{Address, SigningKey, VrfKey}

class MiningAccount(val signingKey: SigningKey, val vrfKey: VrfKey) {
  lazy val address: Address     = signingKey.toAddress
  lazy val publicKey: PublicKey = PublicKey(signingKey.publicKey())
}
