package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.lagonaki.mocks.TestBlock.create as block
import tech.hearth.settings.{FunctionalitySettings, GenesisAssetSettings, TestFunctionalitySettings}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.ScriptsAndSponsorship
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.transfer.TransferTransaction.ParsedTransfer
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
      val transfer  = TxHelpers.massTransfer(master, transfers, Hearth, fee = 1.hearth)

      withDomain(ScriptsAndSponsorship, masterBalance) { d =>
        d.appendBlock(transfer)

        val carryFee = -transfer.fee.value * 3 / 5 + 6.hearth
        assertBalanceInvariant(d.liquidSnapshot, d.rocksDBWriter, carryFee)

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

    import tech.hearth.transaction.transfer.TransferTransaction.MaxTransferCount as Max
    Seq(0, 1, 5, Max) foreach testDiff // test edge cases
  }

  property("MassTransfer fails on non-issued asset") {
    val recipient = TxHelpers.address(2)
    val asset     = IssuedAsset(ByteStr.fill(32)(1))
    val assets    = Seq(GenesisAssetSettings(asset.id, "AAAA", 8, 100000000, TestValues.fee))
    val transfer =
      TxHelpers.massTransfer(master, Seq(recipient -> 100000L), asset)

    assertDiffEi(Seq(block(Seq())), block(Seq(transfer)), fs, masterBalance :+ ((TxHelpers.signer(10), asset, 100000000L)), assets) { blockDiffEi =>
      blockDiffEi should produce("Attempt to transfer unavailable funds")
    }
  }

  property("MassTransfer cannot overspend funds") {
    val recipients = Seq(2, 3).map(idx => TxHelpers.address(idx) -> (ENOUGH_AMT / 2 + 1))
    val transfer   = TxHelpers.massTransfer(master, recipients, Hearth)

    assertDiffEi(Seq(block(Seq())), block(Seq(transfer)), fs, masterBalance) { blockDiffEi =>
      blockDiffEi should produce("Attempt to transfer unavailable funds")
    }
  }

  property("MassTransfer fee in an asset at or above its minAssetFee is accepted, below is rejected") {
    val feeAsset = IssuedAsset(ByteStr.fill(32)(2))
    val minFee   = 1000L
    val assets   = Seq(GenesisAssetSettings(feeAsset.id, "Fee", 0, 10000L, minFee))
    val balances = Seq(
      AddrWithBalance(TxHelpers.defaultSigner.toAddress),
      AddrWithBalance(master.toAddress, TestValues.bigMoney, Map(feeAsset -> 10000L))
    )
    val recipient = TxHelpers.address(2)

    val okTransfer = TxHelpers.massTransfer(master, Seq(recipient -> 1L), Hearth, fee = minFee, feeAsset = feeAsset)
    assertDiffEi(Seq(block(Seq())), block(Seq(okTransfer)), fs, balances, assets) { blockDiffEi =>
      blockDiffEi should beRight
    }

    val tooLowTransfer = TxHelpers.massTransfer(master, Seq(recipient -> 1L), Hearth, fee = minFee - 1, feeAsset = feeAsset)
    assertDiffEi(Seq(block(Seq())), block(Seq(tooLowTransfer)), fs, balances, assets) { blockDiffEi =>
      blockDiffEi should produce("does not exceed minimal value")
    }
  }

  property("MassTransfer fee in a non-existent asset is rejected") {
    val unknownAsset = IssuedAsset(ByteStr.fill(32)(3))
    val recipient    = TxHelpers.address(2)
    val transfer     = TxHelpers.massTransfer(master, Seq(recipient -> 1L), Hearth, fee = TestValues.fee, feeAsset = unknownAsset)

    assertDiffEi(Seq(block(Seq())), block(Seq(transfer)), fs, masterBalance) { blockDiffEi =>
      blockDiffEi should produce("does not exist, cannot be used to pay fees")
    }
  }
}
