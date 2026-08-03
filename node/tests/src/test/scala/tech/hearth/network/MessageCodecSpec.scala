package tech.hearth.network

import java.nio.charset.StandardCharsets
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

  private class SpyingMessageCodec extends MessageCodec(PeerDatabase.NoOp) {
    var blockCalls = 0

    override def block(ctx: ChannelHandlerContext, e: Throwable): Unit = {
      blockCalls += 1
    }
  }

}
