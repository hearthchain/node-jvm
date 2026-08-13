package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.lagonaki.mocks.TestBlock.create as block
import tech.hearth.settings.{FunctionalitySettings, GenesisAssetSettings, TestFunctionalitySettings}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.ScriptsAndSponsorship
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.transfer.TransferTransaction.ParsedTransfer

class TransferDiffTest extends PropSpec with WithDomain {
  private val fs: FunctionalitySettings = TestFunctionalitySettings.Enabled

  private val master        = TxHelpers.signer(1)
  private val masterBalance = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, master)

  private val preconditionsAndTransfer = {
    val recipient = TxHelpers.signer(2)

    val transferV1 = TxHelpers.transfer(master, recipient.toAddress)
    val transferV2 = TxHelpers.transfer(master, recipient.toAddress)

    Seq(transferV1, transferV2)
  }

  property("transfers to recipient preserving hearth invariant") {
    preconditionsAndTransfer.foreach { transfer =>
      withDomain(ScriptsAndSponsorship, AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, master)) { d =>
        d.appendBlock(transfer)

        val carryFee = -transfer.fee.value * 3 / 5 + 6.hearth
        assertBalanceInvariant(d.liquidSnapshot, d.rocksDBWriter, carryFee)

        val ParsedTransfer(recipient, amount) = transfer.transfers.head: @unchecked
        if (transfer.sender.toAddress != recipient) {
          d.balance(recipient) shouldBe amount.value
        }
      }
    }
  }

  property("fee in an asset at or above its minAssetFee is accepted, below is rejected") {
    val asset  = IssuedAsset(ByteStr.fill(32)(9))
    val minFee = 1000L
    val assets = Seq(GenesisAssetSettings(asset.id, "Fee", 0, 10000L, minFee))
    val balances = Seq(
      AddrWithBalance(TxHelpers.defaultSigner.toAddress),
      AddrWithBalance(master.toAddress, TestValues.bigMoney, Map(asset -> 10000L))
    )
    val recipient = TxHelpers.signer(2)

    val okTransfer = TxHelpers.transfer(master, recipient.toAddress, amount = 1L, fee = minFee, feeAsset = asset)
    assertDiffEi(Seq(block(Seq())), block(Seq(okTransfer)), fs, balances, assets) { blockDiffEi =>
      blockDiffEi should beRight
    }

    val tooLowTransfer = TxHelpers.transfer(master, recipient.toAddress, amount = 1L, fee = minFee - 1, feeAsset = asset)
    assertDiffEi(Seq(block(Seq())), block(Seq(tooLowTransfer)), fs, balances, assets) { blockDiffEi =>
      blockDiffEi should produce("does not exceed minimal value")
    }
  }

  property("fee in a non-existent asset is rejected") {
    // A non-existent asset can never have a real balance entry, so disallowSendingGreaterThanBalance's combined
    // amount+fee balance check rejects it first, before feePortfolios's own "does not exist" check is ever reached.
    val unknownAsset = IssuedAsset(ByteStr.fill(32)(11))
    val recipient    = TxHelpers.signer(2)
    val transfer     = TxHelpers.transfer(master, recipient.toAddress, amount = 1L, fee = TestValues.fee, feeAsset = unknownAsset)

    assertDiffEi(Seq(block(Seq())), block(Seq(transfer)), fs, masterBalance) { blockDiffEi =>
      blockDiffEi should produce("Attempt to transfer unavailable funds")
    }
  }
}
