package com.wavesplatform.state.diffs

import com.wavesplatform.account.AddressScheme
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.db.WithState
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.state.Height
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.exchange.OrderType
import com.wavesplatform.transaction.transfer.*
import com.wavesplatform.transaction.{Transaction, TxHelpers}

class CommonValidationTest extends PropSpec with WithState {
  private val master = TxHelpers.signer(1)

  // The master is credited by the genesis snapshot, which is applied to the block at height 1
  private val masterBalance = Seq(AddrWithBalance(master.toAddress))

  property("disallows double spending") {
    val preconditionsAndPayment: Seq[TransferTransaction] = {
      val recipients = Seq(master, TxHelpers.signer(2))

      recipients.map { recipient =>
        TxHelpers.transfer(master, recipient.toAddress)
      }
    }

    preconditionsAndPayment.foreach { transfer =>
      assertDiffEi(Seq(TestBlock.create(Seq(transfer))), TestBlock.create(Seq(transfer)), balances = masterBalance) { blockDiffEi =>
        blockDiffEi should produce("AlreadyInTheState")
      }

      assertDiffEi(Seq(TestBlock.create(Seq())), TestBlock.create(Seq(transfer, transfer)), balances = masterBalance) { blockDiffEi =>
        blockDiffEi should produce("AlreadyInTheState")
      }
    }
  }

  property("disallows other network") {
    val preconditionsAndPayment: Seq[Transaction] = {
      val recipient = TxHelpers.signer(2)

      val amount = 100.waves
      val asset  = IssuedAsset(ByteStr.fill(32)(1))

      val invChainId    = '#'.toByte
      val invChainAddr  = recipient.toAddress
      Seq(
        TxHelpers.transfer(master, invChainAddr, amount, chainId = invChainId),
        TxHelpers.lease(master, invChainAddr, amount, chainId = invChainId),
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(OrderType.BUY, asset, Waves, Waves, amount, 1_0000_0000L, fee = 1L, sender = master),
          TxHelpers.order(OrderType.SELL, asset, Waves, Waves, amount, 1_0000_0000L, fee = 1L, sender = recipient),
          master,
          chainId = invChainId
        ),
        TxHelpers.massTransfer(master, Seq(invChainAddr -> amount), chainId = invChainId),
        TxHelpers.leaseCancel(asset.id, master, chainId = invChainId),
        TxHelpers.commitToGeneration(Height(3000), chainId = invChainId)
      )
    }

    preconditionsAndPayment.foreach { tx =>
      tx.chainId should not be AddressScheme.current.chainId
      assertDiffEi(Seq(TestBlock.create(Seq())), TestBlock.create(Seq(tx)), balances = masterBalance) { blockDiffEi =>
        blockDiffEi should produce("Address belongs to another network")
      }
    }
  }
}
