package com.wavesplatform.common.utils

import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.test.PropSpec

class Base16Spec extends PropSpec {
  property("encodes to lowercase hex") {
    Base16.encode(Array[Byte](0x00, 0x01, 0x7f, 0xff.toByte)) shouldBe "00017fff"
    Base16.encode(Array.emptyByteArray) shouldBe ""
  }

  property("roundtrip") {
    forAll(genBoundedBytes(0, 300)) { bytes =>
      Base16.decode(Base16.encode(bytes)) shouldBe bytes
    }
  }

  property("decode is case-insensitive") {
    Base16.decode("00017FFF") shouldBe Array[Byte](0x00, 0x01, 0x7f, 0xff.toByte)
  }

  property("decode rejects invalid input") {
    Base16.tryDecode("zz").isFailure shouldBe true
    Base16.tryDecode("0").isFailure shouldBe true
    Base16.tryDecode("0x00").isFailure shouldBe true
  }

  property("tryDecodeWithLimit enforces the default limit") {
    val maxBytes = Array.fill[Byte](Base16.defaultDecodeLimit / 2)(1)
    Base16.tryDecodeWithLimit(Base16.encode(maxBytes)).isSuccess shouldBe true
    Base16.tryDecodeWithLimit("00" * (Base16.defaultDecodeLimit / 2 + 1)).isFailure shouldBe true
  }

  property("ByteStr renders as hex and parses back") {
    val bytes = ByteStr(Array[Byte](0x00, 0x01, 0x7f, 0xff.toByte))
    bytes.toString shouldBe "00017fff"
    val decoded = ByteStr.decodeBase16("00017fff").get
    decoded shouldBe bytes
  }

  property("large ByteStr still renders as base64") {
    val big = ByteStr(Array.fill[Byte](1024)(1))
    big.toString shouldBe big.base64
  }
}
