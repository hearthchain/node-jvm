package tech.hearth.db

import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.HearthSettings
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers

class TxBloomFilterSpec extends PropSpec with SharedDomain {
  private val richAccount = TxHelpers.signer(1200)

  override def settings: HearthSettings = DomainPresets.TransactionStateSnapshot

  override def genesisBalances: Seq[AddrWithBalance] = Seq(AddrWithBalance(richAccount.toAddress, 10000.hearth))

  property("Filter rotation works") {
    val transfer = TxHelpers.transfer(richAccount, TxHelpers.address(1201), 10.hearth)
    1 to 8 foreach { _ => domain.appendBlock() }
    domain.blockchain.height shouldEqual 9
    domain.appendBlock(transfer) // transfer at height 10
    domain.appendBlock()         // height = 11
    domain.appendBlock()         // solid state height = 11, filters are rotated
    domain.appendBlockE(transfer) should produce("AlreadyInTheState")

    domain.appendBlock()
    val tf2 = TxHelpers.transfer(richAccount, TxHelpers.address(1202), 20.hearth)
    domain.appendBlock(tf2)
    1 to 20 foreach { _ =>
      withClue(s"height = ${domain.blockchain.height}") {
        domain.appendBlockE(tf2) should produce("AlreadyInTheState")
      }
      domain.appendBlock()
    }
  }
}
