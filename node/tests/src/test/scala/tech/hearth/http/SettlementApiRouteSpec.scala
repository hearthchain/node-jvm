package tech.hearth.http

import tech.hearth.api.http.SettlementApiRoute
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.db.WithDomain
import tech.hearth.state.{SnapshotBlockchain, StateSnapshot}
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.{Asset, TxHelpers}
import org.apache.pekko.http.scaladsl.model.StatusCodes.{NotFound, OK}
import play.api.libs.json.*

/** Point reads the settlement flow needs. The binding is injected via SnapshotBlockchain because no test fixture can
  * drive StartBoost to its accept path (see BindApiKeyTransactionDiffTest's doc comment).
  */
class SettlementApiRouteSpec extends RouteSpec("/blockchain") with WithDomain {
  private val enclaveKey = ByteStr.fill(32)(1)
  private val client     = TxHelpers.defaultSigner.toAddress
  private val miner      = TxHelpers.secondSigner.toAddress
  private val envelope   = ByteStr.fill(79)(2)

  private def routeWith(snapshot: StateSnapshot) = (d: tech.hearth.history.Domain) =>
    seal(SettlementApiRoute(SnapshotBlockchain(d.blockchain, snapshot)).route)

  "GET /blockchain/binding/{enclaveKey}/{client}" - {
    "returns the envelope bound to the pair" in withDomain(DeterministicFinality) { d =>
      val route = routeWith(StateSnapshot(apiKeyBindings = Map((enclaveKey, client) -> envelope)))(d)
      Get(routePath(s"/binding/${Base16.encode(enclaveKey.arr)}/${client.toBech32}")) ~> route ~> check {
        status shouldBe OK
        (responseAs[JsObject] \ "envelope").as[String] shouldBe Base16.encode(envelope.arr)
      }
    }

    "404s for a pair with no binding" in withDomain(DeterministicFinality) { d =>
      val route = routeWith(StateSnapshot(apiKeyBindings = Map((enclaveKey, client) -> envelope)))(d)
      Get(routePath(s"/binding/${Base16.encode(enclaveKey.arr)}/${miner.toBech32}")) ~> route ~> check {
        status shouldBe NotFound
        (responseAs[JsObject] \ "error").as[Int] shouldBe 407
      }
    }
  }

  "GET /blockchain/settlement/{client}/{miner}" - {
    "reports the reserved and settled counters" in withDomain(DeterministicFinality) { d =>
      val snapshot = StateSnapshot(
        reservedAmounts = Map((client, miner, Asset.Hearth) -> 800000L),
        settledAmounts = Map((client, miner, Asset.Hearth) -> 240000L)
      )
      Get(routePath(s"/settlement/${client.toBech32}/${miner.toBech32}")) ~> routeWith(snapshot)(d) ~> check {
        status shouldBe OK
        responseAs[JsObject] shouldBe Json.obj("reserved" -> 800000, "settled" -> 240000)
      }
    }

    "reports zeros for a pair that never reserved" in withDomain(DeterministicFinality) { d =>
      Get(routePath(s"/settlement/${miner.toBech32}/${client.toBech32}")) ~> routeWith(StateSnapshot())(d) ~> check {
        status shouldBe OK
        responseAs[JsObject] shouldBe Json.obj("reserved" -> 0, "settled" -> 0)
      }
    }
  }
}
