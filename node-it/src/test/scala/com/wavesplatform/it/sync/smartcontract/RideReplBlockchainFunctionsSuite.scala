package com.wavesplatform.it.sync.smartcontract

import java.nio.charset.StandardCharsets

import com.typesafe.config.Config
import com.wavesplatform.account.Alias
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base16
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.it.NodeConfigs
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.it.api.TransferTransactionInfo
import com.wavesplatform.it.sync.*
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.lang.v1.repl.Repl
import com.wavesplatform.lang.v1.repl.node.http.NodeConnectionSettings
import com.wavesplatform.state.{BinaryDataEntry, BooleanDataEntry, IntegerDataEntry, StringDataEntry, Height}
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.{TxHelpers, TxVersion}
import org.scalatest.Assertion
import org.scalatest.EitherValues.*

import scala.concurrent.Await
import scala.concurrent.duration.*

class RideReplBlockchainFunctionsSuite extends BaseTransactionSuite {
  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] = Seq(Miners.head.quorum(0))

  private def alice = firstKeyPair
  private def bob   = secondKeyPair

  private lazy val chainId: Char = miner.settings.blockchainSettings.addressSchemeCharacter

  private lazy val settings = NodeConnectionSettings(miner.nodeApiEndpoint.toString, chainId.toByte, alice.toAddress.toString)
  private lazy val repl     = Repl(Some(settings))

  private var dataTxId      = ""
  private var assetId       = ""
  private var transferTxIds = Map[TxVersion, String]()

  private val alias          = "nickname"
  private val transferAmount = 100
  private val attachment     = "attachment"

  private def execute(expr: String): Either[String, String] =
    Await.result(repl.execute(expr), 2 seconds)

  private def assert(expr: String, result: String): Assertion =
    execute(expr).explicitGet() should endWith(result)

  test("prepare") {
    dataTxId = sender
      .putData(
        alice,
        List(
          BinaryDataEntry("bin", ByteStr("binary".getBytes)),
          BooleanDataEntry("bool1", true),
          BooleanDataEntry("bool2", false),
          IntegerDataEntry("int", 100500),
          StringDataEntry("str", "Hello")
        ),
        minFee
      )
      .id

    sender.createAlias(bob, alias, minFee).id
    assetId = sender.issue(alice, "Asset", "descr", 1000, 2, waitForTx = true).id

    transferTxIds = Seq(TxVersion.V1, TxVersion.V2, TxVersion.V3).map { version =>
      val tx = sender.transfer(
        alice,
        s"alias:$chainId:$alias",
        transferAmount,
        minFee,
        Some(assetId),
        version = version,
        attachment = Some(attachment)
      )
      (version, tx.id)
    }.toMap

    transferTxIds.values.foreach(nodes.waitForHeightAriseAndTxPresent)
  }

  test("this") {
    assert("this.toString()", s""""${alice.toAddress}"""")
  }

  test("height") {
    assert("height > 0", "true")
  }

  test("lastBlock") {
    assert("lastBlock.height == height", "true")
  }

  test("tx variable doesn't exist") {
    execute("tx").left.value should include("A definition of 'tx' is not found")
  }

  test("assetBalance()") {
    assert(s"this.assetBalance(base16'$assetId')", "= 700")
  }

  test("wavesBalance()") {
    assert(
      "this.wavesBalance()",
      """
        |BalanceDetails(
        |	available = 9899600000
        |	regular = 9899600000
        |	generating = 0
        |	effective = 9899600000
        |)
      """.trim.stripMargin
    )
  }

  test("getBinary()") {
    assert(
      """this.getBinary("bin").value()""",
      s" base16\'${Base16.encode("binary".getBytes)}\'"
    )
  }

  test("getBinaryValue()") {
    assert(
      """this.getBinaryValue("bin")""",
      s" base16\'${Base16.encode("binary".getBytes)}\'"
    )
  }

  test("getBoolean()") {
    assert("""this.getBoolean("bool1").value()""", " true")
    assert("""this.getBoolean("bool2").value()""", " false")
  }

  test("getBooleanValue()") {
    assert("""this.getBooleanValue("bool1")""", " true")
    assert("""this.getBooleanValue("bool2")""", " false")
  }

  test("getInteger()") {
    assert("""this.getInteger("int").value()""", " 100500")
  }

  test("getIntegerValue()") {
    assert("""this.getIntegerValue("int")""", " 100500")
  }

  test("getString()") {
    assert("""this.getString("str").value()""", " \"Hello\"")
  }

  test("getStringValue()") {
    assert("""this.getStringValue("str")""", " \"Hello\"")
  }

  test("assetInfo()") {
    assert(
      s"assetInfo(base16'$assetId').value().issuer.toString()",
      s""""${alice.toAddress}""""
    )
  }

  test("blockInfoByHeight()") {
    val h  = miner.height - 1
    val bi = miner.blockAt(h)
    execute(s"let bi = blockInfoByHeight($h).value()")
    assert(
      s"bi",
      s"""
         |BlockInfo(
         |	baseTarget = ${bi.baseTarget.get}
         |	generator = Address(
         |		bytes = base16'${bi.generator}'
         |	)
         |	timestamp = ${bi.timestamp}
         |	vrf = base16'${bi.vrf.get}'
         |	generationSignature = base16'${bi.generationSignature.get}'
         |	generatorPublicKey = base16'${bi.generatorPublicKey}'
         |	height = ${bi.height}
         |	rewards = []
         |)
      """.trim.stripMargin
    )
  }

  test("transactionHeightById()") {
    assert(s"transactionHeightById(base16'$dataTxId').value() > 0", "true")
  }

  test("transferTransactionById()") {
    Seq(TxVersion.V1, TxVersion.V2, TxVersion.V3)
      .foreach { version =>
        val transferTxId = transferTxIds(version)
        val responseTx   = sender.transactionInfo[TransferTransactionInfo](transferTxId)
        val bodyBytes = TxHelpers.transfer(
            from = alice,
            to = Alias.createWithChainId(alias, chainId.toByte).explicitGet(),
            asset = IssuedAsset(ByteStr.decodeBase16(assetId).get),
            amount = transferAmount,
            feeAsset = Waves,
            fee = responseTx.fee,
            attachment = ByteStr(attachment.getBytes(StandardCharsets.UTF_8)),
            timestamp = responseTx.timestamp,
            version = version
          )
          .bodyBytes
          .value()

        execute(s"let transferTx$version = transferTransactionById(base16'$transferTxId').value()")
        assert(
          s"transferTx$version",
          s"""
               |TransferTransaction(
               |	recipient = Alias(
               |		alias = "$alias"
               |	)
               |	timestamp = ${responseTx.timestamp}
               |	bodyBytes = base16'${Base16.encode(bodyBytes)}'
               |	assetId = base16'$assetId'
               |	feeAssetId = Unit
               |	amount = 100
               |	version = $version
               |	id = base16'$transferTxId'
               |	senderPublicKey = base16'${alice.publicKey}'
               |	attachment = base16'${ByteStr(attachment.getBytes(StandardCharsets.UTF_8))}'
               |	sender = Address(
               |		bytes = base16'${responseTx.sender.get}'
               |	)
               |	proofs = [base16'${responseTx.proofs.get.head}', base16'', base16'', base16'', base16'', base16'', base16'', base16'']
               |	fee = ${responseTx.fee}
               |)
            """.trim.stripMargin
        )
      }
  }

  test("addressFromPublicKey()") {
    assert(
      s"addressFromPublicKey(base16'${alice.publicKey}').value().toString()",
      s""""${alice.toAddress}""""
    )
  }

  test("addressFromRecipient() with alias") {
    assert(
      s"""addressFromRecipient(transferTx1.recipient).toString()""",
      s""""${bob.toAddress}""""
    )
  }

  test("addressFromString()") {
    assert(
      s"""addressFromString("${alice.toAddress}").value().toString()""",
      s""""${alice.toAddress}""""
    )
  }

  test("addressFromStringValue()") {
    assert(
      s"""addressFromStringValue("${alice.toAddress}").toString()""",
      s""""${alice.toAddress}""""
    )
  }
}
