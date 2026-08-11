package tech.hearth.http

import tech.hearth.account.Address
import tech.hearth.api.http.RewardApiRoute
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.settings.HearthSettings
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.TxHelpers
import org.scalactic.source.Position
import play.api.libs.json.{JsObject, JsValue}

/** Gone with the vote: reward voting is unimplemented (see BlockRewardSpec), so the fields this route used to report
  * about it - term, nextCheck, votingIntervalStart, votingInterval, votingThreshold, votes, minIncrement - are gone
  * too, replaced by the emission curve's own parameters (cEmit, halfLifeBlocks) and remainingToCap. What is left to
  * test is the JSON shape itself, at both routes, with and without a configured DAO address.
  */
class RewardApiRouteSpec extends RouteSpec("/blockchain") with WithDomain {

  val daoAddress: Address = TxHelpers.address(100)

  val settingsWithoutAddresses: HearthSettings = RideV6.copy(blockchainSettings =
    RideV6.blockchainSettings.copy(functionalitySettings = RideV6.blockchainSettings.functionalitySettings.copy(daoAddress = None))
  )
  val settingsWithDaoAddress: HearthSettings = RideV6.copy(blockchainSettings =
    RideV6.blockchainSettings.copy(functionalitySettings =
      RideV6.blockchainSettings.functionalitySettings.copy(daoAddress = Some(daoAddress.toString))
    )
  )

  routePath("/rewards (NODE-855)") in {
    checkWithSettings(settingsWithoutAddresses)
    checkWithSettings(settingsWithDaoAddress)
  }

  routePath("/rewards/{height} (NODE-856)") in {
    // Height 2: the genesis block earns no reward, so height 1 has nothing to report
    checkWithSettings(settingsWithoutAddresses, Some(2))
    checkWithSettings(settingsWithDaoAddress, Some(2))
  }

  private def checkWithSettings(settings: HearthSettings, height: Option[Int] = None) =
    withDomain(settings) { d =>
      val route = RewardApiRoute(d.blockchain).route

      d.appendBlock()

      val pathSuffix = height.fold("")(h => s"/$h")

      Get(routePath(s"/rewards$pathSuffix")) ~> route ~> check {
        responseAs[JsValue] should matchJson(expectedResponse(d))
      }
    }

  private def expectedResponse(d: Domain) = {
    val rewardsSettings   = d.blockchain.settings.rewardsSettings
    val totalHearthAmount = d.blockchain.settings.initialBalance + rewardsSettings.initialReward
    val hardCap           = d.blockchain.settings.hardCap
    s"""
       |{
       |  "height" : ${d.blockchain.height},
       |  "totalHearthAmount" : $totalHearthAmount,
       |  "currentReward" : ${rewardsSettings.initialReward},
       |  "cEmit" : ${rewardsSettings.cEmit},
       |  "halfLifeBlocks" : ${rewardsSettings.halfLifeBlocks},
       |  "remainingToCap" : ${hardCap - totalHearthAmount},
       |  "daoAddress" : ${d.blockchain.settings.functionalitySettings.daoAddress.fold("null")(addr => s"\"$addr\"")}
       |}
       |""".stripMargin
  }

  "block reward is never boosted" in {
    val miner      = TxHelpers.signer(3001)
    val daoAddress = TxHelpers.address(3002)

    val settings = DomainPresets.ConsensusImprovements
      .configure(fs => fs.copy(blockRewardBoostPeriod = 10, daoAddress = Some(daoAddress.toString)))

    withDomain(settings, Seq(AddrWithBalance(miner.toAddress, 100_000.hearth)), generators = Seq(miner)) { d =>
      val route = new RewardApiRoute(d.blockchain).route

      def checkRewardAndShares(height: Int, expectedReward: Long)(implicit
          pos: Position
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
      (2 to 15).foreach(h => checkRewardAndShares(h, 6.hearth))
    }
  }
}
