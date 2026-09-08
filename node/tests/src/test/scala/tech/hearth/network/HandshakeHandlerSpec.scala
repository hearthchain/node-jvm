package tech.hearth.network

import tech.hearth.test.FreeSpec
import io.netty.channel.Channel
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.group.{ChannelGroup, DefaultChannelGroup}
import io.netty.util.concurrent.GlobalEventExecutor

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

class HandshakeHandlerSpec extends FreeSpec {

  private def handshakeFrom(version: (Int, Int, Int)) =
    Handshake(applicationName = "hrth", applicationVersion = version, nodeName = "peer", nodeNonce = 2, declaredAddress = None)

  /** (whether the peer was accepted, whether the channel is still open) */
  private def handshakeWith(remote: Handshake): (Boolean, Boolean) = {
    val established: ConcurrentHashMap[Channel, PeerInfo] = new ConcurrentHashMap()
    val allChannels: ChannelGroup                         = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    val handler =
      new HandshakeHandler.Server(
        handshakeFrom((1, 0, 0)).copy(nodeName = "local", nodeNonce = 1),
        established,
        new ConcurrentHashMap(),
        PeerDatabase.NoOp,
        allChannels
      )

    val channel = new EmbeddedChannel(new HandshakeTimeoutHandler(30.seconds), handler)
    try {
      channel.writeInbound(remote)
      (!established.isEmpty, channel.isOpen)
    } finally channel.finishAndReleaseAll()
  }

  "accepts a peer of any version of the same application" in {
    handshakeWith(handshakeFrom((2, 0, 0))) shouldBe (true, true)
  }

  "closes the connection to a peer of another application" in {
    handshakeWith(handshakeFrom((1, 0, 0)).copy(applicationName = "waves")) shouldBe (false, false)
  }
}
