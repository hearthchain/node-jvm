package tech.hearth.state.diffs

import tech.hearth.database.TestStorageFactory
import tech.hearth.db.WithDomain
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.history.Domain
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers

/** NodeRestartTestSuite (node-it) observed a duplicate, already-mined transaction being accepted again after
  * restarting the node(s) that mined it, instead of being rejected with AlreadyInTheState. CommonValidationTest and
  * TxBloomFilterSpec both cover disallowDuplicateIds, but only against a RocksDBWriter that has stayed in the same
  * process the whole time; neither exercises RocksDBWriter's constructor, which is what runs on a real restart and is
  * where its tx bloom filters get rebuilt from disk. This reproduces that specific case: mine a transaction, build a
  * *fresh* RocksDBWriter/BlockchainUpdaterImpl against the same underlying RDB (simulating a restart), and try to
  * re-apply the same transaction there.
  */
class DuplicateTransactionAfterRestartSpec extends PropSpec with WithDomain {

  property("a transaction already in the state is rejected after a simulated restart") {
    withDomain(DomainPresets.TransactionStateSnapshot) { d =>
      val tx = TxHelpers.transfer(TxHelpers.defaultSigner, TxHelpers.secondAddress, 1.waves)
      d.appendBlock(tx)
      // Solidify: NG keeps the latest block "liquid" (in BlockchainUpdaterImpl's memory only) until another block
      // lands on top of it, so without this the tx's block would never actually reach RocksDBWriter to begin with.
      d.appendBlock()

      d.blockchain.containsTransaction(tx) shouldBe true

      val (restartedUpdater, restartedWriter) = TestStorageFactory(d.settings, d.rdb, d.testTime, BlockchainUpdateTriggers.noop)
      val restarted                           = Domain(d.rdb, restartedUpdater, restartedWriter, d.settings, d.testTime)

      withClue("containsTransaction after simulated restart: ") {
        restarted.blockchain.containsTransaction(tx) shouldBe true
      }
      withClue("re-appending the same transaction after simulated restart: ") {
        restarted.appendBlockE(tx) should produce("AlreadyInTheState")
      }
    }
  }
}
