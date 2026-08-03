package tech.hearth.lagonaki.unit

import tech.hearth.account.PublicKey
import tech.hearth.block.Block
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto
import tech.hearth.metrics.Instrumented
import tech.hearth.test.*
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.transfer.*
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

class BlockSpecification extends PropSpec {

  val time = System.currentTimeMillis() - 5000

  val blockGen = for {
    baseTarget          <- arbitrary[Long]
    reference           <- byteArrayGen(Block.BlockIdLength).map(r => ByteStr(r))
    generationSignature <- byteArrayGen(Block.GenerationVRFSignatureLength)
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
      generationSignature                     <- byteArrayGen(Block.GenerationVRFSignatureLength)
      sender                                  <- accountGen
      recipient                               <- accountGen
      paymentTransaction: TransferTransaction <- wavesTransferGeneratorP(time, sender, recipient.toAddress)
    } yield Block
      .buildAndSign(
        time,
        reference,
        baseTarget,
        ByteStr(generationSignature),
        Seq.fill(amt)(paymentTransaction),
        recipient,
        featureVotes = Seq.empty,
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = None
      )
      .explicitGet()

  property(s"feature flags limit is ${Block.MaxFeaturesInBlock}") {
    val supportedFeatures = (0 to Block.MaxFeaturesInBlock * 2).map(_.toShort)

    forAll(blockGen) { case (baseTarget, reference, generationSignature, recipient, transactionData) =>
      Block.buildAndSign(
        time,
        reference,
        baseTarget,
        generationSignature,
        transactionData,
        recipient,
        supportedFeatures,
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
          time,
          reference,
          baseTarget,
          generationSignature,
          weakAccount,
          featureVotes = Seq.empty,
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
    forAll(randomTransactionsGen(60000), accountGen, byteArrayGen(Block.BlockIdLength), byteArrayGen(Block.GenerationVRFSignatureLength)) {
      case (txs, acc, ref, gs) =>
        val (block, _) =
          Instrumented.withTimeMillis(
            Block.buildAndSign(1, ByteStr(ref), 1, ByteStr(gs), txs, acc, Seq.empty, None, None, None).explicitGet()
          )
        val (bytes, _) = Instrumented.withTimeMillis(block.bytes().dropRight(crypto.SignatureLength))
        val (hash, _)  = Instrumented.withTimeMillis(crypto.fastHash(bytes))
        Instrumented.withTimeMillis(acc.sign(hash))
    }
  }
}
