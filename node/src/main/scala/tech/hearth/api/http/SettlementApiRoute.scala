package tech.hearth.api.http

import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.*
import tech.hearth.state.Blockchain
import tech.hearth.utils.byteStrFormat

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
          .map(envelope => Json.obj("envelope" -> byteStrFormat.writes(envelope)))
          .toRight(ApiError.ApiKeyBindingDoesNotExist)
      )
    } ~ (get & path("settlement" / AddrSegment / AddrSegment / AssetId)) { (client, miner, assetId) =>
      complete(
        Json.obj(
          "reserved" -> blockchain.reservedAmount(client, miner, assetId),
          "settled"  -> blockchain.settledAmount(client, miner, assetId)
        )
      )
    }
  }
}
