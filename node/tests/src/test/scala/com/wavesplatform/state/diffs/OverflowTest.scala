package com.wavesplatform.state.diffs

import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.{TransactionType, TxHelpers}

class OverflowTest extends PropSpec with WithDomain {
  import DomainPresets.*

  private val transferFee     = FeeConstants(TransactionType.Transfer) * FeeUnit
  private val massTransferFee = 0.002.waves

  private def numPairs(fee: Long) =
    Seq(
      (Long.MaxValue, 1L),
      (1L + fee, Long.MaxValue - fee),
      (Long.MaxValue / 2 + 1, Long.MaxValue / 2 + 1)
    )

  private val sender    = TxHelpers.signer(1)
  private val recipient = TxHelpers.signer(2).toAddress

  // These two can no longer be set up: overflowing the recipient's balance needs the sender to hold the counterpart,
  // so the genesis total necessarily exceeds Long.MaxValue - which GenesisSnapshot rejects, since a chain's total
  // Waves has to fit in a Long. Reaching that ceiling now requires minting via block rewards rather than genesis.
  ignore("transfer overflow") {
    numPairs(transferFee).foreach { case (recipientBalance, transferAmount) =>
      val balances = Seq(AddrWithBalance(sender.toAddress, Long.MaxValue), AddrWithBalance(recipient, recipientBalance))
      withDomain(RideV5, balances) { d =>
        d.appendBlockE(TxHelpers.transfer(sender, recipient, transferAmount)) should produce("Waves balance sum overflow")
      }
    }
  }

  ignore("mass transfer overflow") {
    numPairs(massTransferFee).foreach { case (recipientBalance, transferAmount) =>
      val balances = Seq(AddrWithBalance(sender.toAddress, Long.MaxValue), AddrWithBalance(recipient, recipientBalance))
      withDomain(RideV5, balances) { d =>
        d.appendBlockE(TxHelpers.massTransfer(sender, Seq(recipient -> transferAmount), fee = massTransferFee)) should produce(
          "Waves balance sum overflow"
        )
      }
    }
  }

  property("mass transfer overflow in list of transfers") {
    numPairs(massTransferFee).foreach { case (balance1, balance2) =>
      (the[Exception] thrownBy TxHelpers.massTransfer(
        sender,
        Seq(
          recipient -> balance1,
          recipient -> balance2
        )
      )).getMessage shouldBe "OverflowError"
    }
  }

}
