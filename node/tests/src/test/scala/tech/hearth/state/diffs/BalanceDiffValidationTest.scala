package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithState
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.lagonaki.mocks.TestBlock
import tech.hearth.settings.TestFunctionalitySettings
import tech.hearth.state.Height
import tech.hearth.test.*
import tech.hearth.transaction.lease.LeaseTransaction
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.{CommitToGenerationTransaction, TxHelpers}

class BalanceDiffValidationTest extends PropSpec with WithState {
  private val master = TxHelpers.signer(1)

  // The master is credited by the genesis snapshot, which is applied to the block at height 1
  private val masterBalance = Seq(AddrWithBalance(master.toAddress))

  val ownLessThatLeaseOut: (TransferTransaction, LeaseTransaction, LeaseTransaction, TransferTransaction) = {
    val alice  = TxHelpers.signer(2)
    val bob    = TxHelpers.signer(3)
    val cooper = TxHelpers.signer(4)

    val fee                      = 400000
    val masterTransferAmount     = 1000.waves
    val aliceLeaseToBobAmount    = 500.waves
    val masterLeaseToAliceAmount = 750.waves

    val masterTransfersToAlice = TxHelpers.transfer(master, alice.toAddress, masterTransferAmount, fee = fee)
    val aliceLeasesToBob       = TxHelpers.lease(alice, bob.toAddress, aliceLeaseToBobAmount)
    val masterLeasesToAlice    = TxHelpers.lease(master, alice.toAddress, masterLeaseToAliceAmount)
    val aliceTransfersMoreThanOwnsMinusLeaseOut =
      TxHelpers.transfer(alice, cooper.toAddress, masterTransferAmount - fee - aliceLeaseToBobAmount, fee = fee)

    (masterTransfersToAlice, aliceLeasesToBob, masterLeasesToAlice, aliceTransfersMoreThanOwnsMinusLeaseOut)
  }

  property("cannot transfer more than own-leaseOut after allow-leased-balance-transfer-until") {
    val settings = TestFunctionalitySettings.Enabled

    val (masterTransfersToAlice, aliceLeasesToBob, masterLeasesToAlice, aliceTransfersMoreThanOwnsMinusLeaseOut) = ownLessThatLeaseOut
    assertDiffEi(
      Seq(
        TestBlock.create(Seq()), // Height 1: carries the genesis snapshot
        TestBlock.create(Seq()),
        TestBlock.create(Seq()),
        TestBlock.create(Seq()),
        TestBlock.create(Seq(masterTransfersToAlice, aliceLeasesToBob, masterLeasesToAlice))
      ),
      TestBlock.create(Seq(aliceTransfersMoreThanOwnsMinusLeaseOut)),
      settings,
      masterBalance
    ) { snapshotEi =>
      snapshotEi should produce("trying to spend leased money")
    }
  }

  property("commit to generation") {
    val settings = DomainPresets.DeterministicFinality.blockchainSettings.functionalitySettings.copy(generationPeriodLength = 3)

    val notBlockedAmount = 100_000.waves
    val initBalance      = notBlockedAmount + CommitToGenerationTransaction.DepositInWavelets + TestValues.commitToGenerationFee

    assertDiffEiTraced(
      Seq(TestBlock.create(Seq())), // Height 1: carries the genesis snapshot
      TestBlock.create(Seq(TxHelpers.commitToGeneration(Height(4)))),
      settings,
      Seq(AddrWithBalance(TxHelpers.defaultAddress, initBalance))
    ) { snapshotEi =>
      snapshotEi.resultE.explicitGet()
    }
  }

  property("cannot transfer more than own-generationDeposit") {
    val settings = DomainPresets.DeterministicFinality.blockchainSettings.functionalitySettings.copy(generationPeriodLength = 3)

    val notBlockedAmount = 100_000.waves
    val initBalance =
      notBlockedAmount + CommitToGenerationTransaction.DepositInWavelets + TestValues.commitToGenerationFee + TestValues.fee // for transfer

    val transferAmount = notBlockedAmount + 1
    // The depositor must not be the account these blocks are mined by (TestBlock signs with defaultSigner): a miner
    // collects the fees of the very blocks under test, and that extra balance would cover the transfer without ever
    // touching the deposit, which is exactly what this test is checking.
    assertDiffEiTraced(
      Seq(
        TestBlock.create(Seq()), // Height 1: carries the genesis snapshot
        TestBlock.create(Seq(TxHelpers.commitToGeneration(Height(4), sender = master)))
      ),
      TestBlock.create(Seq(TxHelpers.transfer(from = master, to = TxHelpers.address(2), amount = transferAmount))),
      settings,
      Seq(AddrWithBalance(master.toAddress, initBalance))
    ) { snapshotEi =>
      snapshotEi.resultE should produce("trying to spend a deposit")
    }
  }
}
