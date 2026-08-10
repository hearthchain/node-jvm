package tech.hearth.state

import tech.hearth.TestValues
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.api.common.LeaseInfo
import tech.hearth.api.common.LeaseInfo.Status.Active
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.defaultSigner
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.{Height, TransactionId}
import tech.hearth.test.*
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxHelpers.*
import tech.hearth.transaction.{Transaction, TxHelpers}
import monix.execution.Scheduler.Implicits.global
import tech.hearth.crypto.SigningKey

class RollbackSpec extends FreeSpec with WithDomain {
  private val time   = new TestTime
  private def nextTs = time.getTimestamp()

  private def randomOp(sender: SigningKey, recipient: Address, amount: Long, op: Int, nextTs: => Long = nextTs) = {
    op match {
      case 1 =>
        val lease       = TxHelpers.lease(sender, recipient, amount, fee = 100000L, timestamp = nextTs)
        val cancelLease = TxHelpers.leaseCancel(lease.id(), sender, fee = 1, timestamp = nextTs)
        List(lease, cancelLease)
      case 2 =>
        List(
          TxHelpers.massTransfer(
            sender,
            Seq(
              recipient -> amount,
              recipient -> amount
            ),
            fee = 10000,
            timestamp = nextTs
          )
        )
      case _ =>
        List(TxHelpers.transfer(sender, recipient, amount, fee = 1000, timestamp = nextTs))
    }
  }

  "NODE-1143, NODE-1144. Rollback resets" - {
    "Rollback save dropped blocks order" in {
      val sender         = TxHelpers.signer(1)
      val initialBalance = 100.waves
      val blocksCount    = 10
      withDomain(balances = Seq(AddrWithBalance(sender.toAddress, initialBalance))) { d =>
        val genesisSignature = d.lastBlockId

        def newBlocks(i: Int): List[ByteStr] = {
          if (i == blocksCount) {
            Nil
          } else {
            val block = d.createBlock()
            d.appendBlock(block)
            block.id() :: newBlocks(i + 1)
          }
        }

        val blocks        = newBlocks(0)
        val droppedBlocks = d.rollbackTo(genesisSignature).map(_._1)
        droppedBlocks(0).header.reference shouldBe genesisSignature
        droppedBlocks.map(_.id()).toList shouldBe blocks
        droppedBlocks.foreach { block =>
          d.appendBlockE(block) should beRight
        }
      }
    }

    "forget rollbacked transaction for querying" in {
      val sender    = TxHelpers.signer(1)
      val recipient = TxHelpers.signer(2)
      val txCount   = (1 to 10).toList
      withDomain(balances = Seq(AddrWithBalance(sender.toAddress))) { d =>
        val genesisSignature = d.lastBlockId

        val transferAmount = 100

        val transfers = txCount.map(tc => Seq.fill(tc)(randomOp(sender, recipient.toAddress, transferAmount, tc % 3)).flatten)

        for (transfer <- transfers) d.appendBlock(transfer*)

        val stransactions1 = d.addressTransactions(sender.toAddress).sortBy(_._2.timestamp)
        val rtransactions1 = d.addressTransactions(recipient.toAddress).sortBy(_._2.timestamp)

        d.rollbackTo(genesisSignature)

        for (transfer <- transfers) d.appendBlock(transfer*)

        val stransactions2 = d.addressTransactions(sender.toAddress).sortBy(_._2.timestamp)
        val rtransactions2 = d.addressTransactions(recipient.toAddress).sortBy(_._2.timestamp)

        stransactions1 shouldBe stransactions2
        rtransactions1 shouldBe rtransactions2
      }
    }

    "waves balances" in {
      val sender         = TxHelpers.signer(1)
      val recipient      = TxHelpers.signer(2)
      val txCount        = (1 to 10).toList
      val initialBalance = 100.waves
      val fee            = 1
      withDomain(balances = Seq(AddrWithBalance(sender.toAddress, initialBalance))) { d =>
        val genesisSignature = d.lastBlockId

        d.balance(sender.toAddress) shouldBe initialBalance
        d.balance(recipient.toAddress) shouldBe 0

        val totalTxCount   = txCount.sum
        val transferAmount = initialBalance / (totalTxCount * 2)

        for (tc <- txCount) {
          d.appendBlock(Seq.fill(tc)(TxHelpers.transfer(sender, recipient.toAddress, transferAmount, fee = fee))*)
        }

        d.balance(recipient.toAddress) shouldBe (transferAmount * totalTxCount)
        d.balance(sender.toAddress) shouldBe (initialBalance - (transferAmount + fee) * totalTxCount)

        d.rollbackTo(genesisSignature)

        d.balance(sender.toAddress) shouldBe initialBalance
        d.balance(recipient.toAddress) shouldBe 0
      }
    }

    "lease balances and states" in {
      val sender         = TxHelpers.signer(1)
      val recipient      = TxHelpers.signer(2)
      val initialBalance = 100.waves
      withDomain(balances = Seq(AddrWithBalance(sender.toAddress, initialBalance))) { d =>
        d.blockchainUpdater.height shouldBe 1
        val genesisBlockId = d.lastBlockId

        // Nearly everything, less a waves for the fee: it comes out of the same balance and cannot be leased away with it
        val leaseAmount = initialBalance - 1.waves
        val lt          = TxHelpers.lease(sender, recipient.toAddress, leaseAmount)
        d.appendBlock(lt)
        d.blockchainUpdater.height shouldBe 2
        val blockWithLeaseId = d.lastBlockId
        d.blockchainUpdater.leaseDetails(lt.id()) should contain(
          LeaseDetails(
            LeaseStaticInfo(PublicKey(sender.publicKey), recipient.toAddress, lt.amount, TransactionId(lt.id()), Height(2)),
            LeaseDetails.Status.Active
          )
        )
        d.blockchainUpdater.leaseBalance(sender.toAddress).out shouldEqual leaseAmount
        d.blockchainUpdater.leaseBalance(recipient.toAddress).in shouldEqual leaseAmount

        val leaseCancel = TxHelpers.leaseCancel(lt.id(), sender)
        d.appendBlock(leaseCancel)
        d.blockchainUpdater.leaseDetails(lt.id()) should contain(
          LeaseDetails(
            LeaseStaticInfo(PublicKey(sender.publicKey), recipient.toAddress, lt.amount, TransactionId(lt.id()), Height(2)),
            LeaseDetails.Status.Cancelled(Height(d.blockchain.height), Some(TransactionId(leaseCancel.id())))
          )
        )
        d.blockchainUpdater.leaseBalance(sender.toAddress).out shouldEqual 0
        d.blockchainUpdater.leaseBalance(recipient.toAddress).in shouldEqual 0

        d.rollbackTo(blockWithLeaseId)
        d.blockchainUpdater.leaseDetails(lt.id()) should contain(
          LeaseDetails(
            LeaseStaticInfo(PublicKey(sender.publicKey), recipient.toAddress, lt.amount, TransactionId(lt.id()), Height(2)),
            LeaseDetails.Status.Active
          )
        )
        d.blockchainUpdater.leaseBalance(sender.toAddress).out shouldEqual leaseAmount
        d.blockchainUpdater.leaseBalance(recipient.toAddress).in shouldEqual leaseAmount

        d.rollbackTo(genesisBlockId)
        d.blockchainUpdater.leaseDetails(lt.id()) shouldBe empty
        d.blockchainUpdater.leaseBalance(sender.toAddress).out shouldEqual 0
        d.blockchainUpdater.leaseBalance(recipient.toAddress).in shouldEqual 0
      }
    }

    "asset balances" in {
      val sender         = TxHelpers.signer(1)
      val recipient      = TxHelpers.signer(2)
      val initialBalance = 100.waves
      val assetAmount    = 100L
      // Nothing issues this asset, so it comes from the genesis snapshot - which has to hand out the whole quantity,
      // hence all of it to the sender
      val issuedAsset = IssuedAsset(ByteStr(new Array[Byte](32)))
      withDomain(
        balances = Seq(AddrWithBalance(sender.toAddress, initialBalance, Map(issuedAsset -> assetAmount))),
        assets = Seq(
          GenesisAssetSettings(
            id = issuedAsset.id,
            name = "Rollback",
            decimals = 2,
            quantity = assetAmount,
            minFee = TestValues.fee
          )
        )
      ) { d =>
        val genesisBlockId = d.lastBlockId

        d.appendBlock(
          TxHelpers.transfer(
            from = sender,
            to = recipient.toAddress,
            amount = assetAmount,
            asset = issuedAsset,
            fee = 1,
            feeAsset = Waves
          )
        )

        d.balance(sender.toAddress, issuedAsset) shouldEqual 0
        d.balance(recipient.toAddress, issuedAsset) shouldEqual assetAmount

        d.rollbackTo(genesisBlockId)

        d.balance(sender.toAddress, issuedAsset) shouldEqual assetAmount
        d.balance(recipient.toAddress, issuedAsset) shouldEqual 0
      }
    }

    "relearn rollbacked transaction" in {
      val sender    = TxHelpers.signer(1)
      val recipient = TxHelpers.signer(2)
      val txCount   = (1 to 66).map(_ % 10 + 1).toList
      withDomain(balances = Seq(AddrWithBalance(sender.toAddress))) { d =>
        val transferAmount = 100

        val transfers = txCount.map(tc => Range(0, tc).flatMap(_ => randomOp(sender, recipient.toAddress, transferAmount, tc % 3)))

        val blocks = for (transfer <- transfers) yield {
          d.appendBlock(transfer*)
          d.lastBlockId
        }

        val middleBlock = blocks(txCount.size / 2)

        d.rollbackTo(middleBlock)

        // The transactions of the very first block are still in the state, so a block replaying them cannot be appended
        d.appendBlockE(transfers.head*) should produce("AlreadyInTheState")
      }
    }

    "active leases by address" in {
      withDomain(DomainPresets.RideV6, AddrWithBalance.enoughBalances(defaultSigner, secondSigner)) { d =>
        def leases(address: Address) = d.accountsApi.activeLeases(address).toListL.runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))

        val leaseTxs = Seq.fill(5)(lease(defaultSigner, secondAddress)) ++ Seq.fill(5)(lease(secondSigner, defaultAddress))
        val info = leaseTxs.map { tx =>
          LeaseInfo(tx.id(), TransactionId(tx.id()), tx.sender.toAddress, tx.recipient.asInstanceOf[Address], tx.amount.value, Height(2), Active)
        }

        val b1 = d.appendBlock(leaseTxs*)
        leases(defaultAddress) should contain theSameElementsAs info
        leases(secondAddress) should contain theSameElementsAs info

        val b2 = d.appendBlock(leaseCancel(leaseTxs.head.id()))
        leases(defaultAddress) should contain theSameElementsAs info.tail
        leases(secondAddress) should contain theSameElementsAs info.tail

        d.appendMicroBlock(leaseCancel(leaseTxs.last.id(), secondSigner))
        leases(defaultAddress) should contain theSameElementsAs info.slice(1, 9)
        leases(secondAddress) should contain theSameElementsAs info.slice(1, 9)

        d.appendBlock(d.createBlock(ref = Some(b2.id())))
        leases(defaultAddress) should contain theSameElementsAs info.tail
        leases(secondAddress) should contain theSameElementsAs info.tail

        d.appendBlock(transfer(defaultSigner, secondAddress), transfer(secondSigner, defaultAddress))
        // to check that rolling back this block will not affect active leases from previous block

        d.rollbackTo(b1.id())
        leases(defaultAddress) should contain theSameElementsAs info
        leases(secondAddress) should contain theSameElementsAs info

        d.rollbackTo(1)
        leases(defaultAddress) shouldBe empty
        leases(secondAddress) shouldBe empty
      }
    }
  }
}
