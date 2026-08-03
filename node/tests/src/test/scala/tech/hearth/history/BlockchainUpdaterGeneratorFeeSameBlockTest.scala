package tech.hearth.history

import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.WavesSettings
import tech.hearth.state.diffs.*
import tech.hearth.test.*
import tech.hearth.transaction.CommitToGenerationTransaction
import tech.hearth.transaction.transfer.*
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterGeneratorFeeSameBlockTest extends PropSpec with DomainScenarioDrivenPropertyCheck {

  type Setup = (SigningKey, TransferTransaction, TransferTransaction)

  // The sender is credited by the genesis snapshot, which the domain applies as its own block at height 1
  val preconditionsAndPayments: Gen[Setup] = for {
    sender                       <- accountGen
    recipient                    <- accountGen
    fee                          <- smallFeeGen
    ts                           <- positiveIntGen
    payment: TransferTransaction <- wavesTransferGeneratorP(ts, sender, recipient.toAddress)
    generatorPaymentOnFee: TransferTransaction = createWavesTransfer(defaultSigner, recipient.toAddress, payment.fee.value, fee, ts + 1).explicitGet()
  } yield (sender, payment, generatorPaymentOnFee)

  /** The generator can pay for its own transaction and nothing more, so that what it may spend on top of that is
    * exactly what it has earned within the block. Its generation deposit is locked, hence the deposit plus the fee, and
    * the reward is zeroed because it alone would cover the transfer under test.
    */
  private def fundGeneratorForItsOwnFeeOnly(s: Setup): Seq[AddrWithBalance] = Seq(
    AddrWithBalance(s._1.toAddress, ENOUGH_AMT),
    AddrWithBalance(defaultSigner.toAddress, CommitToGenerationTransaction.DepositInWavelets + s._3.fee.value)
  )

  private val withoutReward: WavesSettings = {
    val bs = MicroblocksActivatedAt0WavesSettings.blockchainSettings
    MicroblocksActivatedAt0WavesSettings.copy(blockchainSettings = bs.copy(rewardsSettings = bs.rewardsSettings.copy(initial = 0)))
  }

  /* There used to be a second property here, for the behaviour before `applyMinerFeeWithTransactionAfter`, where the
   * fee of a transaction reached the generator before that transaction was applied. There is no such mode any more:
   * `BlockDiffer` credits `CurrentBlockFeePart` of each fee as its transaction is applied, so a generator never sees
   * the fee of a transaction that comes after its own - which is what is left to assert.
   */
  property("block generator can't spend the fee of a later transaction in the same block") {
    scenario(preconditionsAndPayments, withoutReward, fundGeneratorForItsOwnFeeOnly) { case (domain, (_, somePayment, generatorPaymentOnFee)) =>
      domain.appendBlockAtE(generatorPaymentOnFee.timestamp)(generatorPaymentOnFee, somePayment) should produce("trying to spend a deposit")
    }
  }

  property("block generator can spend the fee of an earlier transaction in the same block") {
    scenario(preconditionsAndPayments, withoutReward, fundGeneratorForItsOwnFeeOnly) { case (domain, (_, somePayment, generatorPaymentOnFee)) =>
      // 40% of the earlier transaction's fee is credited before this one is applied, and that is what it spends
      val affordable = createWavesTransfer(
        defaultSigner,
        generatorPaymentOnFee.recipient,
        BlockDiffer.CurrentBlockFeePart(somePayment.fee.value),
        generatorPaymentOnFee.fee.value,
        generatorPaymentOnFee.timestamp
      ).explicitGet()

      domain.appendBlockAtE(somePayment.timestamp)(somePayment, affordable) should beRight
    }
  }
}
