package tech.hearth.api.http

import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.*
import tech.hearth.api.common.CommonStakingApi
import tech.hearth.state.{AddressPayout, Blockchain, GenerationPeriod, Height, PeriodPayout}

/** Staking payouts: what each finished generation period paid its stakers in Cred. A payout is minted in the first
  * block of the next period with no transaction behind it, so without these it shows up only as an unexplained change
  * of the cred asset balance.
  */
case class StakingApiRoute(blockchain: Blockchain, api: CommonStakingApi) extends ApiRoute {
  override def route: Route = pathPrefix("blockchain" / "staking") {
    (get & path("payouts" / IntNumber)) { periodStart =>
      complete(
        for {
          period <- periodStartingAt(periodStart)
          payout <- api.payout(period).toRight(ApiError.StakingPayoutDoesNotExist)
        } yield StakingApiRoute.payoutJson(payout)
      )
    } ~ (get & path("payouts" / "address" / AddrSegment) & parameters("from".as[Int].?, "to".as[Int].?)) { (address, fromParam, toParam) =>
      val (from, to) = (fromParam.getOrElse(1), toParam.getOrElse(Int.MaxValue))
      // Checked here rather than left to the scan: a negative height sorts after every positive one in a key
      if (from < 1 || from > to) complete(ApiError.CustomValidationError(s"from must be at least 1 and no greater than to, got [$from, $to]"))
      else complete(api.payouts(address, Height(from), Height(to)).map(StakingApiRoute.addressPayoutJson))
    } ~ (get & path(AddrSegment)) { address =>
      val period = blockchain.currentGenerationPeriod
      complete(
        Json.obj(
          "address" -> address.toBech32,
          "current" -> period.fold(0L)(blockchain.stakeAt(address, _)),
          "next"    -> period.fold(0L)(p => blockchain.stakeAt(address, p.next)),
          "locked"  -> blockchain.lockedStake(address)
        )
      )
    }
  }

  private def periodStartingAt(height: Int): Either[ApiError, GenerationPeriod] =
    blockchain
      .generationPeriodOf(Height(height))
      .filter(_.start.toInt == height)
      .toRight(ApiError.CustomValidationError(s"$height is not the start of a generation period"))
}

object StakingApiRoute {
  private def periodJson(period: GenerationPeriod): JsObject = Json.obj(
    "periodStart"  -> period.start.toInt,
    "periodEnd"    -> period.end.toInt,
    "paidAtHeight" -> period.next.start.toInt
  )

  def payoutJson(payout: PeriodPayout): JsObject = periodJson(payout.period) ++ Json.obj(
    "assetId"     -> payout.asset.id.toString,
    "issued"      -> payout.issued,
    "totalStaked" -> JsNumber(BigDecimal(payout.totalStaked)),
    "credited"    -> payout.credited,
    "payouts" -> payout.payouts.map { p =>
      Json.obj("address" -> p.address.toBech32, "staked" -> p.staked, "credited" -> p.credited)
    }
  )

  def addressPayoutJson(payout: AddressPayout): JsObject = periodJson(payout.period) ++ Json.obj(
    "assetId"  -> payout.asset.id.toString,
    "staked"   -> payout.staked,
    "credited" -> payout.credited
  )
}
