package tech.hearth.state

import tech.hearth.TestValues
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.test.*
import tech.hearth.test.StakingFixtures.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.SettleTransaction.Settlement
import tech.hearth.transaction.{TxHelpers, TxNonNegativeAmount}

/** A withdrawal through the real append path: a Reserve, the Withdraw, a Settle landing after it, and the payout at
  * the boundary block, persisted and rolled back. The miner is registered through `withDomain(enclaves = ...)`, since
  * no fixture can get a StartBoost accepted.
  */
class WithdrawalStorageTest extends FreeSpec with WithDomain {
  // The miner mines, operates the enclave and is the validator it boosts: Settle needs the validator committed for
  // the period it lands in, which crossPeriodBoundary does for the miner
  private val miner      = TxHelpers.defaultSigner
  private val enclaveKey = TxHelpers.signer(9)
  private val client     = TxHelpers.signer(11)

  private val asset    = IssuedAsset(ByteStr.fill(32)(7))
  private val quantity = 1000L
  private val triple   = (client.toAddress, miner.toAddress, asset)

  private def withWithdrawalDomain[A](f: Domain => A): A =
    withDomain(
      stakingSettings(credAsset = None),
      AddrWithBalance.enoughBalances(miner) :+ AddrWithBalance(client.toAddress, assets = Map(asset -> quantity)),
      assets = Seq(GenesisAssetSettings(asset.id.toString, "RESV", decimals = 0, quantity = quantity, minFee = TestValues.fee)),
      enclaves = Seq(RegisteredEnclave(ByteStr(enclaveKey.publicKey()), miner.toAddress, miner.toAddress))
    )(f)

  private def settle(d: Domain, cumulative: Long) =
    TxHelpers.settle(
      sender = miner,
      enclaveKey = enclaveKey,
      settlements = Seq(Settlement(client.toAddress, asset, TxNonNegativeAmount.unsafeFrom(cumulative))),
      periodStart = d.blockchain.currentGenerationPeriod.get.start.toInt
    )

  private def clientAsset(d: Domain): Long = d.blockchain.balance(client.toAddress, asset)

  /** Reserves 100 and asks for all of it back in [1, 4], so the payout is at 9. Returns the height before the Withdraw. */
  private def reserveAndWithdraw(d: Domain): Int = {
    d.appendBlock(TxHelpers.reserve(client, asset, amount = 100L, miner = miner.toAddress))
    val beforeWithdraw = d.blockchain.height
    d.appendBlock(TxHelpers.withdraw(client, miner.toAddress, asset, amount = 100L))
    beforeWithdraw
  }

  "A withdrawal" - {
    "pays out at the start of the period after next what the miner has not settled by then" in withWithdrawalDomain { d =>
      reserveAndWithdraw(d)
      d.blockchain.pendingWithdrawal(client.toAddress, miner.toAddress, asset) shouldBe Some(PendingWithdrawal(100L, Height(9)))

      crossPeriodBoundary(d, miner)
      d.appendBlock(settle(d, 40L))
      clientAsset(d) shouldBe quantity - 100L

      crossPeriodBoundary(d, miner)
      d.blockchain.height shouldBe 9
      clientAsset(d) shouldBe quantity - 40L
      d.blockchain.withdrawnAmount(client.toAddress, miner.toAddress, asset) shouldBe 60L
      d.blockchain.pendingWithdrawal(client.toAddress, miner.toAddress, asset) shouldBe None
    }

    "restores the request and the client's balance on rollback of a persisted payout block" in withWithdrawalDomain { d =>
      reserveAndWithdraw(d)
      crossPeriodBoundary(d, miner)
      crossPeriodBoundary(d, miner)
      d.appendBlock() // persists the payout block, so the rollback goes through RocksDBWriter
      clientAsset(d) shouldBe quantity

      d.rollbackTo(8)
      clientAsset(d) shouldBe quantity - 100L
      d.blockchain.withdrawnAmount(client.toAddress, miner.toAddress, asset) shouldBe 0L
      d.blockchain.pendingWithdrawal(client.toAddress, miner.toAddress, asset) shouldBe Some(PendingWithdrawal(100L, Height(9)))
      d.blockchain.withdrawalsDue(Height(9)) shouldBe Seq(triple)
    }

    "leaves nothing due on rollback of a persisted Withdraw" in withWithdrawalDomain { d =>
      val beforeWithdraw = reserveAndWithdraw(d)
      d.appendBlock()

      d.rollbackTo(beforeWithdraw)
      d.blockchain.pendingWithdrawal(client.toAddress, miner.toAddress, asset) shouldBe None
      d.blockchain.withdrawalsDue(Height(9)) shouldBe empty
    }
  }
}
