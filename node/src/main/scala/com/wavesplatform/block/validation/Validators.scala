package com.wavesplatform.block.validation

import cats.syntax.either.*
import com.wavesplatform.block.Block.{GenerationVRFSignatureLength, MaxFeaturesInBlock}
import com.wavesplatform.block.{Block, MicroBlock}
import com.wavesplatform.crypto
import com.wavesplatform.crypto.{DigestLength, KeyLength}
import com.wavesplatform.mining.Miner.MaxTransactionsPerMicroblock
import com.wavesplatform.transaction.TxValidationError.GenericError

object Validators {
  type Validation[A] = Either[GenericError, A]

  def validateBlock(b: Block): Validation[Block] =
    (for {
      _ <- Either.raiseUnless(Block.validateReferenceLength(b.header.reference.arr.length))("Incorrect reference length")
      _ <- Either.raiseUnless(b.header.generationSignature.arr.length == GenerationVRFSignatureLength)("Incorrect generationSignature")
      _ <- Either.raiseUnless(b.header.generator.arr.length == KeyLength)("Incorrect signer")
      _ <- Either.raiseUnless(b.header.featureVotes.distinct.size == b.header.featureVotes.size)(s"Duplicates in feature votes")
      _ <- Either.raiseUnless(b.header.featureVotes.sorted == b.header.featureVotes)(s"Unsorted feature votes")
      _ <- Either.raiseUnless(b.header.featureVotes.size <= MaxFeaturesInBlock)(
        s"Block could not contain more than $MaxFeaturesInBlock feature votes"
      )
      _ <- Either.raiseUnless(b.header.stateHash.forall(_.size == DigestLength))("Incorrect block state hash")
    } yield b).leftMap(GenericError(_))

  def validateGenesisBlock(block: Block): Validation[Block] =
    for {
      // Common validation
      _ <- validateBlock(block)
      // Verify signature
      _ <- Either.raiseUnless(crypto.verify(block.signature, block.bodyBytes(), block.header.generator))(
        GenericError("Passed genesis signature is not valid")
      )
      // The genesis block carries a predefined snapshot instead of transactions
      _ <- Either.raiseUnless(block.transactionData.isEmpty)(GenericError("Genesis block must not contain transactions"))
    } yield block

  def validateMicroBlock(mb: MicroBlock): Validation[MicroBlock] =
    (for {
      _ <- Either.raiseUnless(MicroBlock.validateReferenceLength(mb.reference.arr.length))(s"Incorrect reference length: ${mb.reference.arr.length}")
      _ <- Either.raiseUnless(mb.wholeBlockSignature.arr.length == crypto.SignatureLength)(
        s"Incorrect totalResBlockSig: ${mb.wholeBlockSignature.arr.length}"
      )
      _ <- Either.raiseUnless(mb.sender.arr.length == KeyLength)(s"Incorrect generator.publicKey: ${mb.sender.arr.length}")
      _ <- Either.raiseUnless(mb.transactionData.nonEmpty)("cannot create empty MicroBlock")
      _ <- Either.raiseUnless(mb.transactionData.size <= MaxTransactionsPerMicroblock)(
        s"too many txs in MicroBlock: allowed: $MaxTransactionsPerMicroblock, actual: ${mb.transactionData.size}"
      )
    } yield mb).leftMap(GenericError(_))
}
