package com.wavesplatform.history

import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.history.Domain.BlockchainUpdaterExt
import com.wavesplatform.state.diffs.*
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Transaction
import com.wavesplatform.transaction.transfer.*
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterGeneratorFeeNextBlockOrMicroBlockTest extends PropSpec with DomainScenarioDrivenPropertyCheck {

  type Setup = (SigningKey, TransferTransaction, TransferTransaction, TransferTransaction)

  // The sender is credited by the genesis snapshot, which the domain applies as its own block at height 1
  val preconditionsAndPayments: Gen[Setup] = for {
    sender    <- accountGen
    recipient <- accountGen
    ts        <- positiveIntGen
    somePayment: TransferTransaction = createWavesTransfer(sender, recipient.toAddress, 1, 10, ts + 1).explicitGet()
    // generator has enough balance for this transaction if gets fee for block before applying it
    generatorPaymentOnFee: TransferTransaction = createWavesTransfer(defaultSigner, recipient.toAddress, 11, 1, ts + 2).explicitGet()
    someOtherPayment: TransferTransaction      = createWavesTransfer(sender, recipient.toAddress, 1, 1, ts + 3).explicitGet()
  } yield (sender, somePayment, generatorPaymentOnFee, someOtherPayment)

  private def fundSender(s: Setup): Seq[AddrWithBalance] = Seq(AddrWithBalance(s._1.toAddress, ENOUGH_AMT))

  property("generator should get fees before applying block before applyMinerFeeWithTransactionAfter in two blocks") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments, DefaultWavesSettings, fundSender) {
      case (domain: Domain, (_, somePayment, generatorPaymentOnFee, someOtherPayment)) =>
        val blocks = chainBlocksFrom(domain.lastBlockId, Seq(Seq(somePayment), Seq(generatorPaymentOnFee, someOtherPayment)))
        blocks.foreach(block => domain.blockchainUpdater.processBlock(block) should beRight)
    }
  }

  property("generator should get fees before applying block before applyMinerFeeWithTransactionAfter in block + micro") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundSender) {
      case (domain, (_, somePayment, generatorPaymentOnFee, someOtherPayment)) =>
        // The base block is empty: it used to carry the genesis transaction, which the genesis snapshot replaces
        val (block, microBlocks) = chainBaseAndMicro(
          domain.lastBlockId,
          Seq.empty[Transaction],
          Seq(Seq(somePayment), Seq(generatorPaymentOnFee, someOtherPayment)),
          defaultSigner,
          3,
          somePayment.timestamp
        )
        domain.blockchainUpdater.processBlock(block) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks.head, None) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks(1), None) should produce("unavailable funds")
    }
  }

  property("generator should get fees after applying every transaction after applyMinerFeeWithTransactionAfter in two blocks") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundSender) {
      case (domain, (_, somePayment, generatorPaymentOnFee, someOtherPayment)) =>
        val blocks = chainBlocksFrom(domain.lastBlockId, Seq(Seq(somePayment), Seq(generatorPaymentOnFee, someOtherPayment)))
        domain.blockchainUpdater.processBlock(blocks.head) should beRight
        domain.blockchainUpdater.processBlock(blocks(1)) should produce("unavailable funds")
    }
  }

  property("generator should get fees after applying every transaction after applyMinerFeeWithTransactionAfter in block + micro") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundSender) {
      case (domain, (_, somePayment, generatorPaymentOnFee, someOtherPayment)) =>
        val (block, microBlocks) = chainBaseAndMicro(
          domain.lastBlockId,
          Seq.empty[Transaction],
          Seq(Seq(somePayment), Seq(generatorPaymentOnFee, someOtherPayment)),
          defaultSigner,
          3,
          somePayment.timestamp
        )
        domain.blockchainUpdater.processBlock(block) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks.head, None) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks(1), None) should produce("unavailable funds")
    }
  }
}
