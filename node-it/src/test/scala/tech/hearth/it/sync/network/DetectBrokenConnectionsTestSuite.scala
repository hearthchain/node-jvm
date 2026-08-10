package tech.hearth.it.sync.network

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.it.BaseFreeSpec
import tech.hearth.it.NodeConfigs.Default
import tech.hearth.it.api.SyncHttpApi.*

import scala.concurrent.duration.*

class DetectBrokenConnectionsTestSuite extends BaseFreeSpec {

  override protected def nodeConfigs: Seq[Config] = {
    val highPriorityConfig = ConfigFactory.parseString("hearth.network.break-idle-connections-timeout = 20s")
    Default.take(2).map(highPriorityConfig.withFallback)
  }

  "disconnect nodes from the network and wait a timeout for detecting of broken connections" in {
    dockerNodes().foreach(docker.disconnectFromNetwork)
    Thread.sleep(30.seconds.toMillis)

    dockerNodes().foreach { node =>
      docker.connectToNetwork(Seq(node))
      node.connectedPeers shouldBe empty
      docker.disconnectFromNetwork(node)
    }

    // To prevent errors in the log
    docker.connectToNetwork(dockerNodes())
  }

}
