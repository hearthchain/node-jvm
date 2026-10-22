package tech.hearth.state.diffs

import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.TestFunctionalitySettings.Enabled
import tech.hearth.state.*
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers

class CommonValidationTimeTest extends PropSpec with WithDomain {

  property("disallows too old transacions") {
    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    // Bounded by the parent of the block the transaction goes into
    withDomain(DomainPresets.DeterministicFinality, AddrWithBalance.enoughBalances(master)) { d =>
      val prevBlockTs = d.blockchain.lastBlockTimestamp.get
      val transfer    = TxHelpers.transfer(master, recipient.toAddress, timestamp = prevBlockTs - Enabled.maxTransactionTimeBackOffset.toMillis - 1)
      d.appendBlockE(transfer) should produce("in the past relative to previous block timestamp")
    }
  }

  property("disallows transactions from far future") {
    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    // Bounded by the block the transaction goes into, the one on top of the chain the differ sees
    withDomain(DomainPresets.DeterministicFinality, AddrWithBalance.enoughBalances(master)) { d =>
      val blockTs  = d.blockchain.lastBlockTimestamp.get + 7 * 24 * 3600 * 1000
      val transfer = TxHelpers.transfer(master, recipient.toAddress, timestamp = blockTs + Enabled.maxTransactionTimeForwardOffset.toMillis + 1)
      d.appendBlockAtE(blockTs)(transfer) should produce("in the future relative to block timestamp")
    }
  }
}
