package tech.hearth.api.http

import tech.hearth.api.common.CommonGeneratorsApi.GeneratorEntry
import tech.hearth.api.common.{CommonBlocksApi, CommonGeneratorsApi}
import tech.hearth.state.{Blockchain, GenerationPeriod, Height, RegisteredEnclave}
import tech.hearth.utils.byteStrFormat
import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.*

case class FinalityApiRoute(blockchain: Blockchain, blocksApi: CommonBlocksApi, generatorsApi: CommonGeneratorsApi) extends ApiRoute {
  import FinalityApiRoute.given

  override def route: Route = pathPrefix("blockchain" / "finality") {
    (get & pathEndOrSingleSlash) {
      complete(finalityInfo)
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
      // TEE miners registered via StartBoost ride on the generator commitments above, so they are read per period
      // the same way: registrations always target the next period.
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
