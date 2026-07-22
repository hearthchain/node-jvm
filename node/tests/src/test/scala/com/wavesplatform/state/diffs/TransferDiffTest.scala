package com.wavesplatform.state.diffs

import com.wavesplatform.account.Address
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.ScriptsAndSponsorship
import com.wavesplatform.transaction.{TxHelpers, TxVersion}

class TransferDiffTest extends PropSpec with WithDomain {
  private val master = TxHelpers.signer(1)

  private val preconditionsAndTransfer = {
    val recipient = TxHelpers.signer(2)

    val transferV1 = TxHelpers.transfer(master, recipient.toAddress, version = TxVersion.V1)
    val transferV2 = TxHelpers.transfer(master, recipient.toAddress)

    Seq(transferV1, transferV2)
  }

  property("transfers to recipient preserving waves invariant") {
    preconditionsAndTransfer.foreach { transfer =>
      withDomain(ScriptsAndSponsorship, Seq(AddrWithBalance(master.toAddress))) { d =>
        d.appendBlock(transfer)

        val carryFee = -transfer.fee.value * 3 / 5
        assertBalanceInvariant(d.liquidSnapshot, d.rocksDBWriter, carryFee)

        val recipient = transfer.recipient.asInstanceOf[Address]
        if (transfer.sender.toAddress != recipient) {
          d.balance(recipient) shouldBe transfer.amount.value
        }
      }
    }
  }

}
