package tech.hearth.it.api

import tech.hearth.it.Node
import tech.hearth.network.client.NetworkSender

import scala.concurrent.Future

object AsyncNetworkApi {
  implicit class NodeAsyncNetworkApi(node: Node) {
    import scala.concurrent.ExecutionContext.Implicits.global

    def nonce: Long = System.currentTimeMillis()

    def sendByNetwork(messages: Any*): Future[Unit] = {
      val sender = new NetworkSender(
        node.settings.networkSettings.trafficLogger,
        node.settings.blockchainSettings.networkId,
        s"it-client-to-${node.name}",
        nonce
      )
      sender.connect(node.networkAddressAccessibleFromHost).flatMap { ch =>
        if (ch.isActive) sender.send(ch, messages*).map(_ => sender.close())
        else {
          sender.close()
          Future.failed(new Exception("Channel is inactive"))
        }
      }
    }
  }
}
