package com.wavesplatform.account

import com.wavesplatform.crypto
import com.wavesplatform.test.PropSpec
import tech.hearth.crypto.Address as HearthAddress

import scala.jdk.OptionConverters.*

class AccountSpecification extends PropSpec {

  // An address is 20 bytes that say nothing about a network; the network is the bech32m prefix a string carries, and
  // the version byte, chain id byte and base58 checksum it used to be told apart by are gone.
  private val otherNetworkHrp = HearthAddress.MAINNET_HRP.ensuring(_ != HearthAddress.defaultHrp())

  property("Address.fromString accepts an address of this network only") {
    forAll { (data: Array[Byte]) =>
      val address = HearthAddress.fromBytes(crypto.secureHash(data).take(HearthAddress.HASH_LEN)).toScala.get

      Address.fromString(address.toBech32) shouldBe Right(address)
      Address.fromString(address.toBech32(otherNetworkHrp)) should beLeft
    }
  }

  property("Address.fromString rejects a string whose checksum does not hold") {
    forAll { (data: Array[Byte]) =>
      val address = HearthAddress.fromBytes(crypto.secureHash(data).take(HearthAddress.HASH_LEN)).toScala.get
      val bech32  = address.toBech32
      // bech32m is case insensitive and its alphabet excludes '1', so swapping the last character of the data part
      // always yields a different, invalid string
      val corrupted = bech32.init + (if (bech32.last == 'q') 'p' else 'q')

      Address.fromString(corrupted) should beLeft
    }
  }
}
