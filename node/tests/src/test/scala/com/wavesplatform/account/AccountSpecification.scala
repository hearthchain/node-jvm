package com.wavesplatform.account

import com.wavesplatform.crypto
import com.wavesplatform.test.PropSpec

class AccountSpecification extends PropSpec {

  property("Address.fromBytes should reject payloads with another address version") {
    forAll { (data: Array[Byte], version: Byte) =>
      val publicKeyHash = crypto.secureHash(data).take(Address.HashLength)
      val payload       = version +: publicKeyHash
      Address.fromBytes(payload).isRight shouldBe (version == Address.AddressVersion)
    }
  }
}
