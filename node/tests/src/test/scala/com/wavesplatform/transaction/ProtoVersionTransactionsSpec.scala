package com.wavesplatform.transaction

import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base64
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions}
import com.wavesplatform.protobuf.utils.PBUtils
import com.wavesplatform.settings.Constants
import com.wavesplatform.state.Height
import com.wavesplatform.test.FreeSpec
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.assets.exchange.{ExchangeTransaction, Order}
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class ProtoVersionTransactionsSpec extends FreeSpec {

  val MinFee: Long            = (0.001 * Constants.UnitsInWave).toLong
  val DataTxFee: Long         = 15000000
  val InvokeScriptTxFee: Long = 15000000
  val MassTransferTxFee: Long = 15000000
  val SetScriptFee: Long      = (0.01 * Constants.UnitsInWave).toLong
  val SetAssetScriptFee: Long = Constants.UnitsInWave

  val Now: Long = ntpNow

  val Account: SigningKey = accountGen.sample.get

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
          .create(PublicKey(Account.publicKey), recipient, asset, 100, Asset.Waves, MinFee, ByteStr(attachment), Now, Proofs.empty)
          .map(_.signWith(Account))
          .explicitGet()

      val base64Str = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(transferTx)))

      decode(base64Str) shouldBe transferTx
    }

    "MassTransferTransaction" in {
      val transfers =
        Gen.listOfN(10, accountOrAliasGen).map(accounts => accounts.map(ParsedTransfer(_, TxNonNegativeAmount.unsafeFrom(100)))).sample.get
      val attachment = genBoundedBytes(0, TransferTransaction.MaxAttachmentSize).sample.get

      val massTransferTx =
        MassTransferTransaction
          .create(PublicKey(Account.publicKey), Asset.Waves, transfers, MassTransferTxFee, Now, ByteStr(attachment), Proofs.empty)
          .map(_.signWith(Account))
          .explicitGet()
      val base64Str = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(massTransferTx)))

      decode(base64Str) shouldBe massTransferTx
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
