package com.wavesplatform.state.diffs

import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithDomain
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.lagonaki.mocks.TestBlock.create as block
import com.wavesplatform.settings.{FunctionalitySettings, GenesisAssetSettings, TestFunctionalitySettings}
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.ScriptsAndSponsorship
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.transaction.{Asset, TxHelpers, TxVersion}
import tech.hearth.crypto.SigningKey

class MassTransferTransactionDiffTest extends PropSpec with WithDomain {

  val fs: FunctionalitySettings =
    TestFunctionalitySettings.Enabled

  // The master is credited by the genesis snapshot, which is applied to the block at height 1
  val master: SigningKey                  = TxHelpers.signer(1)
  val masterBalance: Seq[AddrWithBalance] = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, master)

  property("MassTransfer preserves balance invariant") {
    def testDiff(transferCount: Int): Unit = {
      val transfers = (1 to transferCount).map(idx => TxHelpers.address(idx + 50) -> (100000L + idx))
      val transfer  = TxHelpers.massTransfer(master, transfers, Waves, fee = 1.waves)

      withDomain(ScriptsAndSponsorship, masterBalance) { d =>
        d.appendBlock(transfer)

        val carryFee = -transfer.fee.value * 3 / 5
//        assertBalanceInvariant(d.liquidSnapshot, d.rocksDBWriter, carryFee)

        d.liquidSnapshot.balances shouldBe (Map((master.toAddress, Waves) -> 100L) ++ transfers.map { case (a, b) => (a -> Waves) -> b}.toMap)

        val totalAmount = transfer.transfers.map(_.amount.value).sum
        val fees        = transfer.fee.value
        d.balance(transfer.sender.toAddress) shouldBe ENOUGH_AMT - fees - totalAmount
        for (ParsedTransfer(recipient, amount) <- transfer.transfers) {
          if (transfer.sender.toAddress != recipient) {
            d.balance(recipient) shouldBe amount.value
          }
        }
      }
    }

    import com.wavesplatform.transaction.transfer.MassTransferTransaction.MaxTransferCount as Max
    Seq(0, 1, 5, Max) foreach testDiff // test edge cases
  }

  property("MassTransfer fails on non-issued asset") {
    val recipient = TxHelpers.address(2)
    val asset     = IssuedAsset(ByteStr.fill(32)(1))
    val assets = Seq(GenesisAssetSettings(asset.id, ByteStr.fill(32)(3).toString, "AAAA", 8, 100000000))
    val transfer =
      TxHelpers.massTransfer(master, Seq(recipient -> 100000L), asset)

    assertDiffEi(Seq(block(Seq())), block(Seq(transfer)), fs, masterBalance :+ ((TxHelpers.signer(10), asset, 100000000L)), assets) { blockDiffEi =>
      blockDiffEi should produce("Attempt to transfer unavailable funds")
    }
  }

  property("MassTransfer cannot overspend funds") {
    val recipients = Seq(2, 3).map(idx => TxHelpers.address(idx) -> (ENOUGH_AMT / 2 + 1))
    val transfer   = TxHelpers.massTransfer(master, recipients, Waves)

    assertDiffEi(Seq(block(Seq())), block(Seq(transfer)), fs, masterBalance) { blockDiffEi =>
      blockDiffEi should produce("Attempt to transfer unavailable funds")
    }
  }
}
