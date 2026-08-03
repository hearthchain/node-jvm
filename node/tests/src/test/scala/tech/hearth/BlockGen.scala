package tech.hearth

import tech.hearth.block.Block
import tech.hearth.block.Block.GenerationVRFSignatureLength
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.transaction.Transaction
import org.scalacheck.Gen
import org.scalatest.Suite
import tech.hearth.crypto.{Crypto, SigningKey}

trait BlockGen extends TransactionGen { suite: Suite =>

  import BlockGen.*

  val blockParamGen: Gen[(Seq[Transaction], SigningKey)] = for {
    count        <- Gen.choose(minTransactionsInBlockCount, maxTransactionsInBlockCount)
    transactions <- randomTransactionsGen(count)
    signer       <- accountGen
  } yield (transactions, signer)

  def versionedBlockGen(txs: Seq[Transaction], signer: SigningKey): Gen[Block] =
    byteArrayGen(Block.BlockIdLength).flatMap(ref => versionedBlockGen(ByteStr(ref), txs, signer))

  def versionedBlockGen(reference: ByteStr, txs: Seq[Transaction], signer: SigningKey): Gen[Block] =
    for {
      baseTarget <- Gen.posNum[Long]
      genSig     <- byteArrayGen(GenerationVRFSignatureLength)
      timestamp  <- timestampGen
    } yield Block
      .buildAndSign(
        if (txs.isEmpty) timestamp else txs.map(_.timestamp).max,
        reference,
        baseTarget,
        ByteStr(genSig),
        txs,
        signer,
        featureVotes = Seq.empty,
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = None
      )
      .explicitGet()

  def blockGen(txs: Seq[Transaction], signer: SigningKey): Gen[Block] = versionedBlockGen(txs, signer)

  val randomSignerBlockGen: Gen[Block] = for {
    (transactions, signer) <- blockParamGen
    block                  <- blockGen(transactions, signer)
  } yield block

  val predefinedSignerBlockGen: Gen[Block] = for {
    (transactions, _) <- blockParamGen
    signer            <- Gen.const(predefinedSignerPrivateKey)
    block             <- blockGen(transactions, signer)
  } yield block

  val mixedBlockGen: Gen[Block] = for {
    block <- Gen.oneOf(randomSignerBlockGen, predefinedSignerBlockGen)
  } yield block

  def blocksSeqGen(blockGen: Gen[Block]): Gen[(Int, Int, Seq[Block])] =
    for {
      start      <- Gen.posNum[Int].label("from")
      end        <- Gen.chooseNum(start, start + 20).label("to")
      blockCount <- Gen.choose(0, end - start + 1).label("actualBlockCount")
      blocks     <- Gen.listOfN(blockCount, blockGen).label("blocks")
    } yield (start, end, blocks)

  val randomBlocksSeqGen: Gen[(Int, Int, Seq[Block])] = blocksSeqGen(randomSignerBlockGen)

  val mixedBlocksSeqGen: Gen[(Int, Int, Seq[Block])] = blocksSeqGen(mixedBlockGen)

}

object BlockGen {
  val minTransactionsInBlockCount            = 1
  val maxTransactionsInBlockCount            = 100
  val predefinedSignerPrivateKey: SigningKey = SigningKey.fromSeed(Crypto.defaultBackend().sha256(new Array[Byte](32)))
}
