package com.wavesplatform.block.validation

import cats.syntax.either.*
import com.wavesplatform.block.Block.{GenerationSignatureLength, GenerationVRFSignatureLength, MaxFeaturesInBlock, ProtoBlockVersion}
import com.wavesplatform.block.{Block, MicroBlock}
import com.wavesplatform.crypto
import com.wavesplatform.crypto.{DigestLength, KeyLength}
import com.wavesplatform.mining.Miner.MaxTransactionsPerMicroblock
import com.wavesplatform.transaction.TxValidationError.GenericError

object Validators {
  type Validation[A] = Either[GenericError, A]

  def validateBlock(b: Block): Validation[Block] =
    (for {
      _ <- Either.cond(Block.validateReferenceLength(b.header.reference.arr.length), (), "Incorrect reference")
      genSigLength = if (b.header.version < ProtoBlockVersion) GenerationSignatureLength else GenerationVRFSignatureLength
      _ <- Either.cond(b.header.generationSignature.arr.length == genSigLength, (), "Incorrect generationSignature")
      _ <- Either.cond(b.header.generator.arr.length == KeyLength, (), "Incorrect signer")
      _ <- Either.cond(
        b.header.version > 2 || b.header.featureVotes.isEmpty,
        (),
        s"Block version ${b.header.version} could not contain feature votes"
      )
      _ <- Either.cond(b.header.featureVotes.distinct.size == b.header.featureVotes.size, (), s"Duplicates in feature votes")
      _ <- Either.cond(b.header.version < ProtoBlockVersion || b.header.featureVotes.sorted == b.header.featureVotes, (), s"Unsorted feature votes")
      _ <- Either.cond(b.header.featureVotes.size <= MaxFeaturesInBlock, (), s"Block could not contain more than $MaxFeaturesInBlock feature votes")
      _ <- Either.cond(b.header.stateHash.forall(_.size == DigestLength), (), "Incorrect block state hash")
    } yield b).leftMap(GenericError(_))

  def validateGenesisBlock(block: Block): Validation[Block] =
    for {
      // Common validation
      _ <- validateBlock(block)
      // Verify signature
      _ <- Either.cond(
        crypto.verify(block.signature, block.bodyBytes(), block.header.generator),
        (),
        GenericError("Passed genesis signature is not valid")
      )
      // The genesis block carries a predefined snapshot instead of transactions
      _ <- Either.cond(block.transactionData.isEmpty, (), GenericError("Genesis block must not contain transactions"))
    } yield block

  def validateMicroBlock(mb: MicroBlock): Validation[MicroBlock] =
    (for {
      _ <- Either.cond(
        MicroBlock.validateReferenceLength(mb.version, mb.reference.arr.length),
        (),
        s"Incorrect prevResBlockSig: ${mb.reference.arr.length}"
      )
      _ <- Either.cond(
        mb.totalResBlockSig.arr.length == crypto.SignatureLength,
        (),
        s"Incorrect totalResBlockSig: ${mb.totalResBlockSig.arr.length}"
      )
      _ <- Either.cond(mb.sender.arr.length == KeyLength, (), s"Incorrect generator.publicKey: ${mb.sender.arr.length}")
      _ <- Either.cond(mb.transactionData.nonEmpty, (), "cannot create empty MicroBlock")
      _ <- Either.cond(
        mb.transactionData.size <= MaxTransactionsPerMicroblock,
        (),
        s"too many txs in MicroBlock: allowed: $MaxTransactionsPerMicroblock, actual: ${mb.transactionData.size}"
      )
    } yield mb).leftMap(GenericError(_))
}
