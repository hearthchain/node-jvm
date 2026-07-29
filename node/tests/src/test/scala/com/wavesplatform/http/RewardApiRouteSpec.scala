package com.wavesplatform.http

import com.wavesplatform.account.Address
import com.wavesplatform.api.http.RewardApiRoute
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.Domain
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.Height
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.TxHelpers
import org.scalactic.source.Position
import play.api.libs.json.{JsObject, JsValue}

class RewardApiRouteSpec extends RouteSpec("/blockchain") with WithDomain {

  val daoAddress: Address = TxHelpers.address(100)

  val settingsWithoutAddresses: WavesSettings = RideV6.copy(blockchainSettings =
    RideV6.blockchainSettings.copy(functionalitySettings = RideV6.blockchainSettings.functionalitySettings.copy(daoAddress = None))
  )
  val settingsWithDaoAddress: WavesSettings = RideV6.copy(blockchainSettings =
    RideV6.blockchainSettings.copy(functionalitySettings =
      RideV6.blockchainSettings.functionalitySettings.copy(daoAddress = Some(daoAddress.toString))
    )
  )

  val blockRewardActivationHeight = 1
  val settingsWithVoteParams: WavesSettings = ConsensusImprovements
    .copy(blockchainSettings =
      ConsensusImprovements.blockchainSettings
        .copy(rewardsSettings =
          ConsensusImprovements.blockchainSettings.rewardsSettings.copy(term = 100, termAfterCappedRewardFeature = 50, votingInterval = 10)
        )
    )

  routePath("/rewards (NODE-855)") in {
    checkWithSettings(settingsWithoutAddresses)
    checkWithSettings(settingsWithDaoAddress)

    withDomain(settingsWithVoteParams) { d =>
      d.appendBlock()
      d.appendBlock()

      // `rewardSharesAt` pins the capped-reward activation height at 1, so its term is the one reported from the
      // start - there is no earlier regime to walk out of
      checkVoteParams(
        d,
        d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - d.blockchain.settings.rewardsSettings.votingInterval,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - 1
      )

      d.appendBlock() // activation height, vote parameters should be changed
      checkVoteParams(
        d,
        d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - d.blockchain.settings.rewardsSettings.votingInterval,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - 1
      )

      d.appendBlock()
      checkVoteParams(
        d,
        d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - d.blockchain.settings.rewardsSettings.votingInterval,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - 1
      )
    }
  }

  routePath("/rewards/{height} (NODE-856)") in {
    // Height 2: the genesis block earns no reward, so height 1 has nothing to report
    checkWithSettings(settingsWithoutAddresses, Some(2))
    checkWithSettings(settingsWithDaoAddress, Some(2))

    withDomain(settingsWithVoteParams) { d =>
      d.appendBlock()
      d.appendBlock()
      d.appendBlock() // activation height, vote parameters should be changed
      d.appendBlock()

      checkVoteParams(
        d,
        d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - d.blockchain.settings.rewardsSettings.votingInterval,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - 1,
        Some(2)
      )

      checkVoteParams(
        d,
        d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - d.blockchain.settings.rewardsSettings.votingInterval,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - 1,
        Some(3)
      )

      checkVoteParams(
        d,
        d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - d.blockchain.settings.rewardsSettings.votingInterval,
        blockRewardActivationHeight + d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature - 1,
        Some(4)
      )
    }
  }

  private def checkWithSettings(settings: WavesSettings, height: Option[Int] = None) =
    withDomain(settings) { d =>
      val route = RewardApiRoute(d.blockchain).route

      d.appendBlock()

      val pathSuffix = height.fold("")(h => s"/$h")

      Get(routePath(s"/rewards$pathSuffix")) ~> route ~> check {
        responseAs[JsValue] should matchJson(expectedResponse(d))
      }
    }

  private def expectedResponse(d: Domain) =
    s"""
       |{
       |  "height" : ${d.blockchain.height},
       |  "totalWavesAmount" : ${d.blockchain.settings.genesisSettings.initialBalance + d.blockchain.settings.rewardsSettings.initial},
       |  "currentReward" : ${d.blockchain.settings.rewardsSettings.initial},
       |  "minIncrement" : ${d.blockchain.settings.rewardsSettings.minIncrement},
       |  "term" : ${d.blockchain.settings.rewardsSettings.termAfterCappedRewardFeature},
       |  "nextCheck" : ${d.blockchain.settings.rewardsSettings.nearestTermEnd(Height(blockRewardActivationHeight), Height(d.blockchain.height), modifyTerm = true)},
       |  "votingIntervalStart" : ${d.blockchain.settings.rewardsSettings
      .nearestTermEnd(Height(blockRewardActivationHeight), Height(d.blockchain.height), modifyTerm = true) - d.blockchain.settings.rewardsSettings.votingInterval + 1},
       |  "votingInterval" : ${d.blockchain.settings.rewardsSettings.votingInterval},
       |  "votingThreshold" : ${d.blockchain.settings.rewardsSettings.votingInterval / 2 + 1},
       |  "votes" : {
       |    "increase" : 0,
       |    "decrease" : 0
       |  },
       |  "daoAddress" : ${d.blockchain.settings.functionalitySettings.daoAddress.fold("null")(addr => s"\"$addr\"")}
       |}
       |""".stripMargin

  private def checkVoteParams(d: Domain, expectedTerm: Int, expectedVotingIntervalStart: Int, expectedNextCheck: Int, height: Option[Int] = None) = {
    val route      = RewardApiRoute(d.blockchain).route
    val pathSuffix = height.fold("")(h => s"/$h")

    Get(routePath(s"/rewards$pathSuffix")) ~> route ~> check {
      val response = responseAs[JsValue]
      (response \ "term").as[Int] shouldBe expectedTerm
      (response \ "votingIntervalStart").as[Int] shouldBe expectedVotingIntervalStart
      (response \ "nextCheck").as[Int] shouldBe expectedNextCheck
    }
  }

  "block reward is never boosted" in {
    val miner      = TxHelpers.signer(3001)
    val daoAddress = TxHelpers.address(3002)

    val settings = DomainPresets.ConsensusImprovements
      .configure(fs => fs.copy(blockRewardBoostPeriod = 10, daoAddress = Some(daoAddress.toString)))

    withDomain(settings, Seq(AddrWithBalance(miner.toAddress, 100_000.waves)), generators = Seq(miner)) { d =>
      val route = new RewardApiRoute(d.blockchain).route

      def checkRewardAndShares(height: Int, expectedReward: Long)(
          implicit pos: Position
      ): Unit = {

        val path = routePath(s"/rewards/$height")
        withClue(path) {
          Get(path) ~> route ~> check {
            val jsonResp = responseAs[JsObject]
            withClue(" reward:") {
              (jsonResp \ "currentReward").as[Long] shouldBe expectedReward
            }
          }
        }
      }

      // The reward stays flat across the BoostBlockReward activation height: boosting was dropped
      (1 to 14).foreach(_ => d.appendKeyBlock(miner))
      d.blockchain.height shouldBe 15
      (2 to 15).foreach(h => checkRewardAndShares(h, 6.waves))
    }
  }
}
