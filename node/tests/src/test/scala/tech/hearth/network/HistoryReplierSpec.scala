package tech.hearth.network

import tech.hearth.block.MicroBlock
import tech.hearth.common.state.ByteStr
import tech.hearth.history.{DefaultHearthSettings, History}
import tech.hearth.protobuf.snapshot.TransactionStateSnapshot
import tech.hearth.test.FreeSpec
import io.netty.channel.embedded.EmbeddedChannel

import scala.concurrent.ExecutionContext

class HistoryReplierSpec extends FreeSpec {
  private object EmptyHistory extends History {
    override def loadBlockBytes(id: ByteStr): Option[Array[Byte]]                            = None
    override def loadMicroBlock(id: ByteStr): Option[MicroBlock]                             = None
    override def blockIdsAfter(candidates: Seq[ByteStr], count: Int): Seq[ByteStr]           = Seq.empty
    override def loadBlockSnapshots(id: ByteStr): Option[Seq[TransactionStateSnapshot]]      = None
    override def loadMicroBlockSnapshots(id: ByteStr): Option[Seq[TransactionStateSnapshot]] = None
  }

  // Silence would leave the requester to time out and blacklist us for a block that simply no longer exists.
  "answers a request for a block it does not have with BlockNotFound" in {
    val ch = new EmbeddedChannel(new HistoryReplier(0, EmptyHistory, DefaultHearthSettings.synchronizationSettings)(using ExecutionContext.parasitic))
    val id = ByteStr(bytes32gen.sample.get)

    ch.writeInbound(GetBlock(id))

    ch.readOutbound[BlockNotFound]() shouldBe BlockNotFound(id)
  }
}
