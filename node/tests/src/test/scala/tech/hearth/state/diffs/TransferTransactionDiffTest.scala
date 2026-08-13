package tech.hearth.state.diffs

import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.RewardsVotingSettings
import tech.hearth.test.{NumericExt, PropSpec}
import tech.hearth.transaction.TxHelpers

class TransferTransactionDiffTest extends PropSpec with WithDomain {

  property("transfers to recipient preserving hearth invariant") {
    val sender    = TxHelpers.secondAddress
    val senderKp  = TxHelpers.secondSigner
    val recipient = TxHelpers.address(2)

    withDomain(
      DomainPresets.mostRecent.copy(rewardsSettings = RewardsVotingSettings(None)),
      AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, senderKp)
    ) { d =>
      val hearthTransfer = TxHelpers.transfer(senderKp, recipient)
      d.appendAndAssertSucceed(hearthTransfer)
      val rewardFee = 6.hearth - hearthTransfer.fee.value * 3 / 5
      assertBalanceInvariant(d.liquidSnapshot, d.rocksDBWriter, rewardFee)
      val transferAmount = hearthTransfer.transfers.map(_.amount.value).sum
      d.blockchain.balance(recipient) shouldBe transferAmount
      d.blockchain.balance(sender) shouldBe ENOUGH_AMT - transferAmount - hearthTransfer.fee.value
    }
  }

}
