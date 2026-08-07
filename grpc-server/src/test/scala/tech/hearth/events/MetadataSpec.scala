package tech.hearth.events

import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.events.FakeObserver.*
import tech.hearth.events.api.grpc.protobuf.SubscribeRequest
import tech.hearth.events.protobuf.TransactionMetadata
import tech.hearth.protobuf.*
import tech.hearth.common.utils.Base16
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.RideV6
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.assets.exchange.OrderType
import tech.hearth.transaction.TxHelpers

class MetadataSpec extends FreeSpec with WithBUDomain {
  "BlockchainUpdates returns correct metadata for supported transaction types" in {
    val issuer  = TxHelpers.signer(50)
    val matcher = TxHelpers.signer(200)
    val leased  = TxHelpers.signer(201)

    val asset = IssuedAsset(ByteStr.fill(32)(1))

    withDomainAndRepo(
      RideV6,
      balances = Seq(
        AddrWithBalance(issuer.toAddress, 100.waves, Map(asset -> 1000L)),
        AddrWithBalance(matcher.toAddress, 100.waves),
        AddrWithBalance(leased.toAddress, 100.waves)
      ),
      assets = Seq(GenesisAssetSettings(asset.id, Base16.encode(issuer.publicKey()), "asset", 8, 1000L, 100000L))
    ) { (d, r) =>
      val transfer = TxHelpers.transfer(issuer, matcher.toAddress, 1.waves)
      val lease    = TxHelpers.lease(issuer, leased.toAddress, 1.waves)

      val order1   = TxHelpers.order(OrderType.SELL, asset, Waves, amount = 100L, price = 1.waves, sender = issuer, matcher = matcher)
      val order2   = TxHelpers.order(OrderType.BUY, asset, Waves, amount = 100L, price = 1.waves, sender = matcher, matcher = matcher)
      val exchange = TxHelpers.exchange(order1, order2, matcher, amount = 100L, price = 1.waves)

      val massTransfer = TxHelpers.massTransfer(issuer, Seq(matcher.toAddress -> 1L, leased.toAddress -> 1L), asset = asset)

      d.appendBlock(transfer, lease, exchange, massTransfer)

      val txMetadata = r
        .createFakeObserver(SubscribeRequest.of(1, 2))
        .fetchAllEvents(d.blockchain, 2)
        .map(_.getUpdate.getAppend.transactionsMetadata)

      txMetadata shouldEqual Seq(
        Seq(
          TransactionMetadata(
            issuer.toAddress.toByteString,
            TransactionMetadata.Metadata.Transfer(TransactionMetadata.TransferMetadata(matcher.toAddress.toByteString))
          ),
          TransactionMetadata(
            issuer.toAddress.toByteString,
            TransactionMetadata.Metadata.Lease(TransactionMetadata.LeaseMetadata(leased.toAddress.toByteString))
          ),
          TransactionMetadata(
            matcher.toAddress.toByteString,
            TransactionMetadata.Metadata.Exchange(
              TransactionMetadata.ExchangeMetadata(
                Seq(exchange.order1.id().toByteString, exchange.order2.id().toByteString),
                Seq(exchange.order1.senderAddress.toByteString, exchange.order2.senderAddress.toByteString),
                Seq(exchange.order1.senderPublicKey.toByteString, exchange.order2.senderPublicKey.toByteString)
              )
            )
          ),
          TransactionMetadata(
            issuer.toAddress.toByteString,
            TransactionMetadata.Metadata.MassTransfer(
              TransactionMetadata.MassTransferMetadata(Seq(matcher.toAddress, leased.toAddress).map(_.toByteString))
            )
          )
        )
      )
    }
  }

}
