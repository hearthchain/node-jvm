package tech.hearth.mining

import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers
import org.scalatest.matchers.should.Matchers

class MinerBalanceOverflowTest extends FlatSpec with Matchers with WithDomain {
  "Miner balance" should "not overflow" in withDomain(
    DomainPresets.RideV4WithRewards,
    Seq(AddrWithBalance(TxHelpers.defaultAddress, Long.MaxValue - 1.waves)),
    generators = Seq(TxHelpers.defaultSigner)
  ) { d =>
    d.appendBlockE() should produce("Waves balance sum overflow")
    val minerBalance = d.blockchain.balance(TxHelpers.defaultAddress)
    minerBalance shouldBe >(0L)
  }
}
