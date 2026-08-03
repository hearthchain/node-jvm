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
      amount              = blockchain.wavesAmount(height.toInt)
      rewardsSettings     = blockchain.settings.rewardsSettings
      funcSettings        = blockchain.settings.functionalitySettings
      nextCheck           = rewardsSettings.nearestTermEnd(Height(1), height, modifyTerm = true) // CappedReward is active
      votingIntervalStart = nextCheck - rewardsSettings.votingInterval + 1
      votingThreshold     = rewardsSettings.votingInterval / 2 + 1
      term                = rewardsSettings.termAfterCappedRewardFeature                         // CappedReward is active
    } yield RewardStatus(
      height,
      amount,
      reward * blockchain.blockRewardBoost(Height(height.toInt)),
      rewardsSettings.minIncrement,
      term,
      nextCheck,
      votingIntervalStart,
      rewardsSettings.votingInterval,
      votingThreshold,
      RewardVotes(0, 0),
      funcSettings.daoAddress
    )
}

object RewardApiRoute {
  final case class RewardStatus(
      height: Height,
      totalWavesAmount: BigInt,
      currentReward: Long,
      minIncrement: Long,
      term: Int,
      nextCheck: Height,
      votingIntervalStart: Height,
      votingInterval: Int,
      votingThreshold: Int,
      votes: RewardVotes,
      daoAddress: Option[String]
  )

  final case class RewardVotes(increase: Int, decrease: Int)

  given Aux[Json.MacroOptions] = JsonConfiguration(optionHandlers = OptionHandlers.WritesNull)

  given Writes[RewardVotes]  = Json.writes
  given Writes[RewardStatus] = Json.writes
}
