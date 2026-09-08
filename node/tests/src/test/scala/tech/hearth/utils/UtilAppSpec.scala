package tech.hearth.utils

import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsObject, Json}
import pureconfig.ConfigSource
import tech.hearth.common.utils.EitherExt2.explicitGet
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.{Bip39, KeyTree}
import tech.hearth.mining.GeneratorKeys
import tech.hearth.settings.MinerSettings
import tech.hearth.test.FlatSpec
import tech.hearth.utils.UtilApp.{Command, KeyPairOptions}

class UtilAppSpec extends FlatSpec {
  private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

  private def createKeys(mnemonic: Option[String], nonce: Int = 0): Either[String, Array[Byte]] =
    UtilApp.Actions.doCreateKeyPair(
      Command(keyPairOptions = KeyPairOptions(nonce = nonce, mnemonic = mnemonic)),
      sys.error("create-keys must not read its input")
    )

  private def createKeysJson(mnemonic: Option[String], nonce: Int): JsObject = Json.parse(createKeys(mnemonic, nonce).explicitGet()).as[JsObject]

  /** The generator keys the node builds from this entry, pasted into `hearth.miner.accounts` as create-keys printed it. */
  private def generatorKeys(account: JsObject): GeneratorKeys = {
    val config = ConfigFactory
      .parseString(s"""hearth.miner {
                      |  enable = yes
                      |  quorum = 1
                      |  interval-after-last-block-then-generation-is-allowed = 1d
                      |  no-quorum-mining-delay = 5s
                      |  micro-block-interval = 5s
                      |  minimal-block-generation-offset = 500ms
                      |  max-transactions-in-micro-block = 400
                      |  min-micro-block-age = 3s
                      |  accounts = [$account]
                      |  supported-features = []
                      |}""".stripMargin)
      .resolve()

    GeneratorKeys.fromSettings(ConfigSource.fromConfig(config).at("hearth.miner").loadOrThrow[MinerSettings])
  }

  "create-keys" should "generate a mining account that mines with the address it reports, without reading any input" in {
    val keys = createKeysJson(None, nonce = 1)

    Bip39.validate((keys \ "mnemonic").as[String]).isValid shouldBe true
    generatorKeys((keys \ "minerAccount").as[JsObject]).accounts.head.address.toString shouldBe (keys \ "address").as[String]
  }

  it should "derive the account from the mnemonic it is given" in {
    val keys = createKeysJson(Some(mnemonic), nonce = 2)

    (keys \ "mnemonic").as[String] shouldBe mnemonic
    (keys \ "address").as[String] shouldBe KeyTree.signingKey(Bip39.toSeed(mnemonic), 2).toAddress.toString
  }

  it should "print key material that configures the same generator keys as the mnemonic does" in {
    val keys = createKeysJson(None, nonce = 1)

    val fromMnemonic = generatorKeys((keys \ "minerAccount").as[JsObject]).accounts.head
    val fromKeys     = generatorKeys((keys \ "minerAccountKeys").as[JsObject]).accounts.head

    fromKeys.address shouldBe fromMnemonic.address
    ByteStr(fromKeys.vrfKey.publicKey()) shouldBe ByteStr(fromMnemonic.vrfKey.publicKey())
    fromKeys.blsKey.publicKey shouldBe fromMnemonic.blsKey.publicKey
  }

  it should "report the signing, VRF and BLS public keys of the account" in {
    val keys    = createKeysJson(None, nonce = 1)
    val account = generatorKeys((keys \ "minerAccount").as[JsObject]).accounts.head

    (keys \ "publicKey").as[String] shouldBe ByteStr(account.signingKey.publicKey()).toString
    (keys \ "vrfPublicKey").as[String] shouldBe ByteStr(account.vrfKey.publicKey()).toString
    (keys \ "blsPublicKey").as[String] shouldBe account.blsKey.publicKey.base16
  }

  it should "reject a phrase that is not a mnemonic" in {
    createKeys(Some("196bd8403a3cdcf4991edb4928419c71049d0c86f3707b9f441454e0888e61ad")).left.value should include("Invalid mnemonic")
  }
}
