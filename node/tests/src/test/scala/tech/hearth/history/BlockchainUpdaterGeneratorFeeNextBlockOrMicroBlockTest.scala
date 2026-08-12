package tech.hearth.history

import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.HearthSettings
import tech.hearth.state.diffs.*
import tech.hearth.test.*
import tech.hearth.transaction.CommitToGenerationTransaction
import tech.hearth.transaction.transfer.*
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterGeneratorFeeNextBlockOrMicroBlockTest extends PropSpec with DomainScenarioDrivenPropertyCheck {

  type Setup = (SigningKey, TransferTransaction, TransferTransaction, TransferTransaction)

  // The sender is credited by the genesis snapshot, which the domain applies as its own block at height 1
  val preconditionsAndPayments: Gen[Setup] = for {
    sender    <- accountGen
    recipient <- accountGen
    ts        <- positiveIntGen
    somePayment: TransferTransaction = createHearthTransfer(sender, recipient.toAddress, 1, 10, ts + 1).explicitGet()
    // The generator can afford this only out of what the earlier transaction's fee earned it
    generatorPaymentOnFee: TransferTransaction = createHearthTransfer(defaultSigner, recipient.toAddress, 11, 1, ts + 2).explicitGet()
    someOtherPayment: TransferTransaction      = createHearthTransfer(sender, recipient.toAddress, 1, 1, ts + 3).explicitGet()
  } yield (sender, somePayment, generatorPaymentOnFee, someOtherPayment)

  /** The generator can pay for its own transaction and nothing more, so that what it spends beyond that is exactly what
    * it has earned. Its generation deposit is locked, hence the deposit plus the fee; the reward is zeroed because it
    * alone would cover the transfer under test.
    */
  private def fundGeneratorForItsOwnFeeOnly(s: Setup): Seq[AddrWithBalance] = Seq(
    AddrWithBalance(s._1.toAddress, ENOUGH_AMT),
    AddrWithBalance(defaultSigner.toAddress, CommitToGenerationTransaction.DepositInEmbers + s._3.fee.value)
  )

  private val settings: HearthSettings = {
    val bs = MicroblocksActivatedAt0HearthSettings.blockchainSettings
    MicroblocksActivatedAt0HearthSettings.copy(blockchainSettings = bs.copy(rewardsSettings = withFlatReward(bs.rewardsSettings, 0)))
  }

  /* These properties used to come in pairs, for before and after `applyMinerFeeWithTransactionAfter`. There is no such
   * mode any more - and the two "after" variants had become byte-identical duplicates. What is left is where a fee
   * reaches the generator: 40% of it in the block that carries the transaction, the 60% carry in the block after.
   */
  property("generator gets 40% of a fee in the block that carries the transaction, and can spend it in the next") {
    scenario(preconditionsAndPayments, settings, fundGeneratorForItsOwnFeeOnly) { case (domain, (_, somePayment, _, _)) =>
      domain.appendBlockAt(somePayment.timestamp)(somePayment)

      val earnedSoFar = BlockDiffer.CurrentBlockFeePart(somePayment.fee.value)
      val affordable =
        createHearthTransfer(defaultSigner, somePayment.transfers.head.address, earnedSoFar, 1, somePayment.timestamp + 2).explicitGet()

      domain.appendBlockAtE(affordable.timestamp)(affordable) should beRight
    }
  }

  property("generator can't spend more of a fee than the part credited so far") {
    scenario(preconditionsAndPayments, settings, fundGeneratorForItsOwnFeeOnly) {
      case (domain, (_, somePayment, generatorPaymentOnFee, someOtherPayment)) =>
        domain.appendBlockAt(somePayment.timestamp)(somePayment)

        // The whole fee plus one, which the carry only makes available a block later
        domain.appendBlockAtE(generatorPaymentOnFee.timestamp)(generatorPaymentOnFee, someOtherPayment) should produce(
          "trying to spend a deposit"
        )
    }
  }

  property("generator gets the carry of the referenced block, in a block extended by micro blocks") {
    scenario(preconditionsAndPayments, settings, fundGeneratorForItsOwnFeeOnly) { case (domain, (_, somePayment, _, someOtherPayment)) =>
      domain.appendBlockAt(somePayment.timestamp)()
      domain.appendMicroBlock(somePayment)

      // The carry of the whole liquid block, micro blocks included, is credited when the next block references it
      val affordable =
        createHearthTransfer(defaultSigner, somePayment.transfers.head.address, somePayment.fee.value, 1, somePayment.timestamp + 2).explicitGet()

      domain.appendBlockAtE(affordable.timestamp)(affordable, someOtherPayment) should beRight
    }
  }
}
