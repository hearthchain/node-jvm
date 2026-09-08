package tech.hearth.network

import java.net.InetSocketAddress

import tech.hearth.network.message.{MessageSpec, Message as ScorexMessage}
import tech.hearth.test.FreeSpec
import tech.hearth.crypto
import io.netty.buffer.Unpooled.wrappedBuffer
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.embedded.EmbeddedChannel
import org.scalacheck.Gen

import scala.concurrent.duration.DurationInt

class LegacyFrameCodecSpec extends FreeSpec {

  "should handle one message" in forAll(transferV1Gen) { origTx =>
    val codec = new LegacyFrameCodecL1(PeerDatabase.NoOp, 3.minutes)

    val buff = Unpooled.buffer
    write(buff, origTx, PBTransactionSpec)

    val ch = new EmbeddedChannel(codec)
    ch.writeInbound(buff)

    val decodedBytes = ch.readInbound[RawBytes]()

    decodedBytes.code shouldBe PBTransactionSpec.messageCode
    decodedBytes.data shouldEqual origTx.bytes()
  }

  "should handle a message per frame" in forAll(Gen.nonEmptyListOf(transferV1Gen)) { origTxs =>
    val codec = new LegacyFrameCodecL1(PeerDatabase.NoOp, 3.minutes)

    val ch = new EmbeddedChannel(codec)
    origTxs.foreach { tx =>
      val buff = Unpooled.buffer
      write(buff, tx, PBTransactionSpec)
      ch.writeInbound(buff)
    }

    val decoded = (1 to origTxs.size).map { _ =>
      ch.readInbound[RawBytes]()
    }

    val decodedTxs = decoded.map { x =>
      PBTransactionSpec.deserializeData(x.data).get
    }

    decodedTxs shouldEqual origTxs
  }

  "should reject an already received transaction" in {
    val tx    = transferV1Gen.sample.getOrElse(throw new RuntimeException("Can't generate a sample transaction"))
    val codec = new LegacyFrameCodecL1(PeerDatabase.NoOp, 3.minutes)
    val ch    = new EmbeddedChannel(codec)

    val buff1 = Unpooled.buffer
    write(buff1, tx, PBTransactionSpec)
    ch.writeInbound(buff1)

    val buff2 = Unpooled.buffer
    write(buff2, tx, PBTransactionSpec)
    ch.writeInbound(buff2)

    ch.inboundMessages().size() shouldEqual 1
  }

  "should not reject an already received GetPeers" in {
    val msg   = KnownPeers(Seq(InetSocketAddress.createUnresolved("127.0.0.1", 80)))
    val codec = new LegacyFrameCodecL1(PeerDatabase.NoOp, 3.minutes)
    val ch    = new EmbeddedChannel(codec)

    val buff1 = Unpooled.buffer
    write(buff1, msg, PeersSpec)
    ch.writeInbound(buff1)

    val buff2 = Unpooled.buffer
    write(buff2, msg, PeersSpec)
    ch.writeInbound(buff2)

    ch.inboundMessages().size() shouldEqual 2
  }

  "should frame a message as code, checksum and data" in {
    val msg   = KnownPeers(Seq(InetSocketAddress.createUnresolved("127.0.0.1", 80)))
    val bytes = PeersSpec.serializeData(msg)
    val ch    = new EmbeddedChannel(new LegacyFrameCodecL1(PeerDatabase.NoOp, 3.minutes))

    ch.writeOutbound(RawBytes(PeersSpec.messageCode, bytes))
    val framed = ch.readOutbound[ByteBuf]()

    framed.readableBytes() shouldBe 1 + ScorexMessage.ChecksumLength + bytes.length
    framed.readByte() shouldBe PeersSpec.messageCode
    framed.release()
  }

  private def write[T <: AnyRef](buff: ByteBuf, msg: T, spec: MessageSpec[T]): Unit = {
    val bytes    = spec.serializeData(msg)
    val checkSum = wrappedBuffer(crypto.fastHash(bytes), 0, ScorexMessage.ChecksumLength)

    buff.writeByte(spec.messageCode)
    buff.writeBytes(checkSum)
    buff.writeBytes(bytes)
  }

}
