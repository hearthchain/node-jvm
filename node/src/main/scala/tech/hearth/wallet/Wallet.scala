package tech.hearth.wallet

import tech.hearth.account.Address
import tech.hearth.lang.ValidationError
import tech.hearth.settings.WalletSettings
import tech.hearth.transaction.TxValidationError.MissingSenderPrivateKey
import tech.hearth.utils.*
import play.api.libs.json.*
import tech.hearth.crypto.{Bip39, KeyTree, Mnemonic, SigningKey}

import java.io.File
import scala.collection.concurrent.TrieMap
import scala.util.{Failure, Success, Try}
import scala.util.chaining.*

trait Wallet {
  def privateKeyAccounts: Seq[SigningKey]
  def generateNewAccounts(howMany: Int): Seq[SigningKey]
  def generateNewAccount(): Option[SigningKey]
  def generateNewAccount(nonce: Int): Option[SigningKey]
  def deleteAccount(account: SigningKey): Boolean
  def signingKey(account: Address): Either[ValidationError, SigningKey]
}

object Wallet {
  implicit class WalletExtension(private val wallet: Wallet) extends AnyVal {
    def findPrivateKey(addressString: String): Either[ValidationError, SigningKey] =
      for {
        acc        <- Address.fromString(addressString)
        privKeyAcc <- wallet.signingKey(acc)
      } yield privKeyAcc
  }

  /** The account at a derivation index of a BIP-39 phrase, as [[KeyTree]] derives it (SLIP-10, `m/44'/9381'/n'/0'/0'`),
    * so any wallet that implements the same standard reaches the same key from the same phrase.
    */
  def account(mnemonic: String, nonce: Int): SigningKey = KeyTree.signingKey(Bip39.toSeed(mnemonic), nonce)

  @throws[IllegalArgumentException]("if invalid wallet configuration provided")
  def apply(settings: WalletSettings): Wallet =
    new WalletImpl(settings.file, settings.password, settings.mnemonic)

  /** Accounts are stored as the derivation indices they are, not as key material: the phrase rebuilds every one of
    * them, and a wallet file that held seeds could not be carried to another implementation.
    */
  private final case class WalletData(mnemonic: String, accounts: Set[Int], nonce: Int)

  private object WalletData {
    implicit val walletFormat: Format[WalletData] = Json.format
  }

  private final class WalletImpl(maybeFile: Option[File], passwordOpt: Option[String], maybeMnemonicFromConfig: Option[String])
      extends ScorexLogging
      with Wallet {

    require(maybeMnemonicFromConfig.forall(Mnemonic.isValid), "Wallet mnemonic is not a valid BIP-39 phrase")

    private lazy val encryptionKey = {
      val password = passwordOpt.getOrElse(PasswordProvider.askPassword())
      JsonFileStorage.prepareKey(password)
    }

    private lazy val actualMnemonic = maybeMnemonicFromConfig.getOrElse {
      val generated = Mnemonic.generate()
      log.info(s"Your randomly generated mnemonic is: $generated")
      generated
    }

    private var walletData: WalletData = {
      if (maybeFile.isEmpty)
        WalletData(actualMnemonic, Set.empty, 0)
      else {
        def loadOrImport(walletFile: File): Try[WalletData] =
          Try(JsonFileStorage.load[WalletData](walletFile.getCanonicalPath, Some(this.encryptionKey)))

        val file = maybeFile.get
        if (file.isFile && file.length() > 0) {
          loadOrImport(maybeFile.get) match {
            case Failure(exception) =>
              throw new IllegalArgumentException(
                s"Failed to open existing wallet file '${maybeFile.get}' maybe provided password is incorrect",
                exception
              )
            case Success(walletData) =>
              require(maybeMnemonicFromConfig.forall(_ == walletData.mnemonic), "Mnemonic from config doesn't match the actual one")
              walletData
          }
        } else {
          WalletData(actualMnemonic, Set.empty, 0)
        }
      }
    }

    private object WalletLock {
      private val lockObject   = new Object
      def write[T](f: => T): T = lockObject.synchronized(f)
    }

    /** Keyed by [[Address.toString]], the network-independent form, so that the wallet does not depend on a configured
      * default network. [[signingKey]] looks accounts up the same way.
      */
    private val accountsCache: TrieMap[String, SigningKey] = {
      val accounts = walletData.accounts.map(Wallet.account(walletData.mnemonic, _))
      TrieMap(accounts.map(acc => acc.toAddress.toString -> acc).toSeq*)
    }

    override def privateKeyAccounts: Seq[SigningKey] =
      this.accountsCache.values.toVector

    override def generateNewAccounts(howMany: Int): Seq[SigningKey] =
      (1 to howMany)
        .flatMap(_ => this.generateNewAccountWithoutSave())
        .tap(_ => this.saveWalletFile())

    override def generateNewAccount(): Option[SigningKey] = WalletLock.write {
      generateNewAccount(getAndIncrementNonce())
    }

    override def generateNewAccount(nonce: Int): Option[SigningKey] = WalletLock.write {
      generateNewAccountWithoutSave(nonce).map(acc => {
        this.saveWalletFile()
        acc
      })
    }

    override def deleteAccount(account: SigningKey): Boolean = WalletLock.write {
      val before = walletData.accounts.size
      // SigningKey compares by identity, so the stored index is matched by the address it derives
      walletData = walletData.copy(
        accounts = walletData.accounts.filterNot(nonce => Wallet.account(actualMnemonic, nonce).toAddress == account.toAddress)
      )
      accountsCache -= account.toAddress.toString
      saveWalletFile()
      before > walletData.accounts.size
    }

    override def signingKey(account: Address): Either[ValidationError, SigningKey] =
      accountsCache.get(account.toString).toRight[ValidationError](MissingSenderPrivateKey)

    def nonce: Int =
      walletData.nonce

    def saveWalletFile(): Unit =
      maybeFile.foreach(f => JsonFileStorage.save(walletData, f.getCanonicalPath, Some(encryptionKey)))

    private def generateNewAccountWithoutSave(): Option[SigningKey] = WalletLock.write {
      generateNewAccountWithoutSave(getAndIncrementNonce())
    }

    private def generateNewAccountWithoutSave(nonce: Int): Option[SigningKey] = WalletLock.write {
      val account = Wallet.account(actualMnemonic, nonce)
      val address = account.toAddress.toString

      if (accountsCache.contains(address)) None
      else {
        accountsCache += address -> account
        walletData = walletData.copy(accounts = walletData.accounts + nonce)
        log.info(s"Added account #${privateKeyAccounts.size}")
        Some(account)
      }
    }

    private def getAndIncrementNonce(): Int = WalletLock.write {
      val oldNonce = walletData.nonce
      walletData = walletData.copy(nonce = walletData.nonce + 1)
      oldNonce
    }
  }
}
