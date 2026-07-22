package com.wavesplatform.wallet

import com.google.common.primitives.{Bytes, Ints}
import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.settings.WalletSettings
import com.wavesplatform.transaction.TxValidationError.MissingSenderPrivateKey
import com.wavesplatform.utils.*
import play.api.libs.json.*
import tech.hearth.crypto.SigningKey

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

  def generateNewAccount(seed: Array[Byte], nonce: Int): SigningKey =
    SigningKey.fromSeed(generateAccountSeed(seed, nonce))

  def generateAccountSeed(seed: Array[Byte], nonce: Int): Array[Byte] =
    crypto.secureHash(Bytes.concat(Ints.toByteArray(nonce), seed))

  @throws[IllegalArgumentException]("if invalid wallet configuration provided")
  def apply(settings: WalletSettings): Wallet =
    new WalletImpl(settings.file, settings.password, settings.seed)

  private final case class WalletData(seed: ByteStr, accountSeeds: Set[ByteStr], nonce: Int)

  private object WalletData {
    implicit val walletFormat: Format[WalletData] = Json.format
  }

  private final class WalletImpl(maybeFile: Option[File], passwordOpt: Option[String], maybeSeedFromConfig: Option[ByteStr])
      extends ScorexLogging
      with Wallet {

    private lazy val encryptionKey = {
      val password = passwordOpt.getOrElse(PasswordProvider.askPassword())
      JsonFileStorage.prepareKey(password)
    }

    private lazy val actualSeed = maybeSeedFromConfig.getOrElse {
      val randomSeed = ByteStr(randomBytes(64))
      log.info(s"Your randomly generated seed is ${randomSeed.toString}")
      randomSeed
    }

    private var walletData: WalletData = {
      if (maybeFile.isEmpty)
        WalletData(actualSeed, Set.empty, 0)
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
              require(maybeSeedFromConfig.forall(_ == walletData.seed), "Seed from config doesn't match the actual seed")
              walletData
          }
        } else {
          WalletData(actualSeed, Set.empty, 0)
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
      val accounts = walletData.accountSeeds.map(seed => SigningKey.fromSeed(seed.arr))
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
      val before = walletData.accountSeeds.size
      // SigningKey doesn't expose its seed, so the stored seed is matched by the address it derives
      walletData = walletData.copy(
        accountSeeds = walletData.accountSeeds.filterNot(seed => SigningKey.fromSeed(seed.arr).toAddress == account.toAddress)
      )
      accountsCache -= account.toAddress.toString
      saveWalletFile()
      before > walletData.accountSeeds.size
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
      val accountSeed = Wallet.generateAccountSeed(actualSeed.arr, nonce)
      val account     = SigningKey.fromSeed(accountSeed)
      val address     = account.toAddress.toString

      if (accountsCache.contains(address)) None
      else {
        accountsCache += address -> account
        walletData = walletData.copy(accountSeeds = walletData.accountSeeds + ByteStr(accountSeed))
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
