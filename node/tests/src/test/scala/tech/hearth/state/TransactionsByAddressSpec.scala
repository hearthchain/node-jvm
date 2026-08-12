package tech.hearth.state

import tech.hearth.BlockGen
import tech.hearth.account.Address
import tech.hearth.block.Block
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.db.{InterferableDB, WithDomain}
import tech.hearth.history.Domain
import tech.hearth.settings.Constants
import tech.hearth.test.DomainPresets.RideV5
import tech.hearth.test.FreeSpec
import tech.hearth.transaction.TxHelpers.{defaultAddress, secondSigner}
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.{TransactionType, TxHelpers}
import org.scalactic.source.Position
import tech.hearth.crypto.SigningKey

import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class TransactionsByAddressSpec extends FreeSpec with BlockGen with WithDomain {
  def transfers(sender: SigningKey, rs: Address, amount: Long): Seq[TransferTransaction] =
    Seq(
      TxHelpers.transfer(sender, rs, amount),
      TxHelpers.transfer(sender, rs, amount)
    )

  private val sender     = TxHelpers.signer(1)
  private val recipient1 = TxHelpers.signer(2)
  private val recipient2 = TxHelpers.signer(3)

  /** Blocks come from the domain: one signed by a random account with a zeroed generation signature is not a block any
    * chain will take, and the results used to be discarded, so the chain simply stayed empty. The genesis balance goes
    * through `balances` too - `withDomain` replaces whatever the settings carried.
    */
  private def test(f: (Address, Seq[Block], Domain) => Unit): Unit = {
    val txCount1 = 20
    val txCount2 = 30

    Seq(recipient1, recipient2).foreach { recipient =>
      withDomain(DomainPresets.RideV6, Seq(AddrWithBalance(sender.toAddress))) { d =>
        val genesisBlock  = d.lastBlock
        val transactions1 = (1 to txCount1 / 2).flatMap(_ => transfers(sender, recipient.toAddress, Constants.TotalHearth / 2 / txCount1))
        val block1        = d.appendBlock(transactions1*)
        val transactions2 = (1 to txCount2 / 2).flatMap(_ => transfers(sender, recipient.toAddress, Constants.TotalHearth / 2 / txCount2))
        val block2        = d.appendBlock(transactions2*)

        val blocks = Seq(genesisBlock, block1, block2)

        Seq[Address](sender.toAddress, recipient1.toAddress, recipient2.toAddress).foreach(f(_, blocks, d))

        // An empty block on top: what an address has done must not change with it
        d.appendBlock()

        Seq[Address](sender.toAddress, recipient1.toAddress, recipient2.toAddress).foreach(f(_, blocks, d))
      }
    }
  }

  private def collectTransactions(forAddress: Address, fromBlocks: Seq[Block]): Seq[(Int, ByteStr)] =
    fromBlocks.zipWithIndex
      .flatMap { case (b, h) => b.transactionData.map(t => (h + 1, t)) }
      .collect {
        // The genesis block has no transactions, so it contributes nothing to an address' history
        case (h, t: TransferTransaction) if t.sender.toAddress == forAddress || t.transfers.exists(_.address == forAddress) =>
          (h, t.id())
      }
      .reverse

  "Transactions by address returns" - {
    "correct N txs on request" - {
      "with `after`" in test { (sender, blocks, d) =>
        val senderTransactions                                  = collectTransactions(sender, blocks)
        def transactionsAfter(id: ByteStr): Seq[(Int, ByteStr)] = senderTransactions.dropWhile { case (_, txId) => txId != id }.tail
        senderTransactions.map(_._2).foreach { id =>
          transactionsAfter(id) shouldEqual d.addressTransactions(sender, Some(id)).map { case (h, tx) => h -> tx.id() }
        }
      }
    }
    "all transactions" in test { (sender, blocks, d) =>
      collectTransactions(sender, blocks) shouldEqual d.addressTransactions(sender).map { case (h, tx) => h -> tx.id() }
    }
    "distinct result avoiding inconsistent state" in {
      val startRead = new ReentrantLock()
      withDomain(RideV5, AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, secondSigner), InterferableDB(_, startRead)) { d =>
        d.appendBlock()
        d.appendMicroBlock(TxHelpers.transfer())
        startRead.lock()
        val txs = Future { d.addressTransactions(defaultAddress).map(_._2.tpe) }
        d.blockchain.bestLiquidSnapshot.synchronized(d.appendKeyBlock())
        startRead.unlock()
        Await.result(txs, 1.minute) shouldBe List(TransactionType.Transfer)
      }
    }
  }
}
