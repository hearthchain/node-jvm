package com.wavesplatform.lagonaki.unit

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.{Block, BlockEndorsement, FinalizationVoting, MicroBlock}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base64
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.DigestLength
import com.wavesplatform.crypto.bls.{BlsKeyPair, BlsSignature}
import com.wavesplatform.mining.Miner
import com.wavesplatform.protobuf.block.{PBFinalizationVotings, PBMicroBlocks, SignedMicroBlock}
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions}
import com.wavesplatform.protobuf.utils.PBUtils
import com.wavesplatform.state.{GeneratorIndex, GenesisBlockHeight, Height}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.transfer.*
import tech.hearth.crypto.{Crypto, SigningKey}

import scala.util.Random

class MicroBlockSpecification extends FunSuite {

  private val prevResBlockSig  = ByteStr(Array.fill(Block.BlockIdLength)(Random.nextInt(100).toByte))
  private val totalResBlockSig = ByteStr(Array.fill(Block.BlockIdLength)(Random.nextInt(100).toByte))
  private val stateHash        = ByteStr.fill(DigestLength)(Random.nextInt(100).toByte)
  private val reference        = Array.fill(Block.BlockIdLength)(Random.nextInt(100).toByte)
  private val sender           = SigningKey.fromSeed(Crypto.defaultBackend().sha256(reference.dropRight(2)))
  private val gen              = SigningKey.fromSeed(Crypto.defaultBackend().sha256(reference))

  test("MicroBlock with txs bytes/parse roundtrip, with finalizationVoting") {
    val ts = System.currentTimeMillis() - 5000
    val tr: TransferTransaction =
      TxHelpers.transfer(
        from = sender,
        to = gen.toAddress,
        amount = 5,
        asset = Waves,
        fee = 2,
        feeAsset = Waves,
        attachment = ByteStr.empty,
        timestamp = ts + 1,
        version = 1.toByte
      )
    val assetId = IssuedAsset(ByteStr(Array.fill(AssetIdLength)(Random.nextInt(100).toByte)))
    val tr2: TransferTransaction =
      TxHelpers.transfer(
        from = sender,
        to = gen.toAddress,
        amount = 5,
        asset = assetId,
        fee = 2,
        feeAsset = Waves,
        attachment = ByteStr.empty,
        timestamp = ts + 2,
        version = 1.toByte
      )

    val transactions = Seq(tr, tr2)

    val aggregatedEndorsement = Some(BlsSignature(Array.fill(BlsSignature.SizeInBytes)(1.toByte)).explicitGet())

    val finalizedHeight = Height(5)
    val finalizedId     = ByteStr(Array.fill(Block.BlockIdLength)(2.toByte))
    val endorsedId      = ByteStr(Array.fill(Block.BlockIdLength)(3.toByte))
    val blsEndorser     = BlsKeyPair(???)
    val conflictEndorsements: IndexedSeq[BlockEndorsement] =
      IndexedSeq(BlockEndorsement.signed(blsEndorser, GeneratorIndex(7), finalizedId, finalizedHeight, endorsedId))

    val finalizationVoting = Some(
      FinalizationVoting(
        valid = Seq(GeneratorIndex(1), GeneratorIndex(2), GeneratorIndex(3)),
        finalizedHeight = finalizedHeight,
        aggregatedEndorsement = aggregatedEndorsement,
        conflict = conflictEndorsements
      )
    )

    val microBlock =
      MicroBlock.buildAndSign(3.toByte, sender, transactions, prevResBlockSig, totalResBlockSig, Some(stateHash), finalizationVoting).explicitGet()
    val totalBlockId       = ByteStr(Array.fill(Block.BlockIdLength)(1.toByte))
    val signedMicroBlockPb = PBMicroBlocks.protobuf(microBlock, totalBlockId)
    val parsedBlock        = PBMicroBlocks.vanilla(SignedMicroBlock.parseFrom(signedMicroBlockPb.toByteArray)).get.microblock

    assert(microBlock.signaturesValid().isRight)
    assert(parsedBlock.signaturesValid().isRight)

    assert(microBlock.signature == parsedBlock.signature)
    assert(microBlock.sender == parsedBlock.sender)
    assert(microBlock.totalResBlockSig == parsedBlock.totalResBlockSig)
    assert(microBlock.reference == parsedBlock.reference)
    assert(microBlock.transactionData == parsedBlock.transactionData)
    assert(microBlock.stateHash == parsedBlock.stateHash)
    assert(microBlock.finalizationVoting == parsedBlock.finalizationVoting)
    assert(microBlock == parsedBlock)
  }

  test("FinalizationVoting serialization matches Go reference output") {
    def decode(s: String): ByteStr = ByteStr.decodeBase16(s).get

    val referenceId =
      decode("69c5be3f0212b385eb66d0e8ab6112460b263c077c447dee64f29e4c9d1c7738fec9cc9164fe05235d9e296d46d5b6e29f361986721966c53dd1350119d18080")
    val endorsedId =
      decode("d5c2f16adf3a3408a55436b81b0db0f2f91398f3c65363f29f356efaf7775c0f2858c4e2b25ce004975793da9672041ee4fe601e5f02949d7c7bfa951314e783")
    val aggregatedSig = BlsSignature(
      decode(
        "8328e45f6813d989b3a2a4fe80263bce0771789ef949af84b588d0cbaa9f0df20a2e727a4824420bc7ec0fc0bcfb002216677891671c45f5fca64ff53c58148c666d7e61702102791b8a557b1c4351192ef33e1ebd772c7bae2f5765cbc5be8b"
      )
    ).explicitGet()
    val conflictSig = BlsSignature(
      decode(
        "46bd707f14a1508952a4e82a5a366aa87ab185d86fd4f3198629875703aec6dddc80d95aa344ab4825adbbc474ac48a16fb5c87b16b332a249030012fc6ba6c7f2761e8f8463af1180e5077619bf8f558668fc5abf1936753c087aafd118e7d3"
      )
    ).explicitGet()

    val conflictFinalizedHeight = 12345
    val conflictEndorsement = BlockEndorsement(
      endorserIndex = GeneratorIndex(1),
      finalizedId = referenceId,
      finalizedHeight = Height(conflictFinalizedHeight),
      endorsedId = endorsedId,
      signature = conflictSig
    )

    val finalizationVoting = FinalizationVoting(
      valid = Seq(GeneratorIndex(1), GeneratorIndex(2), GeneratorIndex(3)),
      finalizedHeight = GenesisBlockHeight,
      aggregatedEndorsement = Some(aggregatedSig),
      conflict = IndexedSeq(conflictEndorsement)
    )

    val serialized = PBUtils.encodeDeterministic(PBFinalizationVotings.protobuf(finalizationVoting))
    val goFinalizationVotingBase64 =
      "CgMBAgMQARpggyjkX2gT2YmzoqT+gCY7zgdxeJ75Sa+EtYjQy6qfDfIKLnJ6SCRCC8fsD8C8+wAiFmd4kWccRfX8pk/1PFgUjGZtfmFwIQJ5G4pVexxDURku8z4evXcse64vV2XLxb6LIusBCAESQGnFvj8CErOF62bQ6KthEkYLJjwHfER97mTynkydHHc4/snMkWT+BSNdniltRtW24p82GYZyGWbFPdE1ARnRgIAYuWAiQNXC8WrfOjQIpVQ2uBsNsPL5E5jzxlNj8p81bvr3d1wPKFjE4rJc4ASXV5PalnIEHuT+YB5fApSdfHv6lRMU54MqYEa9cH8UoVCJUqToKlo2aqh6sYXYb9TzGYYph1cDrsbd3IDZWqNEq0glrbvEdKxIoW+1yHsWszKiSQMAEvxrpsfydh6PhGOvEYDlB3YZv49Vhmj8Wr8ZNnU8CHqv0Rjn0w=="
    Base64.encode(serialized) shouldBe goFinalizationVotingBase64
  }

  test("MicroBlock serialization matches Go reference output (without signature)") {
    def decode(s: String): ByteStr = ByteStr.decodeBase16(s).get

    val referenceId =
      decode("69c5be3f0212b385eb66d0e8ab6112460b263c077c447dee64f29e4c9d1c7738fec9cc9164fe05235d9e296d46d5b6e29f361986721966c53dd1350119d18080")
    val endorsedId =
      decode("d5c2f16adf3a3408a55436b81b0db0f2f91398f3c65363f29f356efaf7775c0f2858c4e2b25ce004975793da9672041ee4fe601e5f02949d7c7bfa951314e783")
    val aggregatedSig = BlsSignature(
      decode(
        "8328e45f6813d989b3a2a4fe80263bce0771789ef949af84b588d0cbaa9f0df20a2e727a4824420bc7ec0fc0bcfb002216677891671c45f5fca64ff53c58148c666d7e61702102791b8a557b1c4351192ef33e1ebd772c7bae2f5765cbc5be8b"
      )
    ).explicitGet()
    val conflictSig = BlsSignature(
      decode(
        "46bd707f14a1508952a4e82a5a366aa87ab185d86fd4f3198629875703aec6dddc80d95aa344ab4825adbbc474ac48a16fb5c87b16b332a249030012fc6ba6c7f2761e8f8463af1180e5077619bf8f558668fc5abf1936753c087aafd118e7d3"
      )
    ).explicitGet()

    val conflictFinalizedHeight = 12345
    val conflictEndorsement = BlockEndorsement(
      endorserIndex = GeneratorIndex(1),
      finalizedId = referenceId,
      finalizedHeight = Height(conflictFinalizedHeight),
      endorsedId = endorsedId,
      signature = conflictSig
    )

    val finalizationVoting = FinalizationVoting(
      valid = Seq(GeneratorIndex(1), GeneratorIndex(2), GeneratorIndex(3)),
      finalizedHeight = GenesisBlockHeight,
      aggregatedEndorsement = Some(aggregatedSig),
      conflict = IndexedSeq(conflictEndorsement)
    )

    val txBytesBase64 =
      "ClcIVBIg7FlNNgjs8B4KV3mLFwdyeS2xRTKEN3fgrPVEXywc8wQaBBCgjQYgydOsyLgtKAHCBiEKFgoUflp9MfPSElPDgt8e0bJfEbpsP6wSBxCA7oO7rwESQEz8sQx7qThcCFVSdgGm5Dk0VKETkPcJXXJYxnt70rxfsarlD7D4gHB5yTXdDzfndnHAyXH7NwZfzy8YR/CizgY="
    val transaction     = PBTransactions.vanilla(PBSignedTransaction.parseFrom(Base64.decode(txBytesBase64))).explicitGet()
    val senderPublicKey = PublicKey(ByteStr(Base64.decode("xJSp5EjVj+mv4H1T062etqFbqsDYN+7U+sYuhC6feGI=")))
    val referenceSignature =
      decode("69c5be3f0212b385eb66d0e8ab6112460b263c077c447dee64f29e4c9d1c7738fec9cc9164fe05235d9e296d46d5b6e29f361986721966c53dd1350119d18080")
    val totalResSignature =
      decode("9081a55d1cae10c50c846228736ba7e1d6d8904d80d7a7d6e5413b7b4bdec0894e40dfc8d2296a0c5ebe10ffcd0d2d7d2da81edb1e9041312cd333969ce8c28f")

    val microBlock = MicroBlock(
      version = 5.toByte,
      sender = senderPublicKey,
      transactionData = Seq(transaction),
      reference = referenceSignature,
      totalResBlockSig = totalResSignature,
      signature = ByteStr.empty,
      stateHash = None,
      finalizationVoting = Some(finalizationVoting)
    )

    val serializedWithoutSignature = Base64.encode(microBlock.bytesWithoutSignature())
    val goSerializedWithoutSignature =
      "BWnFvj8CErOF62bQ6KthEkYLJjwHfER97mTynkydHHc4/snMkWT+BSNdniltRtW24p82GYZyGWbFPdE1ARnRgICQgaVdHK4QxQyEYihza6fh1tiQTYDXp9blQTt7S97AiU5A38jSKWoMXr4Q/80NLX0tqB7bHpBBMSzTM5ac6MKPAAAAowAAAAEAAACbClcIVBIg7FlNNgjs8B4KV3mLFwdyeS2xRTKEN3fgrPVEXywc8wQaBBCgjQYgydOsyLgtKAHCBiEKFgoUflp9MfPSElPDgt8e0bJfEbpsP6wSBxCA7oO7rwESQEz8sQx7qThcCFVSdgGm5Dk0VKETkPcJXXJYxnt70rxfsarlD7D4gHB5yTXdDzfndnHAyXH7NwZfzy8YR/CizgbElKnkSNWP6a/gfVPTrZ62oVuqwNg37tT6xi6ELp94YgoDAQIDEAEaYIMo5F9oE9mJs6Kk/oAmO84HcXie+UmvhLWI0Muqnw3yCi5yekgkQgvH7A/AvPsAIhZneJFnHEX1/KZP9TxYFIxmbX5hcCECeRuKVXscQ1EZLvM+Hr13LHuuL1dly8W+iyLrAQgBEkBpxb4/AhKzhetm0OirYRJGCyY8B3xEfe5k8p5MnRx3OP7JzJFk/gUjXZ4pbUbVtuKfNhmGchlmxT3RNQEZ0YCAGLlgIkDVwvFq3zo0CKVUNrgbDbDy+ROY88ZTY/KfNW7693dcDyhYxOKyXOAEl1eT2pZyBB7k/mAeXwKUnXx7+pUTFOeDKmBGvXB/FKFQiVKk6CpaNmqoerGF2G/U8xmGKYdXA67G3dyA2VqjRKtIJa27xHSsSKFvtch7FrMyokkDABL8a6bH8nYej4RjrxGA5Qd2Gb+PVYZo/Fq/GTZ1PAh6r9EY59M="

    serializedWithoutSignature shouldBe goSerializedWithoutSignature
  }

  test("MicroBlock cannot be created with zero transactions") {
    val transactions       = Seq.empty[TransferTransaction]
    val eitherBlockOrError = MicroBlock.buildAndSign(3.toByte, sender, transactions, prevResBlockSig, totalResBlockSig, None, None)

    eitherBlockOrError should produce("cannot create empty MicroBlock")
  }

  test("MicroBlock cannot contain more than Miner.MaxTransactionsPerMicroblock") {
    val transaction =
      TxHelpers.transfer(
        from = sender,
        to = gen.toAddress,
        amount = 5,
        asset = Waves,
        fee = 1000,
        feeAsset = Waves,
        attachment = ByteStr.empty,
        timestamp = System.currentTimeMillis(),
        version = 1.toByte
      )
    val transactions = Seq.fill(Miner.MaxTransactionsPerMicroblock + 1)(transaction)

    val eitherBlockOrError = MicroBlock.buildAndSign(3.toByte, sender, transactions, prevResBlockSig, totalResBlockSig, None, None)
    eitherBlockOrError should produce("too many txs in MicroBlock")
  }
}
