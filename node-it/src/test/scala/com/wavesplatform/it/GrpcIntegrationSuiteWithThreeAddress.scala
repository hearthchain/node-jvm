package com.wavesplatform.it

import com.google.protobuf.ByteString
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.it.api.SyncGrpcApi.*
import com.wavesplatform.protobuf.transaction.{PBRecipients, PBTransactions, Recipient}
import com.wavesplatform.test.NumericExt
import com.wavesplatform.transaction.TransactionType
import com.wavesplatform.utils.ScorexLogging
import org.scalatest.*
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import tech.hearth.crypto.SigningKey

trait GrpcIntegrationSuiteWithThreeAddress extends BaseSuite with ScalaFutures with IntegrationPatience with RecoverMethods with ScorexLogging {
  this: TestSuite & Nodes =>

  // Same seeds as IntegrationSuiteWithThreeAddresses (the REST fixture), so grpc suites trading the shared
  // genesis test asset (see template.conf) reach the same accounts it was distributed to.
  protected lazy val firstAcc: SigningKey  = keyPairFromSeed("firstKeyPair".getBytes("UTF-8"))
  protected lazy val secondAcc: SigningKey = keyPairFromSeed("secondKeyPair".getBytes("UTF-8"))
  protected lazy val thirdAcc: SigningKey  = keyPairFromSeed("thirdKeyPair".getBytes("UTF-8"))

  protected lazy val firstAddress: ByteString  = PBRecipients.create(Address.fromPublicKey(PublicKey(firstAcc.publicKey()))).publicKeyHash
  protected lazy val secondAddress: ByteString = PBRecipients.create(Address.fromPublicKey(PublicKey(secondAcc.publicKey()))).publicKeyHash
  protected lazy val thirdAddress: ByteString  = PBRecipients.create(Address.fromPublicKey(PublicKey(thirdAcc.publicKey()))).publicKeyHash

  abstract protected override def beforeAll(): Unit = {
    super.beforeAll()

    val defaultBalance: Long = 100.waves

    def dumpBalances(node: Node, accounts: Seq[ByteString], label: String): Unit = {
      accounts.foreach(acc => {
        val balance = node.wavesBalance(acc).available
        val eff     = node.wavesBalance(acc).effective

        val formatted = s"$acc: balance = $balance, effective = $eff"
        log.debug(s"$label account balance:\n$formatted")
      })
    }

    def waitForTxsToReachAllNodes(txIds: Seq[String]): Unit = {
      val txNodePairs = for {
        txId <- txIds
        node <- nodes
      } yield (node, txId)

      txNodePairs.foreach({ case (node, tx) => node.waitForTransaction(tx) })
    }

    def makeTransfers(accounts: Seq[ByteString]): Seq[String] = accounts.map { acc =>
      PBTransactions
        .vanilla(
          sender.broadcastTransfer(sender.keyPair, Recipient().withPublicKeyHash(acc), defaultBalance, sender.fee(TransactionType.Transfer.id.toByte))
        )
        .explicitGet()
        .id()
        .toString
    }

    def correctStartBalancesFuture(): Unit = {
      nodes.foreach(n => n.waitForHeight(2))
      val accounts = Seq(firstAddress, secondAddress, thirdAddress)

      dumpBalances(sender, accounts, "initial")
      val txs = makeTransfers(accounts)

      val height = nodes.map(_.height).max

      withClue(s"waitForHeight(${height + 2})") {
        nodes.foreach(n => n.waitForHeight(height + 1))
        nodes.foreach(n => n.waitForHeight(height + 2))
      }

      withClue("waitForTxsToReachAllNodes") {
        waitForTxsToReachAllNodes(txs)
      }

      dumpBalances(sender, accounts, "after transfer")
      accounts.foreach(acc => miner.wavesBalance(acc).available shouldBe defaultBalance)
      accounts.foreach(acc => miner.wavesBalance(acc).effective shouldBe defaultBalance)
    }

    withClue("beforeAll") {
      correctStartBalancesFuture()
    }
  }
}
