package com.wavesplatform.state.diffs

import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.settings.RewardsVotingSettings
import com.wavesplatform.test.{NumericExt, PropSpec}
import com.wavesplatform.transaction.TxHelpers

class TransferTransactionDiffTest extends PropSpec with WithDomain {

  property("transfers to recipient preserving waves invariant") {
    val sender    = TxHelpers.secondAddress
    val senderKp  = TxHelpers.secondSigner
    val recipient = TxHelpers.address(2)

    withDomain(
      DomainPresets.mostRecent.copy(rewardsSettings = RewardsVotingSettings(None)),
      AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, senderKp)
    ) { d =>
      val wavesTransfer = TxHelpers.transfer(senderKp, recipient)
      d.appendAndAssertSucceed(wavesTransfer)
      val rewardFee = 6.waves - wavesTransfer.fee.value * 3 / 5
      assertBalanceInvariant(d.liquidSnapshot, d.rocksDBWriter, rewardFee)
      d.blockchain.balance(recipient) shouldBe wavesTransfer.amount.value
      d.blockchain.balance(sender) shouldBe ENOUGH_AMT - wavesTransfer.amount.value - wavesTransfer.fee.value
    }
  }

}
