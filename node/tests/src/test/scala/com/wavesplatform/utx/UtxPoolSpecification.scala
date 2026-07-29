package com.wavesplatform.utx

import com.wavesplatform
import com.wavesplatform.*
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.block.Block
import tech.hearth.crypto.SigningKey
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.consensus.TransactionsOrdering
import com.wavesplatform.database.{RDB, RocksDBWriter, TestStorageFactory}
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.events.UtxEvent
import com.wavesplatform.history.Domain.BlockchainUpdaterExt
import com.wavesplatform.mining.*
import com.wavesplatform.settings.*
import com.wavesplatform.state.*
import com.wavesplatform.state.diffs.*
import com.wavesplatform.state.utils.TestRocksDB
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.TxValidationError.{GenericError, SenderIsBlacklisted}
import com.wavesplatform.transaction.transfer.*
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.{Transaction, *}
import com.wavesplatform.utx.UtxPool.PackStrategy
import org.scalacheck.Gen.*
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.concurrent.Eventually

import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.util.Using

private object UtxPoolSpecification {
  final case class TempDB(fs: FunctionalitySettings, dbSettings: DBSettings) extends AutoCloseable {
    val path: Path            = Files.createTempDirectory("rocksdb-test-utx")
    val rdb                   = RDB.open(dbSettings.copy(directory = path.toAbsolutePath.toString))
    val writer: RocksDBWriter = TestRocksDB.withFunctionalitySettings(rdb, fs)

    override def close(): Unit = {
      writer.close()
      rdb.close()
      TestHelpers.deleteRecursively(path)
    }
  }
}

class UtxPoolSpecification extends FreeSpec, WithDomain, EitherValues, Eventually {
  private val PoolDefaultMaxBytes = 50 * 1024 * 1024 // 50 MB

  import FeeValidation.ScriptExtraFee as extraFee
  import FunctionalitySettings.TESTNET.maxTransactionTimeBackOffset as maxAge
  import UtxPoolSpecification.*

  private def withBlockchain[A](genAccounts: Map[Address, Long])(test: BlockchainUpdaterImpl => A): A = {
    val genesisSettings = TestHelpers.genesisSettings(genAccounts)
    val origSettings    = WavesSettings.default()
    val settings = origSettings.copy(
      blockchainSettings = BlockchainSettings(
        'T',
        FunctionalitySettings.TESTNET,
        genesisSettings,
        RewardsSettings.TESTNET
      ),
      autoShutdownOnUnsupportedFeature = false
    )

    Using.resource(TempDB(settings.blockchainSettings.functionalitySettings, settings.dbSettings)) { dbContext =>
      val (bcu, rdbWriter) = TestStorageFactory(settings, dbContext.rdb, new TestTime, ignoreBlockchainUpdateTriggers)
      Using.resource(rdbWriter) { _ =>
        bcu.processBlock(
          Block
            .genesis(
              genesisSettings,
            )
            .explicitGet()
        ) should beRight
        test(bcu)
      }
    }
  }

  private def transfer(sender: SigningKey, maxAmount: Long) =
    (for {
      amount    <- chooseNum(1L, (maxAmount * 0.9).toLong)
      recipient <- accountGen
      fee       <- chooseNum(extraFee, (maxAmount * 0.1).toLong)
    } yield TxHelpers.transfer(from = sender, to = recipient.toAddress, amount = amount, asset = Waves, fee = fee, feeAsset = Waves, attachment = ByteStr.empty))
      .label("transferTransaction")

  private def withState[A](test: (SigningKey, Long, BlockchainUpdaterImpl) => A): A = {
    val sender        = TxHelpers.signer(1)
    val senderBalance = ENOUGH_AMT
    withBlockchain(Map(sender.toAddress -> senderBalance)) { bcu =>
      test(sender, senderBalance, bcu)
    }
  }

  private def withTwoOutOfManyValidPayments[A](test: (UtxPoolImpl, TestTime, IndexedSeq[TransferTransaction], FiniteDuration) => A): A =
    withState { case (sender, senderBalance, bcu) =>
      val recipient = TxHelpers.signer(2)

      val time = TestTime()
      val utx =
        new UtxPoolImpl(
          time,
          bcu,
          UtxSettings(
            10,
            PoolDefaultMaxBytes,
            1000,
            Set.empty,
            Set.empty,
            Set.empty,
            allowTransactionsFromSmartAccounts = true,
            allowSkipChecks = false,
            forceValidateInCleanup = false,
            alwaysUnlimitedExecution = false
          ),
          Int.MaxValue,
          isMiningEnabled = true
        )
      val amountPart = (senderBalance - extraFee) / 2 - extraFee
      val txs = for (_ <- 1 to 10) yield createWavesTransfer(sender, recipient.toAddress, amountPart, extraFee, time.getTimestamp()).explicitGet()
      test(utx, time, txs, 2000.millis)
    }

  private def withBlacklisted[A](test: (SigningKey, UtxPoolImpl, Seq[TransferTransaction]) => A): A =
    withState { case (sender, _, bcu) =>
      val recipient = TxHelpers.signer(2)
      val time      = TestTime()
      val txs       = (1 to 10).map(_ => transferWithRecipient(sender, PublicKey(recipient.publicKey)))
      val settings =
        UtxSettings(
          10,
          PoolDefaultMaxBytes,
          1000,
          Set(sender.toAddress.toString),
          Set.empty,
          Set.empty,
          allowTransactionsFromSmartAccounts = true,
          allowSkipChecks = false,
          forceValidateInCleanup = false,
          alwaysUnlimitedExecution = false
        )
      val utxPool = new UtxPoolImpl(time, bcu, settings, Int.MaxValue, isMiningEnabled = true)
      test(sender, utxPool, txs)
    }

  private def withMassTransferWithBlacklisted[A](allowRecipients: Boolean)(test: (SigningKey, UtxPoolImpl, Seq[MassTransferTransaction]) => A): A = {
    withState { case (sender, senderBalance, bcu) =>
      val recipients = (1 to 10).map(idx => PublicKey(TxHelpers.signer(1 + idx).publicKey))
      val time       = TestTime()
      // @TODO: Random transactions
      val txs = (1 to 10).map(_ => massTransferWithRecipients(sender, recipients, senderBalance / 10)) ++
        (if (!allowRecipients) Seq(massTransferWithRecipients(sender, Seq.empty, senderBalance / 10)) else Seq.empty)
      val whitelist: Set[String] = if (allowRecipients) recipients.map(_.toAddress.toString).toSet else Set.empty
      val settings =
        UtxSettings(
          txs.length,
          PoolDefaultMaxBytes,
          1000,
          Set(sender.toAddress.toString),
          whitelist,
          Set.empty,
          allowTransactionsFromSmartAccounts = true,
          allowSkipChecks = false,
          forceValidateInCleanup = false,
          alwaysUnlimitedExecution = false
        )
      val utxPool = new UtxPoolImpl(time, bcu, settings, Int.MaxValue, isMiningEnabled = true)
      test(sender, utxPool, txs)
    }
  }

  private def withBlacklistedAndAllowedByRule[A](test: (SigningKey, UtxPoolImpl, Seq[TransferTransaction]) => A): A =
    withState { case (sender, _, bcu) =>
      val recipient = TxHelpers.signer(2)
      val time      = TestTime()
      val txs       = (1 to 10).map(_ => transferWithRecipient(sender, PublicKey(recipient.publicKey)))
      val settings =
        UtxSettings(
          txs.length,
          PoolDefaultMaxBytes,
          1000,
          Set(sender.toAddress.toString),
          Set(recipient.toAddress.toString),
          Set.empty,
          allowTransactionsFromSmartAccounts = true,
          allowSkipChecks = false,
          forceValidateInCleanup = false,
          alwaysUnlimitedExecution = false
        )
      val utxPool = new UtxPoolImpl(time, bcu, settings, Int.MaxValue, isMiningEnabled = true)
      test(sender, utxPool, txs)
    }

  private def withBlacklistedAndWhitelisted[A](test: (SigningKey, UtxPoolImpl, Seq[TransferTransaction]) => A): A = {
    withState { case (sender, _, bcu) =>
      val recipient = TxHelpers.signer(2)
      val time      = TestTime()
      val txs       = (1 to 10).map(_ => transferWithRecipient(sender, PublicKey(recipient.publicKey)))
      val settings =
        UtxSettings(
          txs.length,
          PoolDefaultMaxBytes,
          1000,
          Set(sender.toAddress.toString),
          Set.empty,
          Set(sender.toAddress.toString),
          allowTransactionsFromSmartAccounts = true,
          allowSkipChecks = false,
          forceValidateInCleanup = false,
          alwaysUnlimitedExecution = false
        )
      val utxPool = new UtxPoolImpl(time, bcu, settings, Int.MaxValue, isMiningEnabled = true)
      test(sender, utxPool, txs)
    }
  }

  private def withDualTxs[A](test: (UtxPool, TestTime, Seq[Transaction], Seq[Transaction]) => A): A =
    withState { case (sender, _, bcu) =>
      val count = 5
      val txs1  = (1 to count).map(_ => transfer(sender))
      val txs2  = (1 to count).map(_ => transfer(sender))
      val time  = TestTime()
      val utx = new UtxPoolImpl(
        time,
        bcu,
        UtxSettings(
          10,
          PoolDefaultMaxBytes,
          1000,
          Set.empty,
          Set.empty,
          Set.empty,
          allowTransactionsFromSmartAccounts = true,
          allowSkipChecks = false,
          forceValidateInCleanup = false,
          alwaysUnlimitedExecution = false
        ),
        Int.MaxValue,
        isMiningEnabled = true
      )
      test(utx, time, txs1, txs2)
    }

  private def transfer(sender: SigningKey) =
    TxHelpers.transfer(from = sender, to = TxHelpers.address(2), amount = 1, asset = Waves, fee = extraFee, feeAsset = Waves, attachment = ByteStr.empty)

  private def transferWithRecipient(sender: SigningKey, recipient: PublicKey) =
    TxHelpers.transfer(from = sender, to = recipient.toAddress, amount = 1, asset = Waves, fee = extraFee, feeAsset = Waves, attachment = ByteStr.empty)

  private def massTransferWithRecipients(sender: SigningKey, recipients: Seq[PublicKey], maxAmount: Long) = {
    val amount    = maxAmount / (recipients.size + 1)
    val transfers = recipients.map(r => ParsedTransfer(r.toAddress, TxNonNegativeAmount.unsafeFrom(amount)))
    val minFee    = FeeValidation.FeeConstants(TransactionType.Transfer) + FeeValidation.FeeConstants(TransactionType.MassTransfer) * transfers.size
    TxHelpers.massTransfer(sender, transfers.map(t => (t.address, t.amount.value)), Waves, minFee)
  }

  private def utxTest(utxSettings: UtxSettings, txCount: Int = 10)(f: (Seq[TransferTransaction], UtxPool, TestTime) => Unit): Unit = {
    withState { case (sender, _, bcu) =>
      val time = TestTime()
      val txs  = (1 to txCount).map(_ => transfer(sender))

      val utx = new UtxPoolImpl(time, bcu, utxSettings, Int.MaxValue, isMiningEnabled = true)
      f(txs, utx, time)
    }
  }

  "UTX Pool" - {
    "does not add new transactions when full" in utxTest(
      UtxSettings(
        1,
        PoolDefaultMaxBytes,
        1000,
        Set.empty,
        Set.empty,
        Set.empty,
        allowTransactionsFromSmartAccounts = true,
        allowSkipChecks = false,
        forceValidateInCleanup = false,
        alwaysUnlimitedExecution = false
      )
    ) { (txs, utx, _) =>
      utx.putIfNew(txs.head).resultE should beRight
      all(txs.tail.map(t => utx.putIfNew(t).resultE)) should produce("pool size limit")
    }

    "does not add new transactions when full in bytes" in utxTest(
      UtxSettings(
        999999,
        152,
        1000,
        Set.empty,
        Set.empty,
        Set.empty,
        allowTransactionsFromSmartAccounts = true,
        allowSkipChecks = false,
        forceValidateInCleanup = false,
        alwaysUnlimitedExecution = false
      )
    ) { (txs, utx, _) =>
      utx.putIfNew(txs.head).resultE should beRight
      all(txs.tail.map(t => utx.putIfNew(t).resultE)) should produce("pool bytes size limit")
    }

    "adds new transactions when skip checks is allowed" in {
      withState { case (sender, senderBalance, bcu) =>
        val time = TestTime()

        val gen = for {
          headTransaction <- transfer(sender, senderBalance / 2)
          vipTransaction <- transfer(sender, senderBalance / 2)
            .suchThat(TransactionsOrdering.InUTXPool(Set.empty).compare(_, headTransaction) < 0)
        } yield (headTransaction, vipTransaction)

        forAll(gen, Gen.choose(0, 1).label("allowSkipChecks")) { case ((headTransaction, vipTransaction), allowSkipChecks) =>
          val utxSettings =
            UtxSettings(
              1,
              // Exactly one transaction's worth: a transfer is no longer 152 bytes, and with the old literal not even
              // the first one fitted
              headTransaction.bytes().length,
              1,
              Set.empty,
              Set.empty,
              Set.empty,
              allowTransactionsFromSmartAccounts = true,
              allowSkipChecks = allowSkipChecks == 1,
              forceValidateInCleanup = false,
              alwaysUnlimitedExecution = false
            )
          val utx = new UtxPoolImpl(time, bcu, utxSettings, Int.MaxValue, isMiningEnabled = true)

          utx.putIfNew(headTransaction).resultE should beRight
          utx.putIfNew(vipTransaction).resultE should matchPattern {
            case Right(_) if allowSkipChecks == 1 =>
            case Left(_)                          =>
          }
        }
      }
    }

    "does not broadcast the same transaction twice if not allowed" in utxTest(
      utxSettings = UtxSettings(
        20,
        PoolDefaultMaxBytes,
        1000,
        Set.empty,
        Set.empty,
        Set.empty,
        allowTransactionsFromSmartAccounts = true,
        allowSkipChecks = false,
        forceValidateInCleanup = false,
        alwaysUnlimitedExecution = false
      )
    ) { (txs, utx, _) =>
      utx.putIfNew(txs.head).resultE should matchPattern { case Right(true) => }
      utx.putIfNew(txs.head).resultE should matchPattern { case Right(false) => }
    }

    "packUnconfirmed result is limited by constraint" in withDualTxs { case (utx, _, txs, _) =>
      txs.foreach(tx => utx.putIfNew(tx).resultE should beRight)
      utx.all.size shouldEqual txs.size

      val maxNumber                = Math.max(utx.all.size / 2, 3)
      val rest                     = limitByNumber(maxNumber)
      val (packed, restUpdated, _) = utx.packUnconfirmed(rest, None, PackStrategy.Unlimited)

      packed.get.lengthCompare(maxNumber) should be <= 0
      if (maxNumber <= utx.all.size) restUpdated.isFull shouldBe true
    }

    "evicts expired transactions when packUnconfirmed is called" in withDualTxs { case (utx, time, txs, _) =>
      txs.foreach(tx => utx.putIfNew(tx).resultE should beRight)
      utx.all.size shouldEqual txs.size

      time.setTimeIfGreater(txs.maxBy(_.timestamp).timestamp + maxAge.toMillis + 1000)

      val (packed, _, _) = utx.packUnconfirmed(limitByNumber(100), None, PackStrategy.Unlimited)
      packed shouldBe empty
      utx.all shouldBe empty
    }

    "evicts one of mutually invalid transactions when packUnconfirmed is called" in withTwoOutOfManyValidPayments { case (utx, time, txs, offset) =>
      txs.foreach(tx => utx.putIfNew(tx).resultE should beRight)
      utx.all.size shouldEqual txs.size

      time.advance(offset)

      val (packed, _, _) = utx.packUnconfirmed(limitByNumber(100), None, PackStrategy.Unlimited)
      packed.get.size shouldBe 2
      utx.all.size shouldBe 2
    }

    "processes transaction fees" in {
      val blockMiner    = TxHelpers.signer(1200)
      val recipient     = TxHelpers.signer(1201)
      val initialAmount = 10000.waves
      // The deposit it holds as a committed generator is locked, so it has to be funded on top of what it spends
      val minerBalance = initialAmount + 0.001.waves * 2 + CommitToGenerationTransaction.DepositInWavelets

      withDomain(DomainPresets.NG, balances = Seq(AddrWithBalance(blockMiner.toAddress, minerBalance)), generators = Seq(blockMiner)) { d =>
        val transfer1 = TxHelpers.transfer(blockMiner, recipient.toAddress, amount = initialAmount, fee = 0.001.waves)
        val transfer2 = TxHelpers.transfer(blockMiner, recipient.toAddress, amount = 0.0004.waves, fee = 0.001.waves)
        d.appendBlock(d.createBlock(generator = blockMiner))
        d.utxPool.addTransaction(transfer1, verify = true)
        d.utxPool.addTransaction(transfer2, verify = true)

        // Both fit, which is what the fees make possible; the order is the pool's own, by fee per byte
        d.utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None)._1.get should contain theSameElementsAs Seq(transfer1, transfer2)
      }
    }

    "blacklisting" - {
      "prevent a transfer transaction from specific addresses" in {
        def test(utxPool: UtxPoolImpl, txs: Seq[Transaction]): Unit = {
          val r = txs.forall { tx =>
            utxPool.putIfNew(tx).resultE match {
              case Left(SenderIsBlacklisted(_)) => true
              case _                            => false
            }
          }

          r shouldBe true
          utxPool.all.size shouldEqual 0
          utxPool.close()
        }

        withBlacklisted { case (_, utxPool, txs) => test(utxPool, txs) }
        withMassTransferWithBlacklisted(allowRecipients = false) { case (_, utxPool, txs) => test(utxPool, txs) }
      }

      "allow a transfer transaction from blacklisted address to specific addresses" in {
        def test(utxPool: UtxPoolImpl, txs: Seq[Transaction]): Unit = {
          txs.foreach(utxPool.putIfNew(_).resultE should beRight)
          utxPool.all.size shouldEqual txs.size
          utxPool.close()
        }

        withBlacklistedAndAllowedByRule { case (_, utxPool, txs) => test(utxPool, txs) }
        withMassTransferWithBlacklisted(allowRecipients = true) { case (_, utxPool, txs) => test(utxPool, txs) }
      }

      "allow a transfer transaction from whitelisted address" in {
        withBlacklistedAndWhitelisted { case (_, utxPool, txs) =>
          all(txs.map { t =>
            utxPool.putIfNew(t).resultE
          }) shouldBe Symbol("right")
          utxPool.all.size shouldEqual txs.size
          utxPool.close()
        }
      }
    }

    "cleanup" - {
      // The deposit of the generator withDomain commits is locked, so it comes on top: what this address can spend
      // is still the 11 waves the transfers below are measured against
      "doesnt take the composite snapshot into account" in withDomain(
        balances = Seq(AddrWithBalance(TxHelpers.defaultAddress, 11.waves + CommitToGenerationTransaction.DepositInWavelets))
      ) { d =>
        val transfers = Seq.fill(10)(TxHelpers.transfer(amount = 10.waves))
        transfers.foreach(tx => d.utxPool.addTransaction(tx, verify = false))
        d.utxPool.cleanUnconfirmed()
        d.utxPool.nonPriorityTransactions.toSet shouldBe transfers.toSet
      }

    }

    "event stream" - {
      "fires events correctly" in {
        val preconditions = for {
          richAcc   <- accountGen
          secondAcc <- accountGen
          ts = System.currentTimeMillis()
          fee <- smallFeeGen
          validTransfer = TxHelpers.transfer(from = richAcc, to = secondAcc.toAddress, amount = 1L, asset = Waves, fee = fee, feeAsset = Waves, attachment = ByteStr.empty, timestamp = ts)
          invalidTransfer = TxHelpers.transfer(from = secondAcc, to = richAcc.toAddress, amount = 2L, asset = Waves, fee = fee, feeAsset = Waves, attachment = ByteStr.empty, timestamp = ts)
        } yield (richAcc, validTransfer, invalidTransfer)

        forAll(preconditions) { case (richAcc, validTransfer, invalidTransfer) =>
          // The rich account is credited by the genesis snapshot, which the domain applies as its own block
          withDomain(balances = Seq(AddrWithBalance(richAcc.toAddress, ENOUGH_AMT))) { d =>
            val time   = TestTime()
            val events = new ListBuffer[UtxEvent]
            val utxPool =
              new UtxPoolImpl(
                time,
                d.blockchainUpdater,
                WavesSettings.default().utxSettings,
                WavesSettings.default().maxTxErrorLogSize,
                isMiningEnabled = true,
                events += _
              )

            def assertEvents(f: PartialFunction[Seq[UtxEvent], Unit]): Unit = {
              val currentEvents = events.toList
              f(currentEvents)
              events.clear()
            }

            def addUnverified(tx: Transaction): Unit = {
              utxPool.addTransaction(tx, verify = false)
            }

            val differ = TransactionDiffer(d.blockchainUpdater.lastBlockTimestamp, System.currentTimeMillis(), verify = false)(
              d.blockchainUpdater,
              _: Transaction
            ).resultE.explicitGet()
            val validTransferDiff = differ(validTransfer)
            addUnverified(validTransfer)
            addUnverified(invalidTransfer)
            assertEvents { case UtxEvent.TxAdded(`validTransfer`, `validTransferDiff`) +: Nil => // Pass
            }

            utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None, PackStrategy.Unlimited)
            assertEvents { case UtxEvent.TxRemoved(`invalidTransfer`, Some(_)) +: Nil => // Pass
            }

            utxPool.removeAll(Seq(validTransfer))
            assertEvents { case UtxEvent.TxRemoved(`validTransfer`, None) +: Nil => // Pass
            }

            addUnverified(validTransfer)
            events.clear()
            time.advance(maxAge + 1000.millis)
            utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None, PackStrategy.Unlimited)
            assertEvents { case UtxEvent.TxRemoved(`validTransfer`, Some(GenericError("Expired"))) +: Nil => // Pass
            }
          }
        }
      }
    }
  }

  private def limitByNumber(n: Int): MultiDimensionalMiningConstraint = MultiDimensionalMiningConstraint(
    OneDimensionalMiningConstraint(n, TxEstimators.one, "one"),
    OneDimensionalMiningConstraint(n, TxEstimators.one, "one")
  )

}
