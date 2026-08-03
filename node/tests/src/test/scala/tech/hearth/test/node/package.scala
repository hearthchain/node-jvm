package tech.hearth.test

import java.util.concurrent.ThreadLocalRandom
import tech.hearth.account.Address
import tech.hearth.crypto.KeyLength
import tech.hearth.crypto.SigningKey

package object node {
  def randomKeyPair(): SigningKey = {
    val seed = new Array[Byte](KeyLength)
    ThreadLocalRandom.current().nextBytes(seed)
    SigningKey.fromSeed(seed)
  }

  def randomAddress(): Address = randomKeyPair().toAddress
}
