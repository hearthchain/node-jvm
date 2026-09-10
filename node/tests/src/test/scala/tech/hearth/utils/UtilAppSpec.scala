package tech.hearth.utils

import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsObject, Json}
import pureconfig.ConfigSource
import tech.hearth.common.utils.EitherExt2.explicitGet
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.crypto.{Bip39, KeyTree}
import tech.hearth.mining.{GeneratorKeys, MiningAccount}
import tech.hearth.settings.{MinerSettings, WalletSettings}
import tech.hearth.test.FlatSpec
import tech.hearth.transaction.{CommitToGenerationTransaction, TransactionFactory, TransactionType}
import tech.hearth.utils.UtilApp.{Command, KeyPairOptions}
import tech.hearth.wallet.Wallet

class UtilAppSpec extends FlatSpec {
  private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

  private def createKeys(mnemonic: Option[String], nonce: Int = 0): Either[String, Array[Byte]] =
    UtilApp.Actions.doCreateKeyPair(
      Command(keyPairOptions = KeyPairOptions(nonce = nonce, mnemonic = mnemonic)),
      sys.error("create-keys must not read its input")
    )

  private def createKeysJson(mnemonic: Option[String], nonce: Int): JsObject = Json.parse(createKeys(mnemonic, nonce).explicitGet()).as[JsObject]

  /** The generator keys the node builds from these entries, pasted into `hearth.miner.accounts` as create-keys printed them. */
  private def generatorKeys(accounts: JsObject*): GeneratorKeys = {
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
                      |  accounts = [${accounts.mkString(",")}]
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

  private val periodStart = 101

  /** The `hearth.miner.accounts` entry create-keys prints for a fresh account, which is what a node config holds. */
  private def minerAccount(): JsObject = (createKeysJson(None, nonce = 0) \ "minerAccountKeys").as[JsObject]

  private def signCommitment(generatorKeys: GeneratorKeys, request: JsObject): Either[String, JsObject] =
    UtilApp.Actions
      .doSignTx(Wallet(WalletSettings(None, None, None)), generatorKeys, None, request.toString.getBytes)
      .map(Json.parse(_).as[JsObject])

  private def commitmentRequest(sender: Option[MiningAccount] = None): JsObject =
    Json.obj("type" -> TransactionType.CommitToGeneration.id, "generationPeriodStart" -> periodStart) ++
      sender.fold(Json.obj())(a => Json.obj("sender" -> a.address.toString))

  "transaction sign" should "register the generator keys of the sole configured miner account" in {
    val keys      = generatorKeys(minerAccount())
    val generator = keys.accounts.head

    val signed = signCommitment(keys, commitmentRequest()).explicitGet()

    (signed \ "sender").as[String] shouldBe generator.address.toString
    (signed \ "senderPublicKey").as[String] shouldBe generator.publicKey.toString
    (signed \ "endorserPublicKey").as[String] shouldBe generator.blsKey.publicKey.base16
    (signed \ "vrfPublicKey").as[String] shouldBe ByteStr(generator.vrfKey.publicKey()).toString
    (signed \ "generationPeriodStart").as[Int] shouldBe periodStart
  }

  it should "prove possession of both generator keys for the period it commits to" in {
    val keys = generatorKeys(minerAccount())
    val tx = TransactionFactory
      .parseRequest(signCommitment(keys, commitmentRequest()).explicitGet())
      .explicitGet()
      .asInstanceOf[CommitToGenerationTransaction]

    tx.commitmentSignature.verifyBasic(tx.popMessage, tx.endorserPublicKey).value
    crypto.verifyVRF(tx.vrfCommitmentSignature, tx.vrfPopMessage, tx.vrfPublicKey).value
    crypto.verify(tx.proofs.head, tx.bodyBytes(), tx.sender) shouldBe true
  }

  it should "commit for the account the request names when several are configured" in {
    val keys   = generatorKeys(minerAccount(), minerAccount())
    val second = keys.accounts(1)

    val signed = signCommitment(keys, commitmentRequest(Some(second))).explicitGet()

    (signed \ "sender").as[String] shouldBe second.address.toString
    (signed \ "endorserPublicKey").as[String] shouldBe second.blsKey.publicKey.base16
  }

  it should "refuse to guess which account to commit for when several are configured" in {
    signCommitment(generatorKeys(minerAccount(), minerAccount()), commitmentRequest()).left.value should include("invalid.sender")
  }

  it should "refuse to commit for an account the config does not hold the generator keys of" in {
    val stranger = generatorKeys(minerAccount()).accounts.head

    signCommitment(generatorKeys(minerAccount()), commitmentRequest(Some(stranger))).left.value should include(
      "is not one of this node's generators"
    )
  }

  it should "require the request to name the period it commits to" in {
    signCommitment(generatorKeys(minerAccount()), Json.obj("type" -> TransactionType.CommitToGeneration.id)).left.value should include(
      "missing generation period start"
    )
  }
}
