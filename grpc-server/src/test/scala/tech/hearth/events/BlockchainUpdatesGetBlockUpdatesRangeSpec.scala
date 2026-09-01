package tech.hearth.events

import tech.hearth.TestValues.fee
import tech.hearth.account.NetworkId
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.events.api.grpc.protobuf.GetBlockUpdatesRangeRequest
import tech.hearth.events.protobuf.BlockchainUpdated as PBBlockchainUpdated
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.assets.exchange.{ExchangeTransaction, Order, OrderType}

class BlockchainUpdatesGetBlockUpdatesRangeSpec extends BlockchainUpdatesTestBase {
  "BlockchainUpdates getBlockUpdateRange tests" - {
    "BU-172. Return correct data for transfer" in {
      val transferTx = TxHelpers.transfer(firstTxParticipant, secondTxParticipantAddress, amount, Hearth, customFee)
      withGenerateGetBlockUpdateRange(
        GetBlockUpdatesRangeRequest.of(1, 2),
        settings = currentSettings,
        balances = Seq(
          AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore),
          AddrWithBalance(secondTxParticipant.toAddress, secondTxParticipantBalanceBefore)
        )
      ) { d =>
        d.appendBlock(transferTx)
        d.appendBlock()
      } { getBlockUpdateRange =>
        val append = getBlockUpdateRange.apply(1).getAppend
        checkTransferTx(append, transferTx)
      }
    }

    "Exchange transaction subscription tests" - {
      "BU-160. Return correct data for order V3, exchange V2" in {
        val order1          = createOrder(OrderType.BUY, firstTxParticipant, Order.V3)
        val order2          = createOrder(OrderType.SELL, secondTxParticipant, Order.V3)
        val normalizedPrice = order1.price.value * order1.amount.value / 100000000
        // V3 orders are always AssetDecimals-normalized: price / 10^(priceDecimals - amountDecimals), here
        // firstTokenAsset(2)/secondTokenAsset(6), a 10^4 factor - the tx's own price field must clear that
        val exchangeTx =
          TxHelpers.exchangeFromOrders(order1, order2, order1.price.value * 10000, firstTxParticipant, fee, NetworkId.current)
        withAddedBlocksAndGetBlockUpdateRange(exchangeTx, GetBlockUpdatesRangeRequest.of(1, 2)) { getBlockUpdateRange =>
          val append = getBlockUpdateRange.apply(1).getAppend
          checkExchangeTx(append, exchangeTx, normalizedPrice, order1.amount.value)
        }
      }

      "BU-188. Return correct data for order V4, exchange V3" in {
        val order1          = createOrder(OrderType.BUY, firstTxParticipant, Order.V4)
        val order2          = createOrder(OrderType.SELL, secondTxParticipant, Order.V4)
        val normalizedPrice = order1.price.value / 2 / 10000000
        val exchangeTx      = TxHelpers.exchangeFromOrders(order1, order2, firstTxParticipant)
        withAddedBlocksAndGetBlockUpdateRange(exchangeTx, GetBlockUpdatesRangeRequest.of(1, 2)) { getBlockUpdateRange =>
          val append = getBlockUpdateRange.apply(1).getAppend
          checkExchangeTx(append, exchangeTx, normalizedPrice, order1.amount.value)
        }
      }
    }

    "BU-163. Return correct data for lease" in {
      val lease = TxHelpers.lease(firstTxParticipant, secondTxParticipantAddress, amount, customFee)
      withGenerateGetBlockUpdateRange(
        GetBlockUpdatesRangeRequest.of(1, 2),
        settings = currentSettings,
        balances = Seq(AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore))
      ) { d =>
        d.appendBlock(lease)
        d.appendBlock()
      } { getBlockUpdateRange =>
        val append = getBlockUpdateRange.apply(1).getAppend
        checkLeaseTx(append, lease)
      }
    }

    "BU-164. Return correct data for lease cancel" in {
      val lease       = TxHelpers.lease(firstTxParticipant, secondTxParticipantAddress, amount, customFee)
      val leaseCancel = TxHelpers.leaseCancel(lease.id.value(), firstTxParticipant, customFee)
      withGenerateGetBlockUpdateRange(
        GetBlockUpdatesRangeRequest.of(1, 3),
        settings = currentSettings,
        balances = Seq(AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore))
      ) { d =>
        d.appendBlock(lease)
        d.appendBlock(leaseCancel)
        d.appendBlock()
      } { getBlockUpdateRange =>
        val append = getBlockUpdateRange.apply(2).getAppend
        checkLeaseCancelTx(append, leaseCancel, lease)
      }
    }

    "BU-165. Return correct data for massTransfer" in {
      val massTransferFee = fee * 6
      val massTransfer =
        TxHelpers.massTransfer(firstTxParticipant, recipients.map(v => v.address -> v.amount.value), firstTokenAsset, massTransferFee)
      withGenerateGetBlockUpdateRange(
        GetBlockUpdatesRangeRequest.of(1, 2),
        settings = currentSettings,
        balances = tokenBalances,
        assets = genesisAssets
      ) { d =>
        d.appendBlock(massTransfer)
        d.appendBlock()
      } { getBlockUpdateRange =>
        val append = getBlockUpdateRange.apply(1).getAppend
        checkForMassTransferTx(append, massTransfer)
      }
    }

    def withAddedBlocksAndGetBlockUpdateRange(exchangeTx: ExchangeTransaction, height: GetBlockUpdatesRangeRequest)(
        f: Seq[PBBlockchainUpdated] => Unit
    ): Unit = {
      withGenerateGetBlockUpdateRange(
        height,
        settings = currentSettings,
        balances = tokenBalances,
        assets = genesisAssets
      ) { d =>
        d.appendBlock(exchangeTx)
        d.appendBlock()
      }(f)
    }
  }
}
