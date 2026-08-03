package tech.hearth.network

import tech.hearth.account.PublicKey
import tech.hearth.block.Block.BlockId
import tech.hearth.block.{Block, BlockEndorsement, MicroBlock}
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.network.message.MessageSpec
import tech.hearth.protobuf.block.EndorseBlock as PBEndorseBlock
import tech.hearth.protobuf.snapshot.{TransactionStateSnapshot, BlockSnapshot as PBBlockSnapshot, MicroBlockSnapshot as PBMicroBlockSnapshot}
import tech.hearth.protobuf.{toByteStr, toByteString}
import tech.hearth.state.{GeneratorIndex, Height}
import tech.hearth.transaction.{Signed, Transaction}
import monix.eval.Coeval
import tech.hearth.crypto.SigningKey

import java.net.InetSocketAddress
import java.util

sealed trait Message

case object GetPeers extends Message

case class KnownPeers(peers: Seq[InetSocketAddress]) extends Message

case class GetBlockIds(ids: Seq[ByteStr]) extends Message {
  override def toString: String = s"GetSignatures(${formatSignatures(ids)})"
}

case class BlockIds(ids: Seq[ByteStr]) extends Message {
  override def toString: String = s"Signatures(${formatSignatures(ids)})"
}

case class GetBlock(id: ByteStr) extends Message

case class LocalScoreChanged(newLocalScore: BigInt) extends Message

case class RawBytes(code: Byte, data: Array[Byte]) extends Message {
  override def toString: String = s"RawBytes($code, ${data.length} bytes)"

  override def equals(obj: Any): Boolean = obj match {
    case o: RawBytes => o.code == code && util.Arrays.equals(o.data, data)
    case _           => false
  }
}

object RawBytes {
  def fromTransaction(tx: Transaction): RawBytes =
    RawBytes(PBTransactionSpec.messageCode, PBTransactionSpec.serializeData(tx))

  def fromBlock(b: Block): RawBytes =
    RawBytes(PBBlockSpec.messageCode, PBBlockSpec.serializeData(b))

  def fromMicroBlock(mb: MicroBlockResponse): RawBytes =
    RawBytes(PBMicroBlockSpec.messageCode, PBMicroBlockSpec.serializeData(mb))

  def from[T <: AnyRef](spec: MessageSpec[T], message: T): RawBytes = RawBytes(spec.messageCode, spec.serializeData(message))
}

case class BlockForged(block: Block) extends Message

case class MicroBlockRequest(totalBlockSig: ByteStr) extends Message

case class MicroBlockResponse(microblock: MicroBlock, totalBlockId: BlockId) extends Message {
  override def toString: String = microblock.stringRepr(totalBlockId)
}

object MicroBlockResponse {
  def apply(mb: MicroBlock): MicroBlockResponse = {
    MicroBlockResponse(mb, mb.wholeBlockSignature)
  }
}

case class MicroBlockInv(sender: PublicKey, totalBlockId: ByteStr, reference: ByteStr, signature: ByteStr) extends Message with Signed {
  override protected val signatureValid: Coeval[Boolean] =
    Coeval.evalOnce(crypto.verify(signature, sender.toAddress.toBytes ++ totalBlockId.arr ++ reference.arr, sender))

  override def toString: String = s"MicroBlockInv(${totalBlockId.trim} ~> ${reference.trim})"
}

object MicroBlockInv {
  def apply(sender: SigningKey, totalBlockRef: ByteStr, prevBlockRef: ByteStr): MicroBlockInv = {
    val signature = sender.sign(sender.toAddress.toBytes ++ totalBlockRef.arr ++ prevBlockRef.arr)
    new MicroBlockInv(PublicKey(sender.publicKey), totalBlockRef, prevBlockRef, ByteStr(signature))
  }
}

case class GetSnapshot(blockId: BlockId) extends Message

case class MicroSnapshotRequest(totalBlockId: BlockId) extends Message

case class BlockSnapshotResponse(blockId: BlockId, snapshots: Seq[TransactionStateSnapshot]) extends Message {
  def toProtobuf: PBBlockSnapshot = PBBlockSnapshot(blockId.toByteString, snapshots)

  override def toString: String = s"BlockSnapshotResponse($blockId, ${snapshots.size} snapshots)"
}

object BlockSnapshotResponse {
  def fromProtobuf(snapshot: PBBlockSnapshot): BlockSnapshotResponse =
    BlockSnapshotResponse(snapshot.blockId.toByteStr, snapshot.snapshots)
}

case class MicroBlockSnapshotResponse(totalBlockId: BlockId, snapshots: Seq[TransactionStateSnapshot]) extends Message {
  def toProtobuf: PBMicroBlockSnapshot =
    PBMicroBlockSnapshot(totalBlockId.toByteString, snapshots)

  override def toString: String = s"MicroBlockSnapshotResponse($totalBlockId, ${snapshots.size} snapshots)"
}

object MicroBlockSnapshotResponse {
  def fromProtobuf(snapshot: PBMicroBlockSnapshot): MicroBlockSnapshotResponse =
    MicroBlockSnapshotResponse(snapshot.totalBlockId.toByteStr, snapshot.snapshots)
}

case class EndorseBlock(endorserIndex: Int, finalizedId: BlockId, finalizedHeight: Height, endorsedId: BlockId, signature: ByteStr) extends Message {
  def toProtobuf: PBEndorseBlock = PBEndorseBlock(
    endorserIndex,
    finalizedId.toByteString,
    finalizedHeight.toInt,
    endorsedId.toByteString,
    signature.toByteString
  )

  override def toString: String = s"EndorseBlock(i=$endorserIndex, f=$finalizedId, fh=$finalizedHeight, e=$endorsedId, s=$signature)"
}

object EndorseBlock {
  def fromProtobuf(x: PBEndorseBlock): EndorseBlock = EndorseBlock(
    x.endorserIndex,
    x.finalizedBlockId.toByteStr,
    Height(x.finalizedBlockHeight),
    x.endorsedBlockId.toByteStr,
    x.signature.toByteStr
  )

  def from(x: BlockEndorsement): EndorseBlock =
    EndorseBlock(x.endorserIndex.toInt, x.finalizedId, x.finalizedHeight, x.endorsedId, x.signature.byteStr)
}
