package tech.hearth.network

import com.google.common.cache.CacheBuilder
import tech.hearth.block.Block
import tech.hearth.common.utils.Base64
import tech.hearth.crypto
import tech.hearth.network.BasicMessagesRepo.Spec
import tech.hearth.network.LegacyFrameCodec.MessageRawData
import tech.hearth.network.message.Message.*
import tech.hearth.transaction.Transaction
import tech.hearth.utils.ScorexLogging
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled.*
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec

import java.util
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*
import scala.util.control.NonFatal

/** The pipeline's outer length prefix (see NetworkServer) delivers exactly one message here, so a frame carries its
  * own boundaries: the data length is what is left after the code and the checksum.
  */
abstract class LegacyFrameCodec(peerDatabase: PeerDatabase) extends MessageToMessageCodec[ByteBuf, Any] with ScorexLogging {

  protected def filterBySpecOrChecksum(spec: BasicMessagesRepo.Spec, checkSum: Array[Byte]): Boolean = true
  protected def specsByCodes: Map[Byte, BasicMessagesRepo.Spec]
  protected def messageToRawData(msg: Any): MessageRawData
  protected def rawDataToMessage(rawData: MessageRawData): AnyRef

  override def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]): Unit =
    if (!ctx.isRemoved && ctx.channel().isActive) try {
      val code = in.readByte()
      require(specsByCodes.contains(code), s"Unexpected message code $code")

      val spec = specsByCodes(code)

      if (in.readableBytes() == 0) out.add(rawDataToMessage(MessageRawData(code, Array.emptyByteArray)))
      else {
        val length = in.readableBytes() - ChecksumLength
        require(length > 0, s"${spec.messageName} message is not long enough to carry a checksum")
        require(length <= spec.maxLength, s"${spec.messageName} message length $length exceeds ${spec.maxLength}")

        val declaredChecksum = in.readSlice(ChecksumLength)
        val dataBytes        = new Array[Byte](length)
        in.readBytes(dataBytes)

        val rawChecksum    = crypto.fastHash(dataBytes)
        val actualChecksum = wrappedBuffer(rawChecksum, 0, ChecksumLength)

        require(declaredChecksum.equals(actualChecksum), "invalid checksum")
        actualChecksum.release()

        if (filterBySpecOrChecksum(spec, rawChecksum)) out.add(rawDataToMessage(MessageRawData(code, dataBytes)))
      }
    } catch {
      case NonFatal(e) =>
        log.warn(s"${id(ctx)} Malformed network message", e)
        peerDatabase.blacklistAndClose(ctx.channel(), s"Malformed network message: $e")
    }

  override def encode(ctx: ChannelHandlerContext, msg1: Any, out: util.List[AnyRef]): Unit = {
    val msg  = messageToRawData(msg1)
    val data = msg.data
    val buf  = ctx.alloc().buffer(1 + (if (data.isEmpty) 0 else ChecksumLength + data.length))

    buf.writeByte(msg.code)
    if (data.nonEmpty) {
      buf.writeBytes(crypto.fastHash(data), 0, ChecksumLength)
      buf.writeBytes(data)
    }

    out.add(buf)
  }
}

object LegacyFrameCodec {
  case class MessageRawData(code: Byte, data: Array[Byte])
}

class LegacyFrameCodecL1(peerDatabase: PeerDatabase, receivedTxsCacheTimeout: FiniteDuration) extends LegacyFrameCodec(peerDatabase) {

  // todo: this is highly inefficient
  private val receivedTxsCache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(receivedTxsCacheTimeout.toJava)
    .build[String, Object]()

  protected def specsByCodes: Map[MessageCode, Spec] = BasicMessagesRepo.specsByCodes

  protected override def filterBySpecOrChecksum(spec: BasicMessagesRepo.Spec, checkSum: Array[Byte]): Boolean =
    spec != PBTransactionSpec || {
      val actualChecksumStr = Base64.encode(checkSum)
      if (receivedTxsCache.getIfPresent(actualChecksumStr) == null) {
        receivedTxsCache.put(actualChecksumStr, LegacyFrameCodecL1.dummy)
        true
      } else false
    }

  protected def messageToRawData(msg: Any): MessageRawData = {
    val rawBytes = (msg: @unchecked) match {
      case rb: RawBytes           => rb
      case tx: Transaction        => RawBytes.fromTransaction(tx)
      case block: Block           => RawBytes.fromBlock(block)
      case mb: MicroBlockResponse => RawBytes.fromMicroBlock(mb)
    }
    MessageRawData(rawBytes.code, rawBytes.data)
  }

  protected def rawDataToMessage(rawData: MessageRawData): AnyRef =
    RawBytes(rawData.code, rawData.data)
}

object LegacyFrameCodecL1 {
  private val dummy = new Object()
}
