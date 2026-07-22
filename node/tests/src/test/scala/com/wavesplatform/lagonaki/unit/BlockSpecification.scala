package com.wavesplatform.lagonaki.unit

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto
import com.wavesplatform.metrics.Instrumented
import com.wavesplatform.test.*
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.transfer.*
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

class BlockSpecification extends PropSpec {

  val time = System.currentTimeMillis() - 5000

  val blockGen = for {
    baseTarget          <- arbitrary[Long]
    reference           <- byteArrayGen(Block.BlockIdLength).map(r => ByteStr(r))
    generationSignature <- byteArrayGen(Block.GenerationSignatureLength)
    assetBytes          <- byteArrayGen(AssetIdLength)
    assetId = IssuedAsset(ByteStr(assetBytes))
    sender                    <- accountGen
    recipient                 <- accountGen
    paymentTransaction        <- wavesTransferGeneratorP(time, sender, recipient.toAddress)
    transferTrancation        <- transferGeneratorP(1 + time, sender, recipient.toAddress, assetId, Waves)
    anotherPaymentTransaction <- wavesTransferGeneratorP(2 + time, sender, recipient.toAddress)
    transactionData = Seq(paymentTransaction, transferTrancation, anotherPaymentTransaction)
  } yield (baseTarget, reference, ByteStr(generationSignature), recipient, transactionData)

  def bigBlockGen(amt: Int): Gen[Block] =
    for {
      baseTarget                              <- arbitrary[Long]
      reference                               <- byteArrayGen(Block.BlockIdLength).map(r => ByteStr(r))
      generationSignature                     <- byteArrayGen(Block.GenerationSignatureLength)
      sender                                  <- accountGen
      recipient                               <- accountGen
      paymentTransaction: TransferTransaction <- wavesTransferGeneratorP(time, sender, recipient.toAddress)
    } yield Block
      .buildAndSign(
        3.toByte,
        time,
        reference,
        baseTarget,
        ByteStr(generationSignature),
        Seq.fill(amt)(paymentTransaction),
        recipient,
        featureVotes = Seq.empty,
        rewardVote = -1L,
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = None
      )
      .explicitGet()

  property(s"feature flags limit is ${Block.MaxFeaturesInBlock}") {
    val version           = 3.toByte
    val supportedFeatures = (0 to Block.MaxFeaturesInBlock * 2).map(_.toShort)

    forAll(blockGen) { case (baseTarget, reference, generationSignature, recipient, transactionData) =>
      Block.buildAndSign(
        version,
        time,
        reference,
        baseTarget,
        generationSignature,
        transactionData,
        recipient,
        supportedFeatures,
        rewardVote = -1L,
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = None
      ) should produce(s"Block could not contain more than ${Block.MaxFeaturesInBlock} feature votes")
    }
  }

  property("block signed by a weak public key is invalid") {
    val weakAccount = PublicKey(Array.fill(32)(0: Byte))
    forAll(blockGen) { case (baseTarget, reference, generationSignature, _, transactionData) =>
      val block = Block
        .create(
          version = 3.toByte,
          time,
          reference,
          baseTarget,
          generationSignature,
          weakAccount,
          featureVotes = Seq.empty,
          rewardVote = -1L,
          transactionData,
          stateHash = None,
          challengedHeader = None,
          finalizationVoting = None
        )
        .copy(signature = ByteStr(Array.fill(64)(0: Byte)))
      block.signatureValid() shouldBe false
    }
  }

  ignore("sign time for 60k txs") {
    forAll(randomTransactionsGen(60000), accountGen, byteArrayGen(Block.BlockIdLength), byteArrayGen(Block.GenerationSignatureLength)) {
      case (txs, acc, ref, gs) =>
        val (block, _) =
          Instrumented.withTimeMillis(
            Block.buildAndSign(3.toByte, 1, ByteStr(ref), 1, ByteStr(gs), txs, acc, Seq.empty, -1L, None, None, None).explicitGet()
          )
        val (bytes, _) = Instrumented.withTimeMillis(block.bytes().dropRight(crypto.SignatureLength))
        val (hash, _)  = Instrumented.withTimeMillis(crypto.fastHash(bytes))
        Instrumented.withTimeMillis(acc.sign(hash))
    }
  }
}
