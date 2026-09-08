package tech.hearth.lagonaki.unit

import java.io.File
import java.nio.file.Files

import cats.syntax.option.*
import tech.hearth.crypto.{Bip39, KeyTree}
import tech.hearth.settings.WalletSettings
import tech.hearth.test.FunSuite
import tech.hearth.wallet.Wallet

class WalletSpecification extends FunSuite {

  private val walletSize = 10

  // The BIP-39 English test vector, so the accounts below are checkable against any other implementation of the standard
  private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

  val w = Wallet(WalletSettings(None, "cookies".some, mnemonic.some))

  test("wallet - acc creation") {
    w.generateNewAccounts(walletSize)

    w.privateKeyAccounts.size shouldBe walletSize
    // The accounts of the BIP-39 test vector phrase above at m/44'/9381'/0..9'/0'/0', rebaselined when the wallet
    // moved from its own seed hashing to BIP-39 + SLIP-10: any implementation of that standard reaches these.
    w.privateKeyAccounts.map(_.toAddress.toString) shouldBe Seq(
      "thrth1qu57hn3ec2ansm5s89pstxjd6f47937ylcyag8",
      "thrth1t5tqrn9026kr657r4cyc0djznmychy6j6feqc6",
      "thrth175wsf9k580rsd7nruflwwr90ma8ury689xv8vd",
      "thrth1y2n853r6t49sh2jk4yrqafv67hw26mfe39zyp3",
      "thrth1hvc57gsvjnn4swykrhrx2uyrngfnqh634em6es",
      "thrth19uvmpe6ll76dav0mvk06d35att3wk7a7vvkkh7",
      "thrth1ffd4euzj4rwwqfvgksz4pxz65xq2n0z7r0kz7e",
      "thrth1nsecl8g97ceemw4yqujcfm7xtphesz4k534nqz",
      "thrth1vsnxyuwtlz4w0dnsfcha5dmen275wwyfq3pk45",
      "thrth1k7h5sgjuat9834pdu37gzm7esqp9m3za9fs8zz"
    )
  }

  test("wallet - accounts are the standard BIP-39/SLIP-10 accounts of the phrase") {
    w.privateKeyAccounts.map(_.toAddress) should contain theSameElementsAs
      (0 until walletSize).map(nonce => KeyTree.signingKey(Bip39.toSeed(mnemonic), nonce).toAddress)
  }

  test("wallet - rejects a phrase that is not BIP-39") {
    intercept[IllegalArgumentException](Wallet(WalletSettings(None, "cookies".some, "not a mnemonic".some))).getMessage should include(
      "not a valid BIP-39 phrase"
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

    val w1 = Wallet(WalletSettings(walletFile, "cookies".some, mnemonic.some))
    w1.generateNewAccounts(10)
    val w1PrivateKeys = w1.privateKeyAccounts

    val w2 = Wallet(WalletSettings(walletFile, "cookies".some, None))
    w2.privateKeyAccounts.nonEmpty shouldBe true
    // A SigningKey compares by identity, so the reopened accounts are compared by what identifies them instead
    w2.privateKeyAccounts.map(_.toAddress) shouldEqual w1PrivateKeys.map(_.toAddress)

    val otherMnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    val mnemonicError = intercept[IllegalArgumentException](Wallet(WalletSettings(walletFile, "cookies".some, otherMnemonic.some)))
    mnemonicError.getMessage should include("Mnemonic from config doesn't match the actual one")
  }

  test("reopen with incorrect password") {
    val file = Some(createTestTemporaryFile("wallet", ".dat"))
    val w1   = Wallet(WalletSettings(file, "password".some, mnemonic.some))
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
