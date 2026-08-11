package tech.hearth.api.http

import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.MediaTypes.`application/json`
import org.apache.pekko.http.scaladsl.model.headers.Accept
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.http.{ApiErrorMatchers, DummyTransactionPublisher, RestAPISettingsHelper}
import tech.hearth.settings.HearthSettings
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.utils.SharedSchedulerMixin
import org.scalactic.source.Position
import play.api.libs.json.*

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

class CustomJsonMarshallerSpec
    extends PropSpec
    with RestAPISettingsHelper
    with ScalatestRouteTest
    with ApiErrorMatchers
    with ApiMarshallers
    with SharedDomain
    with SharedSchedulerMixin {

  private val numberFormat = Accept(`application/json`.withParams(Map("large-significand-format" -> "string")))
  private val richAccount  = TxHelpers.signer(55)

  override def genesisBalances: Seq[AddrWithBalance] = Seq(AddrWithBalance(richAccount.toAddress, 50000.hearth))
  override def settings: HearthSettings              = DomainPresets.BlockRewardDistribution

  private def ensureFieldsAre[A: ClassTag](v: JsObject, fields: String*)(implicit pos: Position): Unit =
    for (f <- fields) (v \ f).get shouldBe a[A]

  private def checkRoute(req: HttpRequest, route: Route, fields: String*)(implicit pos: Position): Unit = {
    req ~> route ~> check {
      ensureFieldsAre[JsNumber](responseAs[JsObject], fields*)
    }

    req ~> numberFormat ~> route ~> check {
      ensureFieldsAre[JsString](responseAs[JsObject], fields*)
    }
  }

  private val transactionsRoute =
    TransactionsApiRoute(
      restAPISettings,
      domain.transactionsApi,
      domain.wallet,
      domain.generatorKeys,
      domain.blockchain,
      () => domain.blockchain,
      () => domain.utxPool.size,
      DummyTransactionPublisher.accepting,
      ntpTime,
      new RouteTimeout(60.seconds)(using sharedScheduler)
    ).route

  property("/transactions/info/{id}") {
    // todo: add other transaction types
    val leaseTx = TxHelpers.lease(sender = richAccount, TxHelpers.address(80), 25.hearth)
    domain.appendBlock(leaseTx)
    checkRoute(Get(s"/transactions/info/${leaseTx.id()}"), transactionsRoute, "amount")
  }

  property("/transactions/calculateFee") {
    val tx = TxHelpers.transfer(richAccount, TxHelpers.address(81), 5.hearth)
    checkRoute(Post("/transactions/calculateFee", tx.json()), transactionsRoute, "feeAmount")
  }

  private val rewardRoute = RewardApiRoute(domain.blockchain).route

  property("/blockchain/rewards") {
    checkRoute(Get("/blockchain/rewards/2"), rewardRoute, "totalHearthAmount", "currentReward", "cEmit")
  }

  property("/debug/stateHearth") {
    pending // todo: fix when distributions/portfolio become testable
  }

  property("/assets/{assetId}/distribution/{height}/limit/{limit}") {
    pending // todo: fix when distributions/portfolio become testable
  }
}
