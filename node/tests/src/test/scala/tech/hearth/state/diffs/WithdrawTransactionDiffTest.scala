package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.{Blockchain, PendingWithdrawal, SnapshotBlockchain, StateSnapshot}
import tech.hearth.test.*
import tech.hearth.test.StakingFixtures.*
import tech.hearth.transaction.Asset.{Hearth, IssuedAsset}
import tech.hearth.transaction.{Proofs, TxHelpers, WithdrawTransaction}

/** The request half of a withdrawal: what a WithdrawTransaction records and what it rejects. The payout half, at the
  * period boundary, is WithdrawalPayoutTest's.
  *
  * A reservation can only be made against a registered miner, which no fixture can produce through a real
  * StartBoost (see ReserveTransactionDiffTest), so the reserved and settled totals are seeded through a
  * SnapshotBlockchain and the diff is called directly.
  */
class WithdrawTransactionDiffTest extends FreeSpec with WithDomain {
  private val client = TxHelpers.signer(11)
  private val miner  = TxHelpers.secondSigner.toAddress

  private val asset    = IssuedAsset(ByteStr.fill(32)(7))
  private val quantity = 1000L

  private def withWithdrawDomain[A](f: Domain => A): A =
    withDomain(
      stakingSettings(credAsset = None),
      AddrWithBalance.enoughBalances(TxHelpers.defaultSigner) :+ AddrWithBalance(client.toAddress, assets = Map(asset -> quantity)),
      assets = Seq(GenesisAssetSettings(asset.id.toString, "RESV", decimals = 0, quantity = quantity, minFee = TestValues.fee))
    )(f)

  private def withLedgers(blockchain: Blockchain, reserved: Long, settled: Long = 0L): Blockchain = {
    val key      = (client.toAddress, miner, asset)
    val snapshot = StateSnapshot.build(blockchain, reservedAmounts = Map(key -> reserved), settledAmounts = Map(key -> settled)).explicitGet()
    SnapshotBlockchain(blockchain, snapshot)
  }

  "WithdrawTransactionDiff" - {
    "records a pending withdrawal payable at the start of the period after next" in withWithdrawDomain { d =>
      val blockchain = withLedgers(d.blockchain, reserved = 100L)
      val snapshot   = WithdrawTransactionDiff(blockchain)(TxHelpers.withdraw(client, miner, asset, amount = 60L)).explicitGet()

      val payoutAt = blockchain.currentGenerationPeriod.get.next.next.start
      snapshot.pendingWithdrawals((client.toAddress, miner, asset)) shouldBe Some(PendingWithdrawal(60L, payoutAt))
    }

    "rejects withdrawing from a miner the client never reserved with" in withWithdrawDomain { d =>
      d.appendBlockE(TxHelpers.withdraw(client, miner, asset, amount = 1L)) should produce("has no open reservation")
    }

    "rejects withdrawing more than is still unsettled" in withWithdrawDomain { d =>
      val blockchain = withLedgers(d.blockchain, reserved = 100L, settled = 70L)
      WithdrawTransactionDiff(blockchain)(TxHelpers.withdraw(client, miner, asset, amount = 31L)) should produce(
        "exceeds the 30 still unsettled"
      )
    }

    "counts what was already withdrawn as no longer available" in withWithdrawDomain { d =>
      val key        = (client.toAddress, miner, asset)
      val withdrawn  = StateSnapshot.build(d.blockchain, withdrawnAmounts = Map(key -> 50L)).explicitGet()
      val blockchain = withLedgers(SnapshotBlockchain(d.blockchain, withdrawn), reserved = 100L, settled = 20L)
      WithdrawTransactionDiff(blockchain)(TxHelpers.withdraw(client, miner, asset, amount = 31L)) should produce(
        "exceeds the 30 still unsettled"
      )
    }

    "rejects a second withdrawal while one is still pending" in withWithdrawDomain { d =>
      val blockchain1 = withLedgers(d.blockchain, reserved = 100L)
      val snapshot1   = WithdrawTransactionDiff(blockchain1)(TxHelpers.withdraw(client, miner, asset, amount = 10L)).explicitGet()
      val blockchain2 = SnapshotBlockchain(blockchain1, snapshot1)

      WithdrawTransactionDiff(blockchain2)(TxHelpers.withdraw(client, miner, asset, amount = 10L)) should produce(
        "already has a withdrawal pending"
      )
    }

    "charges only the fee, leaving the reserved asset with the reservation until the payout" in withWithdrawDomain { d =>
      val blockchain   = withLedgers(d.blockchain, reserved = 100L)
      val hearthBefore = blockchain.balance(client.toAddress)
      val tx           = TxHelpers.withdraw(client, miner, asset, amount = 60L)
      val snapshot     = WithdrawTransactionDiff(blockchain)(tx).explicitGet()

      snapshot.balances((client.toAddress, Hearth)) shouldBe hearthBefore - tx.fee.value
      snapshot.balances.contains((client.toAddress, asset)) shouldBe false
      snapshot.withdrawnAmounts shouldBe empty
    }
  }

  "WithdrawTransaction.create" - {
    "rejects an assetId that is not exactly 32 bytes" in {
      WithdrawTransaction.create(
        PublicKey(client.publicKey()),
        miner,
        IssuedAsset(ByteStr.fill(10)(1)),
        1L,
        Hearth,
        TestValues.fee,
        TxHelpers.timestamp,
        Proofs.empty
      ) should produce("assetId must be 32 bytes")
    }
  }
}
