package tech.hearth.it.sync.transactions

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.api.http.TransactionsApiRoute.LeaseStatus
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.sync.*
import tech.hearth.it.transactions.BaseTransactionSuite
import org.scalatest.CancelAfterFailure
import play.api.libs.json.Json

class LeaseStatusTestSuite extends BaseTransactionSuite with CancelAfterFailure {
  import LeaseStatusTestSuite.*

  override protected def nodeConfigs: Seq[Config] = Configs

  test("verification of leasing status") {
    val createdLeaseTxId = sender.lease(firstKeyPair, secondAddress, leasingAmount, leasingFee = minFee).id
    nodes.waitForHeightAriseAndTxPresent(createdLeaseTxId)
    val status = getStatus(createdLeaseTxId)
    status shouldBe LeaseStatus.active.toString

    val cancelLeaseTxId = sender.cancelLease(firstKeyPair, createdLeaseTxId, fee = minFee).id
    miner.waitForTransaction(cancelLeaseTxId)
    nodes.waitForHeightArise()
    val status1 = getStatus(createdLeaseTxId)
    status1 shouldBe LeaseStatus.canceled.toString
    val sizeActiveLeases = sender.activeLeases(firstAddress).size
    sizeActiveLeases shouldBe 0
  }

  private def getStatus(txId: String): String = {
    val r = sender.get(s"/transactions/info/$txId")
    (Json.parse(r.getResponseBody) \ "status").as[String]

  }
}

object LeaseStatusTestSuite {
  private val blockGenerationOffset = "10000ms"
  import tech.hearth.it.NodeConfigs.Default

  private val minerConfig = ConfigFactory.parseString(s"""waves {
                                                         |   miner{
                                                         |      enable = yes
                                                         |      minimal-block-generation-offset = $blockGenerationOffset
                                                         |      quorum = 0
                                                         |      micro-block-interval = 3s
                                                         |      max-transactions-in-key-block = 0
                                                         |   }
                                                         |}
     """.stripMargin)

  private val notMinerConfig = ConfigFactory.parseString(s"""waves {
                                                            |   miner.enable = no
                                                            |   miner.minimal-block-generation-offset = $blockGenerationOffset
                                                            |}
     """.stripMargin)

  // A higher-balance generator (not Default.head, the lowest-balance one) so PoS block delay stays comfortably
  // under the fixture's own tx-await timeout (8 * average-block-delay).
  val Configs: Seq[Config] = Seq(
    minerConfig.withFallback(Default(8)),
    notMinerConfig.withFallback(Default(1))
  )

}
