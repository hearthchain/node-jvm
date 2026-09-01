package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.protobuf.transaction.{PBTransactions, SignedTransaction as PBSignedTransaction}
import tech.hearth.state.Height
import tech.hearth.test.PropSpec
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.assets.exchange.{AssetPair, ExchangeTransaction, Order}
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.transfer.TransferTransaction.ParsedTransfer
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class NetworkIdSpecification extends PropSpec {
  // Tests run on NetworkId.Testnet (BaseSuite.configureDefaultNetwork), so mainnet is somebody else's network.
  private val otherNetworkId = NetworkId.Mainnet

  private def txParams: Gen[(SigningKey, TxPositiveAmount, TxPositiveAmount, TxTimestamp)] =
    for {
      sender <- accountGen
      amount <- Gen.choose(1L, 10000000L)
      fee    <- Gen.choose(1000000L, 10000000L)
      ts     <- Gen.choose(1L, 1000000L)
    } yield (sender, TxPositiveAmount.unsafeFrom(amount), TxPositiveAmount.unsafeFrom(fee), ts)

  private def validateFromOtherNetwork(tx: Transaction): Unit = {
    tx.networkId should not be NetworkId.current

    val protoTx       = PBTransactions.protobuf(tx)
    val recoveredTxEi = PBTransactions.vanilla(PBSignedTransaction.parseFrom(protoTx.toByteArray))

    recoveredTxEi.explicitGet()

    val recoveredTx = recoveredTxEi.explicitGet().asInstanceOf[ProvenTransaction]

    recoveredTx shouldBe tx
    recoveredTx.firstProofIsValidSignatureAfterV6.explicitGet()
  }

  property("TransferTransaction validation") {
    forAll(txParams) { case (sender, amount, fee, ts) =>
      validateFromOtherNetwork(
        TransferTransaction(
          PublicKey(sender.publicKey),
          Hearth,
          Seq(ParsedTransfer(sender.toAddress, TxNonNegativeAmount.unsafeFrom(amount.value))),
          fee,
          Hearth,
          ts,
          ByteStr.empty,
          Proofs.empty,
          otherNetworkId
        ).signWith(sender).validatedEither.explicitGet()
      )
    }
  }

  property("LeaseTransaction validation") {
    // An address does not carry the network it belongs to any more, so the sender's own address is its own address on
    // every network - and leasing to it is a self-lease. Lease to somebody else instead.
    forAll(txParams, accountGen) { case ((sender, amount, fee, ts), recipient) =>
      validateFromOtherNetwork(
        LeaseTransaction
          .create(otherNetworkId, PublicKey(sender.publicKey), recipient.toAddress, amount.value, fee.value, ts, Proofs.empty)
          .explicitGet()
          .signWith(sender)
      )
    }
  }

  property("ExchangeTransaction validation") {
    forAll(txParams) { case (sender, amount, fee, ts) =>
      val pair = AssetPair(Hearth, IssuedAsset(ByteStr(bytes32gen.sample.get)))
      validateFromOtherNetwork(
        ExchangeTransaction(
          TxHelpers.sell(Order.V3, sender, PublicKey(sender.publicKey), pair, amount.value, amount.value, ts, ts + ts, fee.value).explicitGet(),
          TxHelpers.buy(Order.V3, sender, PublicKey(sender.publicKey), pair, amount.value, amount.value, ts, ts + ts, fee.value).explicitGet(),
          TxExchangeAmount.unsafeFrom(amount.value),
          TxExchangePrice.unsafeFrom(amount.value),
          TxMatcherFee.unsafeFrom(fee.value),
          TxMatcherFee.unsafeFrom(fee.value),
          fee,
          ts,
          Proofs.empty,
          otherNetworkId
        ).signWith(sender).validatedEither.explicitGet()
      )
    }
  }

  property("LeaseCancelTransaction validation") {
    forAll(txParams) { case (sender, _, fee, ts) =>
      validateFromOtherNetwork(
        LeaseCancelTransaction(
          PublicKey(sender.publicKey),
          ByteStr(bytes32gen.sample.get),
          fee,
          ts,
          Proofs.empty,
          otherNetworkId
        ).signWith(sender).validatedEither.explicitGet()
      )
    }
  }

  property("CommitToGenerationTransaction validation") {
    forAll(txParams) { case (sender, _, fee, ts) =>
      validateFromOtherNetwork(
        TxHelpers.commitToGeneration(
          generationPeriodStart = Height(3001),
          sender = sender,
          timestamp = ts,
          fee = fee.value,
          networkId = otherNetworkId
        )
      )
    }
  }
}
