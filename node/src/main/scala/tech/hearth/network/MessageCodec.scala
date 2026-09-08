package tech.hearth.network

import tech.hearth.utils.ScorexLogging
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec

import java.util
import scala.util.{Failure, Success}

@Sharable
class MessageCodec(peerDatabase: PeerDatabase) extends MessageToMessageCodec[RawBytes, Message] with ScorexLogging {

  import BasicMessagesRepo.specsByCodes

  override def encode(ctx: ChannelHandlerContext, msg: Message, out: util.List[AnyRef]): Unit = {
    val encodedMsg = msg match {
      // Have no spec
      case r: RawBytes              => r
      case LocalScoreChanged(score) => RawBytes.from(ScoreSpec, score)
      case BlockForged(b)           => RawBytes.fromBlock(b)

      // With a spec
      case GetPeers                      => RawBytes.from(GetPeersSpec, GetPeers)
      case k: KnownPeers                 => RawBytes.from(PeersSpec, k)
      case g: GetBlock                   => RawBytes.from(GetBlockSpec, g)
      case m: MicroBlockInv              => RawBytes.from(MicroBlockInvSpec, m)
      case m: MicroBlockRequest          => RawBytes.from(MicroBlockRequestSpec, m)
      case g: GetSnapshot                => RawBytes.from(GetSnapsnotSpec, g)
      case m: MicroSnapshotRequest       => RawBytes.from(MicroSnapshotRequestSpec, m)
      case s: BlockSnapshotResponse      => RawBytes.from(BlockSnapshotResponseSpec, s)
      case s: MicroBlockSnapshotResponse => RawBytes.from(MicroBlockSnapshotResponseSpec, s)
      case e: EndorseBlock               => RawBytes.from(EndorseBlockSpec, e)
      case g: GetBlockIds                => RawBytes.from(GetBlockIdsSpec, g)
      case s: BlockIds                   => RawBytes.from(BlockIdsSpec, s)

      case _ =>
        throw new IllegalArgumentException(s"Can't send message $msg to $ctx (unsupported)")
    }

    out.add(encodedMsg)
  }

  override def decode(ctx: ChannelHandlerContext, msg: RawBytes, out: util.List[AnyRef]): Unit = {
    specsByCodes(msg.code).deserializeData(msg.data) match {
      case Success(x) => out.add(x)
      case Failure(e) => block(ctx, e)
    }
  }

  protected def block(ctx: ChannelHandlerContext, e: Throwable): Unit = {
    peerDatabase.blacklistAndClose(ctx.channel(), s"Invalid message. ${e.getMessage}")
  }
}
