package com.wavesplatform.state

import com.wavesplatform.BlockGen
import com.wavesplatform.account.Address
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.db.{InterferableDB, WithDomain}
import com.wavesplatform.history.Domain
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.{Constants, GenesisBalanceSettings, GenesisSettings, WavesSettings}
import com.wavesplatform.test.DomainPresets
import com.wavesplatform.test.DomainPresets.RideV5
import com.wavesplatform.test.FreeSpec
import com.wavesplatform.transaction.TxHelpers.{defaultAddress, secondSigner}
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{Transaction, TransactionType, TxHelpers, TxVersion}
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

  def mkBlock(sender: SigningKey, reference: ByteStr, transactions: Seq[Transaction]): Block =
    Block
      .buildAndSign(ntpNow, reference, 1000, ByteStr(new Array[Byte](Block.GenerationVRFSignatureLength)), transactions, sender, Seq.empty, None, None, None)
      .explicitGet()

  private val genesisTimestamp = ntpNow

  private val genesisSettings: GenesisSettings = GenesisSettings(
    genesisTimestamp,
    None,
    1000,
    1.minute,
    balances = Seq(GenesisBalanceSettings(TxHelpers.signer(1).toAddress.toBech32, Constants.TotalWaves))
  )

  // The genesis snapshot is built from the settings, so the domain has to run with the very same genesis settings
  private val domainSettings: WavesSettings = {
    val base = DomainPresets.SettingsFromDefaultConfig
    base.copy(blockchainSettings = base.blockchainSettings.copy(genesisSettings = genesisSettings))
  }

  val setup: Seq[(SigningKey, SigningKey, SigningKey, Seq[Block])] = {
    val sender     = TxHelpers.signer(1)
    val recipient1 = TxHelpers.signer(2)
    val recipient2 = TxHelpers.signer(3)

    val genesisBlock = Block
      .genesis(
        genesisSettings,
        domainSettings.blockchainSettings.functionalitySettings,
      )
      .explicitGet()

    val txCount1 = 20
    val txCount2 = 30

    Seq(recipient1, recipient2).map { recipient =>
      val transactions1 = (1 to txCount1 / 2).flatMap(_ => transfers(sender, recipient.toAddress, Constants.TotalWaves / 2 / txCount1))
      val block1        = mkBlock(sender, genesisBlock.id(), transactions1)
      val transactions2 = (1 to txCount2 / 2).flatMap(_ => transfers(sender, recipient.toAddress, Constants.TotalWaves / 2 / txCount2))
      val block2        = mkBlock(sender, block1.id(), transactions2)

      (sender, recipient1, recipient2, Seq(genesisBlock, block1, block2))
    }
  }

  private def test(f: (Address, Seq[Block], Domain) => Unit): Unit = {
    setup.foreach { case (sender, r1, r2, blocks) =>
      withDomain(domainSettings) { d =>
        for (b <- blocks) {
          d.blockchainUpdater.processBlock(b, b.header.generationSignature, snapshot = None, generatorSet = Seq.empty, verify = false)
        }

        Seq[Address](sender.toAddress, r1.toAddress, r2.toAddress).foreach(f(_, blocks, d))

        d.blockchainUpdater.processBlock(
          TestBlock.create(System.currentTimeMillis(), blocks.last.signature, Seq.empty).block,
          ByteStr(new Array[Byte](32)),
          snapshot = None,
          generatorSet = Seq.empty,
          verify = false
        )

        Seq[Address](sender.toAddress, r1.toAddress, r2.toAddress).foreach(f(_, blocks, d))
      }
    }
  }

  private def collectTransactions(forAddress: Address, fromBlocks: Seq[Block]): Seq[(Int, ByteStr)] =
    fromBlocks.zipWithIndex
      .flatMap { case (b, h) => b.transactionData.map(t => (h + 1, t)) }
      .collect {
        // The genesis block has no transactions, so it contributes nothing to an address' history
        case (h, t: TransferTransaction) if t.sender.toAddress == forAddress || t.recipient == forAddress => (h, t.id())
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
