package com.wavesplatform

import cats.syntax.either.*
import com.wavesplatform.block.Block.{TransactionProof, TransactionsMerkleTree}
import com.wavesplatform.block.validation.Validators.*
import com.wavesplatform.common.merkle.Merkle.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.protobuf.transaction.PBTransactions
import com.wavesplatform.transaction.Transaction
import tech.hearth.crypto.SigningKey

import scala.util.Try

package object block {

  // Validation
  private[block] implicit class BlockValidationOps(val block: Block) extends AnyVal {
    def validate: Validation[Block]        = validateBlock(block)
    def validateToTry: Try[Block]          = toTry(validateBlock(block))
    def validateGenesis: Validation[Block] = validateGenesisBlock(block)
  }

  private[block] implicit class MicroBlockValidationOps(val microBlock: MicroBlock) extends AnyVal {
    def validate: Validation[MicroBlock] = validateMicroBlock(microBlock)
    def validateToTry: Try[MicroBlock]   = toTry(validateMicroBlock(microBlock))
  }

  private def toTry[A](result: Validation[A]): Try[A] = result.leftMap(ge => new IllegalArgumentException(ge.err)).toTry

  // Sign
  private[block] implicit class BlockSignOps(val block: Block) extends AnyVal {
    def sign(signer: SigningKey): Block = block.copy(signature = ByteStr(signer.sign(block.bodyBytes())))
  }

  private[block] implicit class MicroBlockSignOps(val microBlock: MicroBlock) extends AnyVal {
    def sign(signer: SigningKey): MicroBlock = microBlock.copy(signature = ByteStr(signer.sign(microBlock.bytesWithoutSignature())))
  }

  def transactionProof(transaction: Transaction, transactionData: Seq[Transaction]): Option[TransactionProof] =
    transactionData.indexWhere(transaction.id() == _.id()) match {
      case -1  => None
      case idx => Some(TransactionProof(transaction.id(), idx, mkProofs(idx, mkMerkleTree(transactionData)).reverse))
    }

  implicit class MerkleTreeOps(private val levels: TransactionsMerkleTree) extends AnyVal {
    def transactionsRoot: ByteStr = {
      require(levels.nonEmpty && levels.head.nonEmpty, "Invalid merkle tree")
      ByteStr(levels.head.head)
    }
  }

  def mkMerkleTree(txs: Seq[Transaction]): TransactionsMerkleTree = mkLevels(txs.map(PBTransactions.toByteArrayMerkle))

  def mkTransactionsRoot(version: Byte, transactionData: Seq[Transaction]): ByteStr =
    if (version < Block.ProtoBlockVersion) ByteStr.empty
    else mkMerkleTree(transactionData).transactionsRoot
}
