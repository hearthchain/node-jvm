package tech.hearth.transaction

import com.google.protobuf.ByteString
import tech.hearth.account.{AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base64
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.protobuf.transaction.{PBSignedTransaction, PBTransactions}
import tech.hearth.protobuf.utils.PBUtils
import tech.hearth.settings.Constants
import tech.hearth.state.Height
import tech.hearth.test.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.assets.exchange.{ExchangeTransaction, Order}
import tech.hearth.transaction.lease.LeaseTransaction
import tech.hearth.transaction.Proofs
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.transfer.TransferTransaction.ParsedTransfer
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class ProtoVersionTransactionsSpec extends FreeSpec {

  val MinFee: Long            = (0.001 * Constants.UnitsInHearth).toLong
  val DataTxFee: Long         = 15000000
  val InvokeScriptTxFee: Long = 15000000
  val MassTransferTxFee: Long = 15000000
  val SetScriptFee: Long      = (0.01 * Constants.UnitsInHearth).toLong
  val SetAssetScriptFee: Long = Constants.UnitsInHearth

  val Now: Long = ntpNow

  val Account: SigningKey = accountGen.sample.get

  val TestAsset: IssuedAsset = IssuedAsset(ByteStr.fill(32)(3))

  "all txs" - {
    "ExchangeTransaction" in {
      val buyer     = accountGen.sample.get
      val seller    = accountGen.sample.get
      val assetPair = assetPairGen.sample.get

      val buyOrder =
        TxHelpers
          .buy(Order.V3, buyer, PublicKey(Account.publicKey), assetPair, Order.MaxAmount / 2, 100, Now, Now + Order.MaxLiveTime / 2, MinFee * 3)
          .explicitGet()
      val sellOrder =
        TxHelpers
          .sell(Order.V3, seller, PublicKey(Account.publicKey), assetPair, Order.MaxAmount / 2, 100, Now, Now + Order.MaxLiveTime / 2, MinFee * 3)
          .explicitGet()

      val exchangeTx =
        TxHelpers.exchange(buyOrder, sellOrder, Account, 100, 100, MinFee * 3, MinFee * 3, MinFee * 3, Now)
      val base64Str = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(exchangeTx)))

      decode(base64Str) shouldBe exchangeTx
    }

    "LeaseTransaction/LeaseCancelTransaction" in {
      val recipient = accountOrAliasGen.sample.get

      val leaseTx = LeaseTransaction
        .create(AddressScheme.current.chainId, PublicKey(Account.publicKey), recipient, 100, MinFee, Now, Proofs.empty)
        .map(_.signWith(Account))
        .explicitGet()
      val leaseCancelTx =
        TxHelpers.leaseCancel(leaseId = leaseTx.id(), sender = Account, fee = MinFee)
      val base64LeaseStr       = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(leaseTx)))
      val base64CancelLeaseStr = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(leaseCancelTx)))

      decode(base64LeaseStr) shouldBe leaseTx
      decode(base64CancelLeaseStr) shouldBe leaseCancelTx
    }

    "TransferTransaction" in {
      val recipient  = accountOrAliasGen.sample.get
      val asset      = IssuedAsset(bytes32gen.map(ByteStr(_)).sample.get)
      val attachment = genBoundedBytes(0, TransferTransaction.MaxAttachmentSize).sample.get

      val transferTx =
        TransferTransaction
          .create(
            PublicKey(Account.publicKey),
            asset,
            Seq(ParsedTransfer(recipient, TxNonNegativeAmount.unsafeFrom(100))),
            MinFee,
            Now,
            ByteStr(attachment),
            Proofs.empty
          )
          .map(_.signWith(Account))
          .explicitGet()

      val base64Str = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(transferTx)))

      decode(base64Str) shouldBe transferTx
    }

    "TransferTransaction (multiple recipients)" in {
      val transfers =
        Gen.listOfN(10, accountOrAliasGen).map(accounts => accounts.map(ParsedTransfer(_, TxNonNegativeAmount.unsafeFrom(100)))).sample.get
      val attachment = genBoundedBytes(0, TransferTransaction.MaxAttachmentSize).sample.get

      val massTransferTx =
        TransferTransaction
          .create(PublicKey(Account.publicKey), Asset.Hearth, transfers, MassTransferTxFee, Now, ByteStr(attachment), Proofs.empty)
          .map(_.signWith(Account))
          .explicitGet()
      val base64Str = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(massTransferTx)))

      decode(base64Str) shouldBe massTransferTx
    }

    "ReserveTransaction" in {
      val tx        = TxHelpers.reserve(Account, asset = TestAsset, amount = 100L, miner = Account.toAddress, fee = MinFee, timestamp = Now)
      val base64Str = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(tx)))
      decode(base64Str) shouldBe tx
    }

    "SettleTransaction" in {
      val settlements = Seq(SettleTransaction.Settlement(Account.toAddress, TestAsset, TxNonNegativeAmount.unsafeFrom(100L)))
      val tx          = TxHelpers.settle(Account, settlements = settlements, fee = MinFee, timestamp = Now)
      val base64Str   = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(tx)))
      decode(base64Str) shouldBe tx
    }

    // Hearth is neither reservable nor settleable, and an empty asset id on the wire decodes as Hearth - so a peer
    // can't get one past PBTransactions.vanilla, however the transaction was assembled (see PBTransactions
    // .issuedAsset).
    "rejects a Reserve carrying an empty asset id" in {
      val tx = TxHelpers.reserve(Account, asset = TestAsset, amount = 100L, miner = Account.toAddress, fee = MinFee, timestamp = Now)
      val hearthOnTheWire =
        PBTransactions.protobuf(tx).update(_.transaction.reserve.amount.assetId := ByteString.EMPTY)
      PBTransactions.vanilla(hearthOnTheWire) should produce("issued asset is required")
    }

    "rejects a Settle carrying an empty asset id" in {
      val settlements = Seq(SettleTransaction.Settlement(Account.toAddress, TestAsset, TxNonNegativeAmount.unsafeFrom(100L)))
      val tx          = TxHelpers.settle(Account, settlements = settlements, fee = MinFee, timestamp = Now)
      val hearthOnTheWire =
        PBTransactions.protobuf(tx).update(_.transaction.settle.settlements.foreach(_.cumulativeSpent.assetId := ByteString.EMPTY))
      PBTransactions.vanilla(hearthOnTheWire) should produce("issued asset is required")
    }

    "CommitToGenerationTransaction" in {
      val tx        = TxHelpers.commitToGeneration(Height(3001), Account, Now, MinFee)
      val base64Str = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(tx)))
      decode(base64Str) shouldBe tx
    }

    def decode(base64Str: String): Transaction = {
      PBTransactions.vanilla(PBSignedTransaction.parseFrom(Base64.decode(base64Str))).explicitGet()
    }
  }
}
