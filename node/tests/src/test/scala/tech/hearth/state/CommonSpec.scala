package tech.hearth.state

import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.test.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers

class CommonSpec extends FreeSpec with WithDomain {

  "Common Conditions" - {
    "Zero balance of absent asset" in {
      val sender         = TxHelpers.signer(1)
      val initialBalance = 1000
      val assetId        = Array.fill(32)(1.toByte)

      withDomain(balances = Seq(AddrWithBalance(sender.toAddress, initialBalance))) { d =>
        d.balance(sender.toAddress, IssuedAsset(ByteStr(assetId))) shouldEqual 0L
      }
    }
  }
}
