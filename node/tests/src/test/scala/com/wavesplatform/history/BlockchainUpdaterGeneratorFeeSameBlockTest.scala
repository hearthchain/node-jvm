package com.wavesplatform.history

import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.history.Domain.BlockchainUpdaterExt
import com.wavesplatform.state.diffs.*
import com.wavesplatform.test.*
import com.wavesplatform.transaction.transfer.*
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterGeneratorFeeSameBlockTest extends PropSpec with DomainScenarioDrivenPropertyCheck {

  type Setup = (SigningKey, TransferTransaction, TransferTransaction)

  // The sender is credited by the genesis snapshot, which the domain applies as its own block at height 1
  val preconditionsAndPayments: Gen[Setup] = for {
    sender    <- accountGen
    recipient <- accountGen
    fee       <- smallFeeGen
    ts        <- positiveIntGen
    payment: TransferTransaction <- wavesTransferGeneratorP(ts, sender, recipient.toAddress)
    generatorPaymentOnFee: TransferTransaction = createWavesTransfer(defaultSigner, recipient.toAddress, payment.fee.value, fee, ts + 1).explicitGet()
  } yield (sender, payment, generatorPaymentOnFee)

  private def fundSender(s: Setup): Seq[AddrWithBalance] = Seq(AddrWithBalance(s._1.toAddress, ENOUGH_AMT))

  property("block generator can spend fee after transaction before applyMinerFeeWithTransactionAfter") {
    scenario(preconditionsAndPayments, DefaultWavesSettings, fundSender) {
      case (domain, (_, somePayment, generatorPaymentOnFee)) =>
        val blocks = chainBlocksFrom(domain.lastBlockId, Seq(Seq(generatorPaymentOnFee, somePayment)))
        blocks.foreach(block => domain.blockchainUpdater.processBlock(block) should beRight)
    }
  }

  property("block generator can't spend fee after transaction after applyMinerFeeWithTransactionAfter") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundSender) {
      case (domain, (_, somePayment, generatorPaymentOnFee)) =>
        val blocks = chainBlocksFrom(domain.lastBlockId, Seq(Seq(generatorPaymentOnFee, somePayment)))
        blocks.init.foreach(block => domain.blockchainUpdater.processBlock(block) should beRight)
        domain.blockchainUpdater.processBlock(blocks.last) should produce("unavailable funds")
    }
  }
}
