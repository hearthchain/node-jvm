package tech.hearth.state.diffs

import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.{Blockchain, GenerationPeriod, RegisteredEnclave, SnapshotBlockchain}
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
  */
class ReserveTransactionDiffTest extends FreeSpec with WithDomain {
  private val sender = TxHelpers.defaultSigner
  private val miner  = TxHelpers.secondSigner.toAddress

  private def withRegisteredMiner(blockchain: Blockchain, registeredMiner: Address): Blockchain =
    new Blockchain {
      export blockchain.{registeredEnclaves as _, *}
      override def registeredEnclaves(at: GenerationPeriod): IndexedSeq[RegisteredEnclave] =
        blockchain.registeredEnclaves(at) :+ RegisteredEnclave(ByteStr.fill(32)(1), registeredMiner, registeredMiner)
    }

  "ReserveTransactionDiff" - {
    "rejects reserving to an unregistered miner" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      d.appendBlockE(TxHelpers.reserve(sender, miner = miner)) should produce("is not a registered miner")
    }

    "rejects a non-existent asset" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      val unknownAsset = IssuedAsset(ByteStr.fill(32)(9))
      d.appendBlockE(TxHelpers.reserve(sender, asset = unknownAsset, miner = miner)) should produce("is not issued")
    }

    "rejects a non-existent fee asset" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      val unknownAsset = IssuedAsset(ByteStr.fill(32)(9))
      d.appendBlockE(TxHelpers.reserve(sender, feeAsset = unknownAsset, miner = miner)) should produce("does not exist, cannot be used to pay fees")
    }

    "accepts a reserve to a registered miner, deducting the sender's balance" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val blockchain    = withRegisteredMiner(d.blockchain, miner)
      val balanceBefore = blockchain.balance(sender.toAddress)
      val tx            = TxHelpers.reserve(sender, amount = 100.hearth, miner = miner)
      val snapshot      = ReserveTransactionDiff(blockchain)(tx).explicitGet()

      snapshot.reservedAmounts((sender.toAddress, miner, Hearth)) shouldBe 100.hearth
      snapshot.balances((sender.toAddress, Hearth)) shouldBe balanceBefore - 100.hearth - tx.fee.value
    }

    "accumulates the reserved amount across multiple transactions to the same (sender, miner, asset)" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val blockchain = withRegisteredMiner(d.blockchain, miner)

      val tx1       = TxHelpers.reserve(sender, amount = 100.hearth, miner = miner)
      val snapshot1 = ReserveTransactionDiff(blockchain)(tx1).explicitGet()
      snapshot1.reservedAmounts((sender.toAddress, miner, Hearth)) shouldBe 100.hearth

      val blockchain2 = SnapshotBlockchain(blockchain, snapshot1)
      val tx2         = TxHelpers.reserve(sender, amount = 50.hearth, miner = miner)
      val snapshot2   = ReserveTransactionDiff(blockchain2)(tx2).explicitGet()
      snapshot2.reservedAmounts((sender.toAddress, miner, Hearth)) shouldBe 150.hearth
    }

    "keeps reserves for different assets to the same miner independent" in {
      val asset    = IssuedAsset(ByteStr.fill(32)(7))
      val assets   = Seq(GenesisAssetSettings(asset.id, "RESV", 0, 1000L, 1L))
      val balances = Seq(AddrWithBalance(sender.toAddress, assets = Map(asset -> 1000L)))
      withDomain(DeterministicFinality, balances, assets = assets) { d =>
        val blockchain = withRegisteredMiner(d.blockchain, miner)

        val hearthTx  = TxHelpers.reserve(sender, asset = Hearth, amount = 10.hearth, miner = miner)
        val snapshot1 = ReserveTransactionDiff(blockchain)(hearthTx).explicitGet()

        val blockchain2 = SnapshotBlockchain(blockchain, snapshot1)
        val assetTx     = TxHelpers.reserve(sender, asset = asset, amount = 5L, miner = miner)
        val snapshot2   = ReserveTransactionDiff(blockchain2)(assetTx).explicitGet()
        val blockchain3 = SnapshotBlockchain(blockchain2, snapshot2)

        blockchain3.reservedAmount(sender.toAddress, miner, Hearth) shouldBe 10.hearth
        blockchain3.reservedAmount(sender.toAddress, miner, asset) shouldBe 5L
      }
    }
  }
}
