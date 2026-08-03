package tech.hearth.state.diffs

import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.ScriptsAndSponsorship
import tech.hearth.transaction.TxHelpers

class TransferDiffTest extends PropSpec with WithDomain {
  private val master = TxHelpers.signer(1)

  private val preconditionsAndTransfer = {
    val recipient = TxHelpers.signer(2)

    val transferV1 = TxHelpers.transfer(master, recipient.toAddress)
    val transferV2 = TxHelpers.transfer(master, recipient.toAddress)

    Seq(transferV1, transferV2)
  }

  property("transfers to recipient preserving waves invariant") {
    preconditionsAndTransfer.foreach { transfer =>
      withDomain(ScriptsAndSponsorship, AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, master)) { d =>
        d.appendBlock(transfer)

        val carryFee = -transfer.fee.value * 3 / 5 + 6.waves
        assertBalanceInvariant(d.liquidSnapshot, d.rocksDBWriter, carryFee)

        val recipient = transfer.recipient
        if (transfer.sender.toAddress != recipient) {
          d.balance(recipient) shouldBe transfer.amount.value
        }
      }
    }
  }

}
