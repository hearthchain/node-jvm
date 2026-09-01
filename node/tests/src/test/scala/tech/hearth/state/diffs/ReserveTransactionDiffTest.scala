package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.{Blockchain, RegisteredEnclave, SnapshotBlockchain}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.{Hearth, IssuedAsset}
import tech.hearth.transaction.TxHelpers

/** ReserveTransaction's "registered miner" check depends on RegisteredEnclave data that, in production, only a
  * verified StartBoostTransaction can write - and (see StartBoostTransactionDiffTest's own doc comment) no test
  * fixture in this repo can drive a StartBoost quote all the way to its accept path, since that needs a real,
  * currently-valid Intel-signed PCK certificate chain. So the "registered miner" accept path here is exercised by
  * calling ReserveTransactionDiff directly against a Blockchain wrapper that injects a RegisteredEnclave entry,
  * rather than through a real StartBoostTransaction - the reject paths that don't need a registered miner (unknown
  * asset, unregistered miner) go through the real domain via d.appendBlockE.
  *
  * Only an issued asset is reservable (see ReserveTransaction), so every reserve here names one of two assets
  * declared in the genesis snapshot and held in full by the sender.
  */
class ReserveTransactionDiffTest extends FreeSpec with WithDomain {
  private val sender = TxHelpers.defaultSigner
  private val miner  = TxHelpers.secondSigner.toAddress

  private val asset      = IssuedAsset(ByteStr.fill(32)(7))
  private val otherAsset = IssuedAsset(ByteStr.fill(32)(8))
  private val quantity   = 1000L

  private def withReserveDomain[A](f: Domain => A): A =
    withDomain(
      DeterministicFinality,
      Seq(AddrWithBalance(sender.toAddress, assets = Map(asset -> quantity, otherAsset -> quantity))),
      assets = Seq(
        GenesisAssetSettings(asset.id, "RESV", decimals = 0, quantity = quantity, minFee = TestValues.fee),
        GenesisAssetSettings(otherAsset.id, "RESV2", decimals = 0, quantity = quantity, minFee = TestValues.fee)
      )
    )(f)

  private def withRegisteredMiner(blockchain: Blockchain, registeredMiner: Address): Blockchain =
    blockchainWithRegisteredEnclave(blockchain, RegisteredEnclave(ByteStr.fill(32)(1), registeredMiner, registeredMiner))

  "ReserveTransactionDiff" - {
    "rejects reserving to an unregistered miner" in withReserveDomain { d =>
      d.appendBlockE(TxHelpers.reserve(sender, asset = asset, amount = 100L, miner = miner)) should produce("is not a registered miner")
    }

    "rejects a non-existent asset" in withReserveDomain { d =>
      val unknownAsset = IssuedAsset(ByteStr.fill(32)(9))
      d.appendBlockE(TxHelpers.reserve(sender, asset = unknownAsset, amount = 100L, miner = miner)) should produce("is not issued")
    }

    "rejects a non-existent fee asset" in withReserveDomain { d =>
      val unknownAsset = IssuedAsset(ByteStr.fill(32)(9))
      d.appendBlockE(TxHelpers.reserve(sender, asset = asset, amount = 100L, feeAsset = unknownAsset, miner = miner)) should produce(
        "does not exist, cannot be used to pay fees"
      )
    }

    "accepts a reserve to a registered miner, deducting the sender's balance" in withReserveDomain { d =>
      val blockchain   = withRegisteredMiner(d.blockchain, miner)
      val hearthBefore = blockchain.balance(sender.toAddress)
      val tx           = TxHelpers.reserve(sender, asset = asset, amount = 100L, miner = miner)
      val snapshot     = ReserveTransactionDiff(blockchain)(tx).explicitGet()

      snapshot.reservedAmounts((sender.toAddress, miner, asset)) shouldBe 100L
      snapshot.balances((sender.toAddress, asset)) shouldBe quantity - 100L
      // The fee is payable in Hearth, unlike the reserved asset itself.
      snapshot.balances((sender.toAddress, Hearth)) shouldBe hearthBefore - tx.fee.value
    }

    "accumulates the reserved amount across multiple transactions to the same (sender, miner, asset)" in withReserveDomain { d =>
      val blockchain = withRegisteredMiner(d.blockchain, miner)

      val tx1       = TxHelpers.reserve(sender, asset = asset, amount = 100L, miner = miner)
      val snapshot1 = ReserveTransactionDiff(blockchain)(tx1).explicitGet()
      snapshot1.reservedAmounts((sender.toAddress, miner, asset)) shouldBe 100L

      val blockchain2 = SnapshotBlockchain(blockchain, snapshot1)
      val tx2         = TxHelpers.reserve(sender, asset = asset, amount = 50L, miner = miner)
      val snapshot2   = ReserveTransactionDiff(blockchain2)(tx2).explicitGet()
      snapshot2.reservedAmounts((sender.toAddress, miner, asset)) shouldBe 150L
    }

    "keeps reserves for different assets to the same miner independent" in withReserveDomain { d =>
      val blockchain = withRegisteredMiner(d.blockchain, miner)

      val tx1       = TxHelpers.reserve(sender, asset = asset, amount = 10L, miner = miner)
      val snapshot1 = ReserveTransactionDiff(blockchain)(tx1).explicitGet()

      val blockchain2 = SnapshotBlockchain(blockchain, snapshot1)
      val tx2         = TxHelpers.reserve(sender, asset = otherAsset, amount = 5L, miner = miner)
      val snapshot2   = ReserveTransactionDiff(blockchain2)(tx2).explicitGet()
      val blockchain3 = SnapshotBlockchain(blockchain2, snapshot2)

      blockchain3.reservedAmount(sender.toAddress, miner, asset) shouldBe 10L
      blockchain3.reservedAmount(sender.toAddress, miner, otherAsset) shouldBe 5L
    }
  }
}
