package tech.hearth.api.http

import tech.hearth.api.common.CommonGeneratorsApi.GeneratorEntry
import tech.hearth.api.common.{CommonBlocksApi, CommonGeneratorsApi}
import tech.hearth.common.utils.Base16
import tech.hearth.state.{Blockchain, GenerationPeriod, Height, RegisteredEnclave}
import tech.hearth.transaction.Asset
import tech.hearth.utils.byteStrFormat
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.*

case class FinalityApiRoute(blockchain: Blockchain, blocksApi: CommonBlocksApi, generatorsApi: CommonGeneratorsApi) extends ApiRoute {
  import FinalityApiRoute.given

  override def route: Route = pathPrefix("blockchain" / "finality") {
    (get & pathEndOrSingleSlash) {
      complete(finalityInfo)
    } ~ (get & path("binding" / PublicKeySegment / AddrSegment)) { (enclaveKey, client) =>
      blockchain.apiKeyBinding(enclaveKey.byteStr, client) match {
        case Some(envelope) => complete(Json.obj("envelope" -> Base16.encode(envelope.arr)))
        case None           => complete(StatusCodes.NotFound, Json.obj("error" -> s"no api key binding for ($enclaveKey, $client)"))
      }
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

  private def finalityInfo: JsObject = {
    val currentHeight = Height(blockchain.height)
    val currentPeriod = blockchain.generationPeriodOf(currentHeight)
    Json.obj(
      "height"                  -> currentHeight,
      "finalizedHeight"         -> blocksApi.currentFinalizedHeight,
      "currentGenerationPeriod" -> currentPeriod,
      "currentGenerators"       -> generatorsApi.generators(currentHeight),
      "nextGenerationPeriod"    -> currentPeriod.map(_.next),
      "nextGenerators" -> currentPeriod.fold(Seq.empty)(p =>
        generatorsApi
          .generators(p.next.start)
          .map(ge =>
            Json.obj(
              "address"       -> ge.address,
              "transactionId" -> ge.commitTxnId
            )
          )
      ),
      // A StartBoost registration always targets the next period; the current list is what last period registered.
      "currentRegisteredEnclaves" -> currentPeriod.fold(Seq.empty[RegisteredEnclave])(blockchain.registeredEnclaves),
      "nextRegisteredEnclaves"    -> currentPeriod.fold(Seq.empty[RegisteredEnclave])(p => blockchain.registeredEnclaves(p.next))
    )
  }
}

object FinalityApiRoute {
  given Writes[GenerationPeriod] = (gp: GenerationPeriod) =>
    Json.obj(
      "start" -> gp.start,
      "end"   -> gp.end
    )

  given Writes[RegisteredEnclave] = (re: RegisteredEnclave) =>
    Json.obj(
      "enclavePublicKey" -> re.enclavePublicKey,
      "validator"        -> re.validator.toBech32,
      "operator"         -> re.operator.toBech32
    )

  given Writes[GeneratorEntry] = (ge: GeneratorEntry) =>
    Json.obj(
      "address"        -> ge.address.toBech32,
      "transactionId"  -> ge.commitTxnId,
      "balance"        -> ge.balance,
      "conflictHeight" -> ge.conflictHeight
    )
}
