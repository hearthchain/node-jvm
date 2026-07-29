package com.wavesplatform.lagonaki.unit

import java.io.File
import java.nio.file.Files

import cats.syntax.option.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.settings.WalletSettings
import com.wavesplatform.test.FunSuite
import com.wavesplatform.wallet.Wallet

class WalletSpecification extends FunSuite {

  private val walletSize = 10
  val w                  = Wallet(WalletSettings(None, "cookies".some, ByteStr.decodeBase58("FQgbSAm6swGbtqA3NE8PttijPhT4N3Ufh4bHFAkyVnQz").toOption))

  test("wallet - acc creation") {
    w.generateNewAccounts(walletSize)

    w.privateKeyAccounts.size shouldBe walletSize
    // Derived from the fixed seed above, in order. Rebaselined when accounts moved to KeyTree/Ed25519 and addresses to
    // bech32: the base58 ones this pinned are not reachable from any seed any more.
    w.privateKeyAccounts.map(_.toAddress.toString) shouldBe Seq(
      "thrth1kzut0rxj9hn4pn8etx77yy9myuays8t00s4f2s",
      "thrth1pw3lu6nwl5lunxx9prj9smr6jhg0cz5afg7g7l",
      "thrth1hu3wrcx2jfeccyyvlj8f5cynhvhgskem8j2hvw",
      "thrth1vy26tvr49pal6syda48r5m60eptwcsclfhpjlm",
      "thrth18pwwtfsngyuncsz8uqp6tsfsr3jcu5dwvs3t7q",
      "thrth17ch0k2jw4mf2spk954g4rftf7whqy8gc3gp007",
      "thrth1hyh0qgnuts225nm6l2c2chkxc6y3d3mr7v6vhh",
      "thrth1mz3je8fz8ucazehcyajawjjm5adn9yjv6w9meh",
      "thrth1qehyqndn0e67gu94cnycvrtnk5yapufrznzl86",
      "thrth1754x44xr9h3l7cavp0hk4chnm5l3gt5zuwzzar"
    )
  }

  test("wallet - acc deletion") {

    val head = w.privateKeyAccounts.head
    w.deleteAccount(head)
    assert(w.privateKeyAccounts.lengthCompare(walletSize - 1) == 0)

    w.deleteAccount(w.privateKeyAccounts.head)
    assert(w.privateKeyAccounts.lengthCompare(walletSize - 2) == 0)

    w.privateKeyAccounts.foreach(w.deleteAccount)

    assert(w.privateKeyAccounts.isEmpty)
  }

  test("reopening") {
    val walletFile = Some(createTestTemporaryFile("wallet", ".dat"))

    val w1 = Wallet(WalletSettings(walletFile, "cookies".some, ByteStr.decodeBase58("FQgbSAm6swGbtqA3NE8PttijPhT4N3Ufh4bHFAkyVnQz").toOption))
    w1.generateNewAccounts(10)
    val w1PrivateKeys = w1.privateKeyAccounts

    val w2 = Wallet(WalletSettings(walletFile, "cookies".some, None))
    w2.privateKeyAccounts.nonEmpty shouldBe true
    // A SigningKey compares by identity, so the reopened accounts are compared by what identifies them instead
    w2.privateKeyAccounts.map(_.toAddress) shouldEqual w1PrivateKeys.map(_.toAddress)

    val seedError = intercept[IllegalArgumentException](Wallet(WalletSettings(walletFile, "cookies".some, ByteStr.decodeBase58("fake").toOption)))
    seedError.getMessage should include("Seed from config doesn't match the actual seed")
  }

  test("reopen with incorrect password") {
    val file = Some(createTestTemporaryFile("wallet", ".dat"))
    val w1   = Wallet(WalletSettings(file, "password".some, ByteStr.decodeBase58("FQgbSAm6swGbtqA3NE8PttijPhT4N3Ufh4bHFAkyVnQz").toOption))
    w1.generateNewAccounts(3)

    assertThrows[IllegalArgumentException] {
      Wallet(WalletSettings(file, "incorrect password".some, None))
    }
  }

  def createTestTemporaryFile(name: String, ext: String): File = {
    val file = Files.createTempFile(name, ext).toFile
    file.deleteOnExit()

    file
  }
}
