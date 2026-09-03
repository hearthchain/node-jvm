package tech.hearth.it

import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.api.SyncGrpcApi.*
import tech.hearth.protobuf.transaction.{PBRecipients, PBTransactions, Recipient}
import tech.hearth.test.NumericExt
import tech.hearth.transaction.TransactionType
import tech.hearth.utils.ScorexLogging
import org.scalatest.*
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import tech.hearth.crypto.SigningKey

trait GrpcIntegrationSuiteWithThreeAddress extends BaseSuite with ScalaFutures with IntegrationPatience with RecoverMethods with ScorexLogging {
  this: TestSuite & Nodes =>

  // Every suite mixing this in talks gRPC (beforeAll already does), and the extension is off by default in
  // template.conf, so this is where it gets switched on and its port published. A suite overriding
  // hearth.extensions in its own nodeConfigs replaces this list and must repeat GrpcExtension itself.
  override protected def createDocker: Docker =
    new Docker(
      suiteConfig = ConfigFactory.parseString(s"hearth.extensions = [${Docker.GrpcExtension}]"),
      tag = getClass.getSimpleName
    )

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

    val defaultBalance: Long = 100.hearth

    def dumpBalances(node: Node, accounts: Seq[ByteString], label: String): Unit = {
      accounts.foreach(acc => {
        val balance = node.hearthBalance(acc).available
        val eff     = node.hearthBalance(acc).effective

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
      accounts.foreach(acc => miner.hearthBalance(acc).available shouldBe defaultBalance)
      accounts.foreach(acc => miner.hearthBalance(acc).effective shouldBe defaultBalance)
    }

    withClue("beforeAll") {
      correctStartBalancesFuture()
    }
  }
}
