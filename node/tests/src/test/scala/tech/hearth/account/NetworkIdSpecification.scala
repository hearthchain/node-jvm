package tech.hearth.account

import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsString, Json}
import pureconfig.ConfigSource
import tech.hearth.crypto.Address
import tech.hearth.test.FreeSpec

class NetworkIdSpecification extends FreeSpec {
  "accepts the networks it names" in {
    NetworkId.Mainnet.value shouldBe "hrth"
    NetworkId.Testnet.value shouldBe "thrth"
    NetworkId.Stagenet.value shouldBe "shrth"
  }

  "rejects anything that is not a valid HRP" in {
    NetworkId.fromString("") should beLeft
    NetworkId.fromString("a" * (NetworkId.MaxLength + 1)) should beLeft
    NetworkId.fromString("hrth1") should beLeft
    NetworkId.fromString("hr-th") should beLeft
    NetworkId.fromString("hrth ") should beLeft
    // Strict where Address.setDefaultHrp normalises: a wire value is taken exactly as signed, never lowercased.
    NetworkId.fromString("HRTH") should beLeft
    NetworkId.fromString("a" * NetworkId.MaxLength) should beRight
  }

  "current is whatever Address.setDefaultHrp pinned" in {
    // The suite base pins thrth; anything setDefaultHrp accepts is a valid NetworkId, so this cannot throw.
    NetworkId.current shouldBe NetworkId.Testnet
    Address.defaultHrp() shouldBe NetworkId.current.value
  }

  "round-trips through JSON as a plain string" in {
    Json.toJson(NetworkId.Mainnet) shouldBe JsString("hrth")
    Json.parse("\"hrth\"").as[NetworkId] shouldBe NetworkId.Mainnet
    Json.parse("\"HRTH\"").validate[NetworkId] shouldBe Symbol("error")
    Json.parse("84").validate[NetworkId] shouldBe Symbol("error")
  }

  "reads from config, rejecting an invalid one" in {
    val source = ConfigSource.fromConfig(ConfigFactory.parseString("""{ good = "hrth", bad = "HRTH" }"""))
    source.at("good").load[NetworkId] shouldBe Right(NetworkId.Mainnet)
    source.at("bad").load[NetworkId] should beLeft
  }
}
