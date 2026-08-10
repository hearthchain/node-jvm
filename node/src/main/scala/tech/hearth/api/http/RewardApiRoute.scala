package tech.hearth.api.http

import tech.hearth.lang.ValidationError
import tech.hearth.state.{Blockchain, Height}
import tech.hearth.transaction.TxValidationError.GenericError
import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.JsonConfiguration.Aux
import play.api.libs.json.{Json, JsonConfiguration, OptionHandlers, Writes}

case class RewardApiRoute(blockchain: Blockchain) extends ApiRoute {
  import RewardApiRoute.*

  override lazy val route: Route = pathPrefix("blockchain" / "rewards") {
    rewards() ~ rewardsAtHeight()
  }

  def rewards(): Route = (get & pathEndOrSingleSlash) {
    complete(getRewards(Height(blockchain.height)))
  }

  def rewardsAtHeight(): Route = (get & path(IntNumber)) { height =>
    complete(getRewards(Height(height)))
  }

  def getRewards(height: Height): Either[ValidationError, RewardStatus] =
    for {
      _      <- Either.cond(height.toInt <= blockchain.height, (), GenericError(s"Invalid height: $height"))
      reward <- blockchain.blockReward(height.toInt).toRight(GenericError(s"No information about rewards at height = $height"))
      amount          = blockchain.hearthAmount(height.toInt)
      rewardsSettings = blockchain.settings.rewardsSettings
      funcSettings    = blockchain.settings.functionalitySettings
    } yield RewardStatus(
      height,
      amount,
      reward * blockchain.blockRewardBoost(Height(height.toInt)),
      rewardsSettings.cEmit,
      rewardsSettings.halfLifeBlocks,
      blockchain.settings.hardCap - amount,
      funcSettings.daoAddress
    )
}

object RewardApiRoute {

  /** @param cEmit
    *   The total supply emitted as a forging reward over the curve's lifetime (`hearth-tokenomics-spec S2.1`).
    * @param halfLifeBlocks
    *   Display/documentation only, not consensus-relevant: how many blocks the reward takes to halve. See
    *   [[tech.hearth.settings.RewardsSettings]].
    * @param remainingToCap
    *   `hardCap - totalHearthAmount`: how much of the hard cap (genesis premine + `cEmit`) has not yet been minted,
    *   between the still-unminted tail of the emission curve and any as-yet-uncredited genesis premine.
    */
  final case class RewardStatus(
      height: Height,
      totalHearthAmount: BigInt,
      currentReward: Long,
      cEmit: Long,
      halfLifeBlocks: Long,
      remainingToCap: BigInt,
      daoAddress: Option[String]
  )

  given Aux[Json.MacroOptions] = JsonConfiguration(optionHandlers = OptionHandlers.WritesNull)

  given Writes[RewardStatus] = Json.writes
}
