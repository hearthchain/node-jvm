package tech.hearth.http

import org.apache.pekko.http.scaladsl.model.StatusCodes.{BadRequest, NotFound, OK}
import play.api.libs.json.*
import tech.hearth.account.Address
import tech.hearth.api.common.CommonStakingApi
import tech.hearth.api.http.StakingApiRoute
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.state.{AddressPayout, GenerationPeriod, Height, PeriodPayout, StakerPayout}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers

/** The JSON shape and the period handling of the staking routes. What CommonStakingApi returns is
  * CommonStakingApiTest's, so it is stubbed here.
  */
class StakingApiRouteSpec extends RouteSpec("/blockchain/staking") with WithDomain {
  private val PeriodLength = 4
  private val second       = GenerationPeriod(Height(5), PeriodLength)

  private val alice     = TxHelpers.signer(11)
  private val bob       = TxHelpers.signer(12).toAddress
  private val credAsset = IssuedAsset(ByteStr.fill(32)(3))

  private val payout = PeriodPayout(second, credAsset, 400L, BigInt(101L), Seq(StakerPayout(alice.toAddress, 100L, 396L), StakerPayout(bob, 1L, 3L)))

  private val stubApi = new CommonStakingApi {
    override def payout(period: GenerationPeriod): Option[PeriodPayout] = Option.when(period == second)(StakingApiRouteSpec.this.payout)
    override def payouts(address: Address, from: Height, to: Height): Seq[AddressPayout] =
      Seq(AddressPayout(second, credAsset, 100L, 396L)).filter(p => address == alice.toAddress && p.period.start >= from && p.period.start <= to)
  }

  private def withStakingDomain[A](f: Domain => A): A =
    withDomain(
      DeterministicFinality.configure(_.copy(generationPeriodLength = PeriodLength)),
      AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, alice)
    )(f)

  private def route(d: Domain) = seal(StakingApiRoute(d.blockchain, stubApi).route)

  "GET /blockchain/staking/payouts/{periodStart}" - {
    "describes what the period paid" in withStakingDomain { d =>
      Get(routePath("/payouts/5")) ~> route(d) ~> check {
        status shouldBe OK
        responseAs[JsObject] shouldBe Json.obj(
          "periodStart"  -> 5,
          "periodEnd"    -> 8,
          "paidAtHeight" -> 9,
          "assetId"      -> credAsset.id.toString,
          "issued"       -> 400,
          "totalStaked"  -> 101,
          "credited"     -> 399,
          "payouts" -> Json.arr(
            Json.obj("address" -> alice.toAddress.toBech32, "staked" -> 100, "credited" -> 396),
            Json.obj("address" -> bob.toBech32, "staked"             -> 1, "credited"   -> 3)
          )
        )
      }
    }

    "404s for a period that paid nothing" in withStakingDomain { d =>
      Get(routePath("/payouts/9")) ~> route(d) ~> check {
        status shouldBe NotFound
        (responseAs[JsObject] \ "error").as[Int] shouldBe 408
      }
    }

    "rejects a height that does not start a period" in withStakingDomain { d =>
      Get(routePath("/payouts/6")) ~> route(d) ~> check {
        status shouldBe BadRequest
        (responseAs[JsObject] \ "message").as[String] should include("6 is not the start of a generation period")
      }
    }
  }

  "GET /blockchain/staking/payouts/address/{address}" - {
    "lists what the address was paid" in withStakingDomain { d =>
      Get(routePath(s"/payouts/address/${alice.toAddress.toBech32}")) ~> route(d) ~> check {
        status shouldBe OK
        responseAs[JsArray] shouldBe Json.arr(
          Json.obj(
            "periodStart"  -> 5,
            "periodEnd"    -> 8,
            "paidAtHeight" -> 9,
            "assetId"      -> credAsset.id.toString,
            "staked"       -> 100,
            "credited"     -> 396
          )
        )
      }
    }

    "narrows the list to the periods starting within from and to" in withStakingDomain { d =>
      Get(routePath(s"/payouts/address/${alice.toAddress.toBech32}?from=6&to=100")) ~> route(d) ~> check {
        status shouldBe OK
        responseAs[JsArray] shouldBe Json.arr()
      }
    }
  }

  "GET /blockchain/staking/{address}" - {
    "reports the stake in force now, the one in force next period, and what is locked" in withStakingDomain { d =>
      d.appendBlock(TxHelpers.stake(alice, periodStart = second.start, amount = 100.hearth))

      Get(routePath(s"/${alice.toAddress.toBech32}")) ~> route(d) ~> check {
        status shouldBe OK
        responseAs[JsObject] shouldBe Json.obj("address" -> alice.toAddress.toBech32, "current" -> 0, "next" -> 100.hearth, "locked" -> 100.hearth)
      }
    }
  }
}
