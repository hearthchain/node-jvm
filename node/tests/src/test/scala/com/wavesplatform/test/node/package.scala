package com.wavesplatform.test

import java.util.concurrent.ThreadLocalRandom
import com.wavesplatform.account.Address
import com.wavesplatform.crypto.KeyLength
import tech.hearth.crypto.SigningKey

package object node {
  def randomKeyPair(): SigningKey = {
    val seed = new Array[Byte](KeyLength)
    ThreadLocalRandom.current().nextBytes(seed)
    SigningKey.fromSeed(seed)
  }

  def randomAddress(): Address = randomKeyPair().toAddress
}
