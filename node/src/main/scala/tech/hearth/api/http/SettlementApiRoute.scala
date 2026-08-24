package tech.hearth.api.http

import tech.hearth.common.utils.Base16
import tech.hearth.state.Blockchain
import tech.hearth.transaction.Asset
import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.*

/** Point reads the TEE miner needs to build a settlement batch: the HPKE api-key envelope a client bound to its
  * enclave key (BindApiKey), and the reserved/settled counters for a (client, miner) pair. Kept out of
  * FinalityApiRoute because these read the Reserve/Settle ledger, not finalization state.
  */
case class SettlementApiRoute(blockchain: Blockchain) extends ApiRoute {
  override def route: Route = pathPrefix("blockchain") {
    (get & path("binding" / PublicKeySegment / AddrSegment)) { (enclaveKey, client) =>
      complete(
        blockchain
          .apiKeyBinding(enclaveKey.byteStr, client)
          .map(envelope => Json.obj("envelope" -> Base16.encode(envelope.arr)))
          .toRight(ApiError.ApiKeyBindingDoesNotExist)
      )
    } ~ (get & path("settlement" / AddrSegment / AddrSegment)) { (client, miner) =>
      // Hearth only for now: Reserve/Settle run on the native asset in this repo's flows, and the miner reads these
      // two counters to cap and assert its settlement batches.
      complete(
        Json.obj(
          "reserved" -> blockchain.reservedAmount(client, miner, Asset.Hearth),
          "settled"  -> blockchain.settledAmount(client, miner, Asset.Hearth)
        )
      )
    }
  }
}
