package tech.hearth.state.snapshot

import tech.hearth.utils.randomBytes
import tech.hearth.TestValues.fee
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.*
import tech.hearth.state.TxMeta.Status.{Failed, Succeeded}
import tech.hearth.state.diffs.BlockDiffer.CurrentBlockFeePart
import tech.hearth.state.diffs.ENOUGH_AMT
import tech.hearth.test.DomainPresets.*
import tech.hearth.test.{NumericExt, PropSpec}
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.TxHelpers.*
import tech.hearth.transaction.assets.exchange.OrderType.{BUY, SELL}
import tech.hearth.transaction.{Transaction, TxHelpers}

import scala.collection.immutable.VectorMap
import scala.math.pow

class StateSnapshotStorageTest extends PropSpec with WithDomain {
  property("transaction snapshot storage") {
    val asset = IssuedAsset(ByteStr(randomBytes()))
    // The sender used to be credited by a genesis transaction, which the genesis snapshot replaces
    withDomain(
      RideV6.configure(_.copy(daoAddress = None)),
      Seq(AddrWithBalance(defaultAddress, ENOUGH_AMT, Map(asset -> 1000000000)), AddrWithBalance(secondAddress, ENOUGH_AMT)),
      assets = Seq(GenesisAssetSettings(asset.id, "AAAA", 8, 1000000000, fee))
    ) { d =>
      val sender           = secondSigner
      val senderAddress    = secondAddress
      val recipientSigner  = TxHelpers.signer(2)
      val recipient        = recipientSigner.toAddress
      val recipientSigner2 = TxHelpers.signer(3)
      val recipient2       = recipientSigner2.toAddress
      val reward           = d.blockchain.settings.rewardsSettings.initialReward

      def assertSnapshot(tx: Transaction, expected: StateSnapshot, failed: Boolean = false): Unit = {
        val expectedSnapshotWithMiner =
          expected
            .addBalances(
              Map(
                defaultAddress -> Portfolio.hearth(CurrentBlockFeePart(tx.fee) + reward + d.carryFee(d.lastBlockId).map(_.hearthAmount).getOrElse(0L))
              )
                .filter(_ => tx.fee != 0),
              d.blockchain
            )
            .explicitGet()
        if (failed) d.appendAndAssertFailed(tx) else d.appendAndAssertSucceed(tx)
        d.appendBlock()
        val status = if (failed) Failed else Succeeded
        d.rocksDBWriter.transactionSnapshot(tx.id()).get shouldBe (expectedSnapshotWithMiner, status)
      }

      // Transfer
      assertSnapshot(
        transfer(sender, recipient),
        StateSnapshot(
          balances = VectorMap(
            (recipient, Hearth)     -> 1.hearth,
            (senderAddress, Hearth) -> (d.balance(senderAddress) - 1.hearth - fee)
          )
        )
      )

      // Exchange
      d.appendBlock(
        transfer(to = recipient2, amount = 1.hearth),
        transfer(to = senderAddress, amount = 1.hearth, asset = asset),
        transfer(from = sender, to = recipient2, amount = 1.hearth, asset = asset, fee = fee)
      )
      val order1         = order(BUY, asset, Hearth, matcher = sender, sender = recipientSigner, amount = 123, price = 40_000_000, fee = 777)
      val order2         = order(SELL, asset, Hearth, matcher = sender, sender = recipientSigner2, amount = 123, price = 40_000_000, fee = 888)
      val priceAssetDiff = ((order1.amount.value * order1.price.value) / pow(10, 8)).toLong
      assertSnapshot(
        exchange(order1, order2, sender, amount = 123, price = 40_000_000, buyMatcherFee = 777, sellMatcherFee = 888),
        StateSnapshot(
          balances = VectorMap(
            (senderAddress, Hearth) -> (d.balance(senderAddress) - fee + order1.matcherFee.value + order2.matcherFee.value),
            (recipient, asset)      -> (d.balance(recipient, asset) + order1.amount.value),
            (recipient, Hearth)     -> (d.balance(recipient) - order1.matcherFee.value - priceAssetDiff),
            (recipient2, asset)     -> (d.balance(recipient2, asset) - order1.amount.value),
            (recipient2, Hearth)    -> (d.balance(recipient2) - order2.matcherFee.value + priceAssetDiff)
          ),
          orderFills = Map(
            order1.id() -> VolumeAndFee(order1.amount.value, order1.matcherFee.value),
            order2.id() -> VolumeAndFee(order2.amount.value, order2.matcherFee.value)
          )
        )
      )

      // Lease
      val leaseTx = lease(sender, recipient, fee = fee)
      assertSnapshot(
        leaseTx,
        StateSnapshot(
          balances = VectorMap(
            (senderAddress, Hearth) -> (d.balance(senderAddress) - fee)
          ),
          leaseBalances = Map(
            senderAddress -> LeaseBalance(0, leaseTx.amount.value),
            recipient     -> LeaseBalance(leaseTx.amount.value, 0)
          ),
          newLeases = Map(
            leaseTx.id() -> LeaseStaticInfo(leaseTx.sender, recipient, leaseTx.amount, TransactionId(leaseTx.id()), Height(d.blockchain.height + 1))
          )
        )
      )

      // Lease cancel
      val leaseCancelTx = leaseCancel(leaseTx.id(), sender, fee = fee)
      assertSnapshot(
        leaseCancelTx,
        StateSnapshot(
          balances = VectorMap(
            (senderAddress, Hearth) -> (d.balance(senderAddress) - fee)
          ),
          leaseBalances = Map(
            senderAddress -> LeaseBalance(0, 0),
            recipient     -> LeaseBalance(0, 0)
          ),
          cancelledLeases = Map(
            leaseTx.id() -> LeaseDetails.Status.Cancelled(Height(d.blockchain.height + 1), Some(TransactionId(leaseCancelTx.id())))
          )
        )
      )

      // Mass transfer
      assertSnapshot(
        massTransfer(
          sender,
          fee = fee,
          to = Seq(
            TxHelpers.signer(4).toAddress -> 123,
            TxHelpers.signer(5).toAddress -> 456
          )
        ),
        StateSnapshot(
          balances = VectorMap(
            (senderAddress, Hearth)                 -> (d.balance(senderAddress) - fee - 123 - 456),
            (TxHelpers.signer(4).toAddress, Hearth) -> 123,
            (TxHelpers.signer(5).toAddress, Hearth) -> 456
          )
        )
      )
    }
  }
}
