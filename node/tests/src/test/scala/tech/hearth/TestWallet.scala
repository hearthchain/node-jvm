package tech.hearth

import com.google.common.primitives.Longs
import tech.hearth.common.state.ByteStr
import tech.hearth.settings.WalletSettings
import tech.hearth.wallet.Wallet

trait TestWallet {
  protected val testWallet: Wallet = TestWallet.instance
}

object TestWallet {
  private[TestWallet] lazy val instance = Wallet(WalletSettings(None, Some("123"), Some(ByteStr(Longs.toByteArray(System.nanoTime())))))
}
