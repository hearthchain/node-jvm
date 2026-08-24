package tech.hearth.http

import tech.hearth.api.http.FinalityApiRoute
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.db.WithDomain
import tech.hearth.state.{SnapshotBlockchain, StateSnapshot}
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.{Asset, TxHelpers}
import org.apache.pekko.http.scaladsl.model.StatusCodes.{NotFound, OK}
import play.api.libs.json.*

/** Covers the point reads the settlement flow needs: the api-key envelope for (enclaveKey, client) and the
  * reserved/settled counters for (client, miner). The bindings are injected via SnapshotBlockchain because no test
  * fixture can drive StartBoost to its accept path (see BindApiKeyTransactionDiffTest's doc comment).
  */
class FinalityApiRouteSpec extends RouteSpec("/blockchain/finality") with WithDomain {
  private val enclaveKey = ByteStr.fill(32)(1)
  private val client     = TxHelpers.defaultSigner.toAddress
  private val miner      = TxHelpers.secondSigner.toAddress
  private val envelope   = ByteStr.fill(79)(2)

  "binding and settlement point reads" in withDomain(DeterministicFinality) { d =>
    val snapshot = StateSnapshot(
      apiKeyBindings = Map((enclaveKey, client) -> envelope),
      reservedAmounts = Map((client, miner, Asset.Hearth) -> 800000L),
      settledAmounts = Map((client, miner, Asset.Hearth) -> 240000L)
    )
    val route = seal(FinalityApiRoute(SnapshotBlockchain(d.blockchain, snapshot), d.blocksApi, d.generatorsApi).route)

    Get(s"/blockchain/finality/binding/${Base16.encode(enclaveKey.arr)}/${client.toBech32}") ~> route ~> check {
      status shouldBe OK
      (responseAs[JsObject] \ "envelope").as[String] shouldBe Base16.encode(envelope.arr)
    }
    Get(s"/blockchain/finality/binding/${Base16.encode(enclaveKey.arr)}/${miner.toBech32}") ~> route ~> check {
      status shouldBe NotFound
    }
    Get(s"/blockchain/finality/settlement/${client.toBech32}/${miner.toBech32}") ~> route ~> check {
      status shouldBe OK
      responseAs[JsObject] shouldBe Json.obj("reserved" -> 800000, "settled" -> 240000)
    }
    Get(s"/blockchain/finality/settlement/${miner.toBech32}/${client.toBech32}") ~> route ~> check {
      status shouldBe OK
      responseAs[JsObject] shouldBe Json.obj("reserved" -> 0, "settled" -> 0)
    }
  }
}
