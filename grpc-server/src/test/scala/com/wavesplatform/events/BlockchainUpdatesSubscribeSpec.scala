package com.wavesplatform.events

import com.wavesplatform.TestValues.fee
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.events.protobuf.BlockchainUpdated as PBBlockchainUpdated
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.assets.exchange.{ExchangeTransaction, Order, OrderType}

class BlockchainUpdatesSubscribeSpec extends BlockchainUpdatesTestBase {
  "BlockchainUpdates subscribe tests" - {
    "BU-28. Return correct data for transfer" in {
      val transferTx = TxHelpers.transfer(firstTxParticipant, secondTxParticipantAddress, amount, Waves, customFee)
      withGenerateSubscription(
        settings = currentSettings,
        balances = Seq(
          AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore),
          AddrWithBalance(secondTxParticipant.toAddress, secondTxParticipantBalanceBefore)
        )
      )(_.appendBlock(transferTx)) { updates =>
        val append = updates(0).append
        checkTransferTx(append, transferTx)
      }
    }

    "Exchange transaction subscription tests" - {
      "BU-6. Return correct data for order V3, exchange V2" in {
        val order1          = createOrder(OrderType.BUY, firstTxParticipant, Order.V3)
        val order2          = createOrder(OrderType.SELL, secondTxParticipant, Order.V3)
        val normalizedPrice = order1.price.value * order1.amount.value / 100000000
        // V3 orders are always AssetDecimals-normalized: price / 10^(priceDecimals - amountDecimals), here
        // firstTokenAsset(2)/secondTokenAsset(6), a 10^4 factor - the tx's own price field must clear that
        val exchangeTx =
          TxHelpers.exchangeFromOrders(order1, order2, order1.price.value * 10000, firstTxParticipant, fee, AddressScheme.current.chainId)
        withAddedBlocksAndSubscribeExchangeTx(exchangeTx) { updated =>
          val append = updated.apply(0).getAppend
          checkExchangeTx(append, exchangeTx, normalizedPrice, order1.amount.value)
        }
      }

      "BU-120. Return correct data for order V4, exchange V3" in {
        val order1          = createOrder(OrderType.BUY, firstTxParticipant, Order.V4)
        val order2          = createOrder(OrderType.SELL, secondTxParticipant, Order.V4)
        val normalizedPrice = order1.price.value / 2 / 10000000
        val exchangeTx      = TxHelpers.exchangeFromOrders(order1, order2, firstTxParticipant)
        withAddedBlocksAndSubscribeExchangeTx(exchangeTx) { updated =>
          val append = updated.apply(0).getAppend
          checkExchangeTx(append, exchangeTx, normalizedPrice, order1.amount.value)
        }
      }
    }

    "BU-12. Return correct data for lease" in {
      val lease = TxHelpers.lease(firstTxParticipant, secondTxParticipantAddress, amount, customFee)
      withGenerateSubscription(
        settings = currentSettings,
        balances = Seq(AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore))
      )(_.appendBlock(lease)) { updates =>
        val append = updates(0).append
        checkLeaseTx(append, lease)
      }
    }

    "BU-14. Return correct data for lease cancel" in {
      val lease       = TxHelpers.lease(firstTxParticipant, secondTxParticipantAddress, amount, customFee)
      val leaseCancel = TxHelpers.leaseCancel(lease.id.value(), firstTxParticipant, customFee)
      withGenerateSubscription(
        settings = currentSettings,
        balances = Seq(AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore))
      ) { d =>
        d.appendBlock(lease)
        d.appendMicroBlock(leaseCancel)
      } { updates =>
        val append = updates(1).append
        checkLeaseCancelTx(append, leaseCancel, lease)
      }
    }

    "BU-16. Return correct data for massTransfer" in {
      val massTransferFee = fee * 6
      val massTransfer =
        TxHelpers.massTransfer(firstTxParticipant, recipients.map(r => r.address -> r.amount.value), firstTokenAsset, massTransferFee)

      withGenerateSubscription(
        settings = currentSettings,
        balances = tokenBalances,
        assets = genesisAssets
      )(_.appendBlock(massTransfer)) { updates =>
        val append = updates(0).append
        checkForMassTransferTx(append, massTransfer)
      }
    }

    def withAddedBlocksAndSubscribeExchangeTx(exchangeTx: ExchangeTransaction)(f: Seq[PBBlockchainUpdated] => Unit): Unit = {
      withGenerateSubscription(
        settings = currentSettings,
        balances = tokenBalances,
        assets = genesisAssets
      )(_.appendBlock(exchangeTx))(f)
    }
  }
}
