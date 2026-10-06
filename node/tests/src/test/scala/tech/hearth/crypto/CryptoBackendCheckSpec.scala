package tech.hearth.crypto

import tech.hearth.test.FlatSpec

class CryptoBackendCheckSpec extends FlatSpec {
  "A node" should "start on libsodium" in {
    CryptoBackendCheck.refusal("libsodium", requested = None) shouldBe None
  }

  it should "refuse the pure-JVM backend it fell back to" in {
    CryptoBackendCheck.refusal("jvm", requested = None) shouldBe defined
  }

  it should "start on the pure-JVM backend when it was chosen on purpose" in {
    CryptoBackendCheck.refusal("jvm", requested = Some(" JVM ")) shouldBe None
  }

  it should "not take a request for libsodium as consent to run without it" in {
    CryptoBackendCheck.refusal("jvm", requested = Some("sodium")) shouldBe defined
  }
}
