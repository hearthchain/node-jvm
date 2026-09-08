package tech.hearth

import tech.hearth.crypto.Mnemonic
import tech.hearth.settings.WalletSettings
import tech.hearth.wallet.Wallet

trait TestWallet {
  protected val testWallet: Wallet = TestWallet.instance
}

object TestWallet {
  private[TestWallet] lazy val instance = Wallet(WalletSettings(None, Some("123"), Some(Mnemonic.generate())))
}
