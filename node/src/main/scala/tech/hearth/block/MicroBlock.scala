package tech.hearth.block

import tech.hearth.account.PublicKey
import tech.hearth.block.Block.BlockId
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.lang.ValidationError
import tech.hearth.protobuf.block.PBMicroBlocks
import tech.hearth.protobuf.utils.PBUtils
import tech.hearth.state.*
import tech.hearth.transaction.*
import monix.eval.Coeval
import tech.hearth.crypto.SigningKey

case class MicroBlock(
    sender: PublicKey,
    transactionData: Seq[Transaction],
    reference: BlockId,
    wholeBlockSignature: ByteStr,
    signature: ByteStr,
    stateHash: Option[ByteStr],
    finalizationVoting: Option[FinalizationVoting]
) extends Signed {
  val bytesWithoutSignature: Coeval[Array[Byte]] = Coeval.evalOnce(PBUtils.encodeDeterministic(PBMicroBlocks.protobufUnsigned(this)))

  override val signatureValid: Coeval[Boolean]        = Coeval.evalOnce(crypto.verify(signature, bytesWithoutSignature(), sender))
  override val signedDescendants: Coeval[Seq[Signed]] = Coeval.evalOnce(transactionData.flatMap(_.cast[Signed]))

  override def toString: String = s"MicroBlock(... -> ${reference.trim}, txs=${transactionData.size})"

  def stringRepr(totalBlockId: ByteStr): String =
    s"MicroBlock(${totalBlockId.trim} -> ${reference.trim}, txs=${transactionData.size})"
}

object MicroBlock {
  def buildAndSign(
      generator: SigningKey,
      transactionData: Seq[Transaction],
      reference: BlockId,
      wholeBlockSignature: ByteStr,
      stateHash: Option[ByteStr],
      finalizationVoting: Option[FinalizationVoting]
  ): Either[ValidationError, MicroBlock] =
    MicroBlock(
      PublicKey(generator.publicKey),
      transactionData,
      reference,
      wholeBlockSignature,
      ByteStr.empty,
      stateHash,
      finalizationVoting
    ).validate
      .map(_.sign(generator))

  def validateReferenceLength(length: Int): Boolean = length == Block.ReferenceLength
}
