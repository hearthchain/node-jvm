package com.wavesplatform.block

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.protobuf.block.PBMicroBlocks
import com.wavesplatform.protobuf.utils.PBUtils
import com.wavesplatform.state.*
import com.wavesplatform.transaction.*
import monix.eval.Coeval
import tech.hearth.crypto.SigningKey

case class MicroBlock(
    version: Byte,
    sender: PublicKey,
    transactionData: Seq[Transaction],
    reference: BlockId,
    totalResBlockSig: ByteStr,
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
      version: Byte,
      generator: SigningKey,
      transactionData: Seq[Transaction],
      reference: BlockId,
      totalResBlockSig: BlockId,
      stateHash: Option[ByteStr],
      finalizationVoting: Option[FinalizationVoting]
  ): Either[ValidationError, MicroBlock] =
    MicroBlock(
      version,
      PublicKey(generator.publicKey),
      transactionData,
      reference,
      totalResBlockSig,
      ByteStr.empty,
      stateHash,
      finalizationVoting
    ).validate
      .map(_.sign(generator))

  def validateReferenceLength(version: Byte, length: Int): Boolean =
    length == Block.referenceLength(version)
}
