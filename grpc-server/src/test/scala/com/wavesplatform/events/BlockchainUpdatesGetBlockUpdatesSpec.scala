package com.wavesplatform.events

import com.wavesplatform.TestValues.fee
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.events.api.grpc.protobuf.GetBlockUpdateResponse
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.assets.exchange.{ExchangeTransaction, Order, OrderType}

class BlockchainUpdatesGetBlockUpdatesSpec extends BlockchainUpdatesTestBase {
  "BlockchainUpdates getBlockUpdate tests" - {
    "BU-207. Return correct data for transfer" in {
      val transferTx = TxHelpers.transfer(firstTxParticipant, secondTxParticipantAddress, amount, Waves, customFee)
      withGenerateGetBlockUpdate(
        height = 2,
        settings = currentSettings,
        balances = Seq(
          AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore),
          AddrWithBalance(secondTxParticipant.toAddress, secondTxParticipantBalanceBefore)
        )
      )(_.appendBlock(transferTx)) { getBlockUpdate =>
        val append = getBlockUpdate.getUpdate.getAppend
        checkTransferTx(append, transferTx)
      }
    }

    "Exchange transaction subscription tests" - {
      "BU-195. Return correct data for order V3, exchange V2" in {
        val order1          = createOrder(OrderType.BUY, firstTxParticipant, Order.V3)
        val order2          = createOrder(OrderType.SELL, secondTxParticipant, Order.V3)
        val normalizedPrice = order1.price.value * order1.amount.value / 100000000
        // V3 orders are always AssetDecimals-normalized: price / 10^(priceDecimals - amountDecimals), here
        // firstTokenAsset(2)/secondTokenAsset(6), a 10^4 factor - the tx's own price field must clear that
        val exchangeTx =
          TxHelpers.exchangeFromOrders(order1, order2, order1.price.value * 10000, firstTxParticipant, fee, AddressScheme.current.chainId)
        withAddedBlocksAndGetBlockUpdate(exchangeTx, height = 2) { getBlockUpdate =>
          val append = getBlockUpdate.getUpdate.getAppend
          checkExchangeTx(append, exchangeTx, normalizedPrice, order1.amount.value)
        }
      }

      "BU-223. Return correct data for order V4, exchange V3" in {
        val order1          = createOrder(OrderType.BUY, firstTxParticipant, Order.V4)
        val order2          = createOrder(OrderType.SELL, secondTxParticipant, Order.V4)
        val normalizedPrice = order1.price.value / 2 / 10000000
        val exchangeTx      = TxHelpers.exchangeFromOrders(order1, order2, firstTxParticipant)
        withAddedBlocksAndGetBlockUpdate(exchangeTx, height = 2) { getBlockUpdate =>
          val append = getBlockUpdate.getUpdate.getAppend
          checkExchangeTx(append, exchangeTx, normalizedPrice, order1.amount.value)
        }
      }
    }

    "BU-198. Return correct data for lease" in {
      val lease = TxHelpers.lease(firstTxParticipant, secondTxParticipantAddress, amount, customFee)
      withGenerateGetBlockUpdate(
        height = 2,
        settings = currentSettings,
        balances = Seq(AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore))
      )(_.appendBlock(lease)) { getBlockUpdate =>
        val append = getBlockUpdate.getUpdate.getAppend
        checkLeaseTx(append, lease)
      }
    }

    "BU-199. Return correct data for lease cancel" in {
      val lease       = TxHelpers.lease(firstTxParticipant, secondTxParticipantAddress, amount, customFee)
      val leaseCancel = TxHelpers.leaseCancel(lease.id.value(), firstTxParticipant, customFee)
      withGenerateGetBlockUpdate(
        height = 3,
        settings = currentSettings,
        balances = Seq(AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore))
      ) { d =>
        d.appendBlock(lease)
        d.appendBlock(leaseCancel)
      } { getBlockUpdate =>
        val append = getBlockUpdate.getUpdate.getAppend
        checkLeaseCancelTx(append, leaseCancel, lease)
      }
    }

    "BU-200. Return correct data for massTransfer" in {
      val massTransferFee = fee * 6
      val massTransfer =
        TxHelpers.massTransfer(firstTxParticipant, recipients.map(r => r.address -> r.amount.value), firstTokenAsset, massTransferFee)

      withGenerateGetBlockUpdate(
        height = 2,
        settings = currentSettings,
        balances = tokenBalances,
        assets = genesisAssets
      )(_.appendBlock(massTransfer)) { getBlockUpdate =>
        val append = getBlockUpdate.getUpdate.getAppend
        checkForMassTransferTx(append, massTransfer)
      }
    }

    def withAddedBlocksAndGetBlockUpdate(exchangeTx: ExchangeTransaction, height: Int)(f: GetBlockUpdateResponse => Unit): Unit = {
      withGenerateGetBlockUpdate(
        height,
        settings = currentSettings,
        balances = tokenBalances,
        assets = genesisAssets
      )(_.appendBlock(exchangeTx))(f)
    }
  }
}
