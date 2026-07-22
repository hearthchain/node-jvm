package com.wavesplatform.transaction

import com.wavesplatform.account.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.protobuf.transaction.{PBTransactions, SignedTransaction as PBSignedTransaction}
import com.wavesplatform.state.Height
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.exchange.{AssetPair, ExchangeTransaction, Order}
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class ChainIdSpecification extends PropSpec {
  private val otherChainId = 'W'.toByte

  private def txParams: Gen[(TxVersion, SigningKey, TxPositiveAmount, TxPositiveAmount, TxTimestamp)] =
    for {
      version <- Gen.oneOf(1, 2, 3)
      sender  <- accountGen
      amount  <- Gen.choose(1L, 10000000L)
      fee     <- Gen.choose(1000000L, 10000000L)
      ts      <- Gen.choose(1L, 1000000L)
    } yield (version.toByte, sender, TxPositiveAmount.unsafeFrom(amount), TxPositiveAmount.unsafeFrom(fee), ts)

  private def validateFromOtherNetwork(tx: Transaction): Unit = {
    tx.chainId should not be AddressScheme.current.chainId

    val protoTx       = PBTransactions.protobuf(tx)
    val recoveredTxEi = PBTransactions.vanilla(PBSignedTransaction.parseFrom(protoTx.toByteArray))

    recoveredTxEi.explicitGet()

    val recoveredTx = recoveredTxEi.explicitGet().asInstanceOf[ProvenTransaction]

    recoveredTx shouldBe tx
    recoveredTx.firstProofIsValidSignatureBeforeV6.explicitGet()
  }

  property("TransferTransaction validation") {
    forAll(txParams) { case (_, sender, amount, fee, ts) =>
      validateFromOtherNetwork(
        TransferTransaction(
          TxVersion.V3,
          PublicKey(sender.publicKey),
          sender.toAddress,
          Waves,
          amount,
          Waves,
          fee,
          ByteStr.empty,
          ts,
          Proofs.empty,
          otherChainId
        ).signWith(sender).validatedEither.explicitGet()
      )
    }
  }

  property("LeaseTransaction validation") {
    forAll(txParams) { case (_, sender, amount, fee, ts) =>
      validateFromOtherNetwork(
        LeaseTransaction
          .create(1, otherChainId, PublicKey(sender.publicKey), sender.toAddress, amount.value, fee.value, ts, Proofs.empty)
          .explicitGet()
          .signWith(sender)
      )
    }
  }

  property("ExchangeTransaction validation") {
    forAll(txParams) { case (_, sender, amount, fee, ts) =>
      val pair = AssetPair(Waves, IssuedAsset(ByteStr(bytes32gen.sample.get)))
      validateFromOtherNetwork(
        ExchangeTransaction(
          TxVersion.V3,
          TxHelpers.sell(Order.V3, sender, PublicKey(sender.publicKey), pair, amount.value, amount.value, ts, ts + ts, fee.value).explicitGet(),
          TxHelpers.buy(Order.V3, sender, PublicKey(sender.publicKey), pair, amount.value, amount.value, ts, ts + ts, fee.value).explicitGet(),
          TxExchangeAmount.unsafeFrom(amount.value),
          TxExchangePrice.unsafeFrom(amount.value),
          fee.value,
          fee.value,
          fee,
          ts,
          Proofs.empty,
          otherChainId
        ).signWith(sender).validatedEither.explicitGet()
      )
    }
  }

  property("LeaseCancelTransaction validation") {
    forAll(txParams) { case (_, sender, _, fee, ts) =>
      validateFromOtherNetwork(
        LeaseCancelTransaction(
          TxVersion.V3,
          PublicKey(sender.publicKey),
          ByteStr(bytes32gen.sample.get),
          fee,
          ts,
          Proofs.empty,
          otherChainId
        ).signWith(sender).validatedEither.explicitGet()
      )
    }
  }

  property("MassTransferTransaction validation") {
    forAll(txParams) { case (_, sender, amount, fee, ts) =>
      validateFromOtherNetwork(
        MassTransferTransaction(
          TxVersion.V2,
          PublicKey(sender.publicKey),
          Waves,
          Seq(ParsedTransfer(sender.toAddress, TxNonNegativeAmount.unsafeFrom(amount.value))),
          fee,
          ts,
          ByteStr.empty,
          Proofs.empty,
          otherChainId
        ).signWith(sender).validatedEither.explicitGet()
      )
    }
  }

  property("CommitToGenerationTransaction validation") {
    forAll(txParams) { case (version, sender, _, fee, ts) =>
      validateFromOtherNetwork(
        TxHelpers.commitToGeneration(
          generationPeriodStart = Height(3001),
          sender = sender,
          timestamp = ts,
          fee = fee.value,
          chainId = otherChainId,
          version = version
        )
      )
    }
  }
}
