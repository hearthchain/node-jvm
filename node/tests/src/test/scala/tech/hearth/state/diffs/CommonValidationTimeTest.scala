package tech.hearth.state.diffs

import tech.hearth.db.WithState
import tech.hearth.settings.TestFunctionalitySettings.Enabled
import tech.hearth.state.*
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers

class CommonValidationTimeTest extends PropSpec with WithState {

  property("disallows too old transacions") {
    val prevBlockTs = TxHelpers.timestamp
    val blockTs     = prevBlockTs + 7 * 24 * 3600 * 1000

    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    val transfer = TxHelpers.transfer(master, recipient.toAddress, timestamp = prevBlockTs - Enabled.maxTransactionTimeBackOffset.toMillis - 1)

    withRocksDBWriter(Enabled) { (blockchain: Blockchain) =>
      val result = TransactionDiffer(Some(prevBlockTs), blockTs)(blockchain, transfer).resultE
      result should produce("in the past relative to previous block timestamp")
    }
  }

  property("disallows transactions from far future") {
    val prevBlockTs = TxHelpers.timestamp
    val blockTs     = prevBlockTs + 7 * 24 * 3600 * 1000

    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    val transfer = TxHelpers.transfer(master, recipient.toAddress, timestamp = blockTs + Enabled.maxTransactionTimeForwardOffset.toMillis + 1)

    val functionalitySettings = Enabled
    withRocksDBWriter(functionalitySettings) { (blockchain: Blockchain) =>
      TransactionDiffer(Some(prevBlockTs), blockTs)(blockchain, transfer).resultE should
        produce("in the future relative to block timestamp")
    }
  }
}
