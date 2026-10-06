package tech.hearth.network

import java.nio.charset.StandardCharsets
import tech.hearth.common.state.ByteStr
import tech.hearth.test.FreeSpec
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.{ProvenTransaction, Transaction}
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel

class MessageCodecSpec extends FreeSpec {

  "should block a sender of invalid messages" in {
    val codec = new SpyingMessageCodec
    val ch    = new EmbeddedChannel(codec)

    ch.writeInbound(RawBytes(PBTransactionSpec.messageCode, "foo".getBytes(StandardCharsets.UTF_8)))
    ch.readInbound[TransferTransaction]()

    codec.blockCalls shouldBe 1
  }

  "should not block a sender of valid messages" in forAll(randomTransactionGen) { (origTx: Transaction & ProvenTransaction) =>
    val codec = new SpyingMessageCodec
    val ch    = new EmbeddedChannel(codec)

    ch.writeInbound(RawBytes.fromTransaction(origTx))
    val decodedTx = ch.readInbound[Transaction]()

    decodedTx shouldBe origTx
    codec.blockCalls shouldBe 0
  }

  "encodes GetBlockIds with the block-id message" in {
    val ch = new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp))

    ch.writeOutbound(GetBlockIds(Seq(ByteStr(bytes32gen.sample.get))))

    ch.readOutbound[RawBytes]().code shouldBe GetBlockIdsSpec.messageCode
  }

  "encodes BlockNotFound as a block message carrying only the id" in {
    val ch = new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp))
    val id = ByteStr(bytes32gen.sample.get)

    ch.writeOutbound(BlockNotFound(id))

    ch.readOutbound[RawBytes]() shouldBe RawBytes(PBBlockSpec.messageCode, id.arr)
  }

  "decodes a block message carrying only an id as BlockNotFound" in {
    val codec = new SpyingMessageCodec
    val ch    = new EmbeddedChannel(codec)
    val id    = ByteStr(bytes32gen.sample.get)

    ch.writeInbound(RawBytes(PBBlockSpec.messageCode, id.arr))

    ch.readInbound[BlockNotFound]() shouldBe BlockNotFound(id)
    codec.blockCalls shouldBe 0
  }

  private class SpyingMessageCodec extends MessageCodec(PeerDatabase.NoOp) {
    var blockCalls = 0

    override def block(ctx: ChannelHandlerContext, e: Throwable): Unit = {
      blockCalls += 1
    }
  }

}
