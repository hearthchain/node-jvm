package com.wavesplatform.it.repl

import com.typesafe.config.Config
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.*
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.it.sync.transactions.FailedTransactionSuiteLike
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3
import com.wavesplatform.lang.v1.repl.Repl
import com.wavesplatform.lang.v1.repl.node.http.NodeConnectionSettings
import com.wavesplatform.state.*
import com.wavesplatform.test.*
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import com.wavesplatform.transaction.{TxHelpers, TxVersion}

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class ReplTest extends BaseTransactionSuite with FailedTransactionSuiteLike[String] {
  override protected def waitForHeightArise(): Unit =
    nodes.waitForHeightArise()

  import com.wavesplatform.it.NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] =
    Seq(BiggestMiner.quorum(0).preactivatedFeatures(BlockchainFeatures.BlockV5))

  def await[A](f: Future[A]): A = Await.result(f, 2 seconds)

  test("waves context") {
    val issuer = TxHelpers.signer(1000)
    val sample = TxHelpers.signer(1001)
    val trans  = miner.transfer(miner.keyPair, issuer.toAddress.toString, 100.waves, 1.waves, version = TxVersion.V3, waitForTx = true)
    miner.transfer(miner.keyPair, sample.toAddress.toString, 100.waves, 1.waves, waitForTx = true)
    miner.createAlias(miner.keyPair, "aaaa", waitForTx = true)

    val failDApp = ScriptCompiler
      .compile(
        s"""
           |{-# STDLIB_VERSION 4 #-}
           |{-# CONTENT_TYPE DAPP #-}
           |{-# SCRIPT_TYPE ACCOUNT #-}
           |
           |@Callable(i)
           |func default() = {
           |  let action = valueOrElse(getString(this, "crash"), "no")
           |  let check = ${"sigVerify(base16'', base16'', base16'') ||" * 10} true
           |
           |  if (action == "yes")
           |  then {
           |    if (check)
           |    then throw("Crashed by dApp")
           |    else throw("Crashed by dApp")
           |  }
           |  else []
           |}
           |
           |""".stripMargin,
        ScriptEstimatorV3.latest
      )
      .explicitGet()
      ._1
      .bytes()
      .base64

    val assetScript = ScriptCompiler
      .compile(
        """
          |{-# STDLIB_VERSION 2 #-}
          |{-# CONTENT_TYPE EXPRESSION #-}
          |{-# SCRIPT_TYPE ASSET #-}
          |
          | false
          |""".stripMargin,
        ScriptEstimatorV3.latest
      )
      .explicitGet()
      ._1
      .bytes()
      .base64

    val assetId =
      miner
        .broadcastIssue(
          issuer,
          "asset",
          "description",
          1000,
          decimals = 1,
          reissuable = true,
          script = Some(assetScript),
          waitForTx = true,
          fee = 1.waves
        )
        .id
    val height = miner.transactionStatus(assetId).height.get

    miner.putData(
      issuer,
      List[DataEntry[?]](
        IntegerDataEntry("int", 100500L),
        StringDataEntry("str", "text"),
        BinaryDataEntry("bin", ByteStr(Base16.decode("62696e617279"))),
        BooleanDataEntry("bool", true)
      ),
      1.waves,
      waitForTx = true
    )

    miner.setScript(issuer, Some(failDApp), 1.waves, waitForTx = true)

    val settings = NodeConnectionSettings(miner.nodeApiEndpoint.toString, 'I'.toByte, issuer.toAddress.toString)
    val repl     = Repl(Some(settings))

    await(repl.execute(""" this.getInteger("int") """)) shouldBe Right("res1: Int|Unit = 100500")
    await(repl.execute(""" this.getString("str") """)) shouldBe Right("""res2: String|Unit = "text"""")
    await(repl.execute(""" this.getBinary("bin") """)) shouldBe Right("res3: ByteVector|Unit = base16'r1Mw3j9J'")
    await(repl.execute(""" this.getBoolean("bool") """)) shouldBe Right("res4: Boolean|Unit = true")

    await(repl.execute(""" height """)).explicitGet() should fullyMatch regex "res5: Int = \\d+".r

    await(repl.execute(s""" transferTransactionById(base16'${trans.id}') """)).explicitGet() should fullyMatch regex
      s"""
          |res6: TransferTransaction\\|Unit = TransferTransaction\\(
          |	recipient = Address\\(
          |		bytes = base16'${issuer.toAddress}'
          |	\\)
          |	timestamp = ${trans.timestamp}
          |	bodyBytes = base16'[0-9a-f]+'
          |	assetId = Unit
          |	feeAssetId = Unit
          |	amount = ${trans.amount.get}
          |	version = ${trans.version.get}
          |	id = base16'${trans.id}'
          |	senderPublicKey = base16'[0-9a-f]+'
          |	attachment = base16''
          |	sender = Address\\(
          |		bytes = base16'${trans.sender.get}'
          |	\\)
          |	proofs = \\[base16'[0-9a-f]+', base16'', base16'', base16'', base16'', base16'', base16'', base16''\\]
          |	fee = ${trans.fee}
          |\\)
        """.trim.stripMargin

    await(repl.execute(s""" transactionHeightById(base16'$assetId') """)) shouldBe
      Right(s"res7: Int|Unit = $height")

    await(repl.execute(s""" assetInfo(base16'$assetId') """)) shouldBe
      Right(
        s"""
          |res8: Asset|Unit = Asset(
          |	description = "description"
          |	issuer = Address(
          |		bytes = base16'${issuer.toAddress}'
          |	)
          |	scripted = true
          |	issuerPublicKey = base16'${issuer.publicKey}'
          |	minSponsoredFee = Unit
          |	id = base16'$assetId'
          |	decimals = 1
          |	reissuable = true
          |	name = "asset"
          |	quantity = 1000
          |)
        """.trim.stripMargin
      )

    await(repl.execute(s""" blockInfoByHeight($height) """)).explicitGet() should fullyMatch regex
      s"""
          |res9: BlockInfo\\|Unit = BlockInfo\\(
          |	baseTarget = \\d+
          |	generator = Address\\(
          |		bytes = base16'[0-9a-f]+${"" /*miner.address*/}'
          |	\\)
          |	timestamp = \\d+
          |	vrf = base16'[0-9a-f]+'
          |	generationSignature = base16'[0-9a-f]+'
          |	generatorPublicKey = base16'[0-9a-f]+${"" /*miner.publicKey*/}'
          |	height = $height
          |	rewards = \\[\\]
          |\\)
        """.trim.stripMargin

    await(
      repl.execute(
        s""" addressFromRecipient(Alias("aaaa")) ==
          addressFromRecipient(Address(base16'${miner.address}'))
      """
      )
    ) shouldBe
      Right("res10: Boolean = true")

    await(
      repl.execute(
        s""" assetBalance(
            Address(base16'${issuer.toAddress}'),
            base16'$assetId'
          )
       """
      )
    ).explicitGet() shouldBe "res11: Int = 1000"

    await(repl.execute(s""" wavesBalance(Address(base16'${sample.toAddress}')).regular """)) shouldBe Right(s"res12: Int = ${100.waves}")
    await(repl.execute(""" this.wavesBalance() """))
      .explicitGet() should fullyMatch regex "res13: BalanceDetails = BalanceDetails\\(\\s+available = \\d+\\s+regular = \\d+\\s+generating = \\d+\\s+effective = \\d+\\s+\\)".r

    /* It function removed from node API. Wait native protobufs implementation. */
//    await(repl.execute(s""" transferTransactionFromProto(base16'3nec5yth17jNrNgA7dfbbmzJTKysfVyrbkAH5A8w8ncBtWYGgfxEn5hGMnNKQyacgGxuoT9DQdbufGBybzPEpR4SFSbM2o1rxgLUtocDdzLWdbSAUKKHM7f2fsCDqEExkGF2f7Se6Tfi44y3yuNMTYAKrfShEBrKGzCgbEaJtLoZo4bPdnX5V6K2eWCBFnmFjUjA947TckxnNGboh7CL6') """)) shouldBe Right(
//      s"""|res15: TransferTransaction|Unit = TransferTransaction(
//          |	recipient = Address(
//          |		bytes = base16'3HdNRU6DwZBy3ZYAmNEkncQFJFCN5DCY1FQ'
//          |	)
//          |	timestamp = 15872737
//          |	bodyBytes = base16'VgZFeoUbnDNf9w4VBwyTUPxNvhPXJwZGnqinAeszLjngHW3MGWU1y2PemPTfVvtvzvGmGieCjNqpCkVspycSPdbpVLX9CkxzdZ6HR1MxoMNWamXHESqhmy'
//          |	assetId = Unit
//          |	feeAssetId = Unit
//          |	amount = 27603095
//          |	version = 1
//          |	id = base16'EmfqfvR3CcaSitJ5AoZdrKs6AAEcWeivNi3aUT9YZaXG'
//          |	senderPublicKey = base16'CgQJiVQ73HQRgVZErv1Uri5n6ZGKSbvrXaRgsMhj8LN6'
//          |	attachment = base16''
//          |	sender = Address(
//          |		bytes = base16'3HiHQ7gWXJZuLCtBStKjjgB8J8ZkixPuGuN'
//          |	)
//          |	proofs = [base16'5op9X8DV9c5tBmDnwZo7baGqTo2dqdH5oxvS5WL4EBJKPJKLsCA2c3mvMHjmSFwf3Yf1VLoCiT2TbicV5vr5kBft', base16'', base16'', base16'', base16'', base16'', base16'', base16'']
//          |	fee = 87195628
//          |)""".stripMargin)
  }
}
