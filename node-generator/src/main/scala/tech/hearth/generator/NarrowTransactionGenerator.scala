package tech.hearth.generator

import cats.Show
import com.typesafe.scalalogging.Logger
import tech.hearth.account.{AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.generator.config.ConfigReaders
import tech.hearth.generator.utils.Universe
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.assets.exchange.*
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.transfer.MassTransferTransaction.ParsedTransfer
import org.slf4j.LoggerFactory
import pureconfig.ConfigReader
import tech.hearth.crypto.SigningKey

import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.*
import scala.reflect.ClassTag
import scala.util.Random

// Every transaction type that isn't one of Transfer/Exchange/Lease/LeaseCancel/MassTransfer (Issue, Reissue, Burn,
// CreateAlias, Data, SponsorFee, InvokeScript, Ethereum, SetScript) no longer exists (see CLAUDE.md's Transaction
// JSON notes) - so this generator, and the preconditions it builds, only cover the surviving five. Aliases are gone
// too, so every recipient is a plain address.
//noinspection ScalaStyle, TypeAnnotation
class NarrowTransactionGenerator(
    settings: NarrowTransactionGenerator.Settings,
    preconditions: NarrowTransactionGenerator.Preconditions,
    accounts: Seq[SigningKey],
    override val initial: Seq[Transaction],
    override val tailInitial: Seq[Transaction]
) extends TransactionGenerator {
  import NarrowTransactionGenerator.*

  private val log     = Logger(LoggerFactory.getLogger(getClass))
  private val typeGen = DistributedRandomGenerator(settings.probabilities)

  override def next(): Iterator[Transaction] = generate(settings.transactions).iterator

  private def generate(n: Int): Seq[Transaction] = {
    val now = System.currentTimeMillis()

    val generated = (0 until (n * 1.2).toInt).foldLeft(
      (Seq.empty[Transaction], Universe.Leases)
    ) { case ((allTxsWithValid, activeLeaseTransactions), i) =>
      val timestamp = now + i

      val tx: Option[Transaction] = typeGen.getRandom match {
        case TransactionType.Transfer =>
          (
            for {
              sender    <- randomFrom(accounts)
              recipient <- randomFrom(accounts).map(_.toAddress)
              tx <- logOption(
                TransferTransaction
                  .create(
                    PublicKey(sender.publicKey()),
                    recipient,
                    Hearth,
                    Random.nextInt(100),
                    Hearth,
                    500000L,
                    createAttachment(),
                    timestamp,
                    Proofs.empty
                  )
                  .map(_.signWith(sender))
              )
            } yield tx
          ).logNone("Can't define sender/recipient of transaction, check your configuration")

        case TransactionType.Exchange =>
          (
            for {
              assetId <- preconditions.tradeAssetId
              matcher <- randomFrom(accounts)
              seller  <- randomFrom(accounts)
              buyer   <- randomFrom(accounts)
              pair  = AssetPair(Hearth, IssuedAsset(assetId))
              delta = random.nextLong(10000)
              sellOrder <- TxHelpers
                .sell(
                  Order.V2,
                  seller,
                  PublicKey(matcher.publicKey()),
                  pair,
                  10000000 + delta,
                  10,
                  timestamp,
                  timestamp + 30.days.toMillis,
                  300000L
                )
                .toOption
              buyOrder <- TxHelpers
                .buy(
                  Order.V2,
                  buyer,
                  PublicKey(matcher.publicKey()),
                  pair,
                  10000000 + delta,
                  10 + random.nextLong(10),
                  timestamp,
                  timestamp + 1.day.toMillis,
                  300000L
                )
                .toOption
            } yield TxHelpers.exchange(buyOrder, sellOrder, matcher, 10000000L + delta, 10, 300000L, 300000L, 700000L, timestamp)
          ).logNone("No trade-asset-id configured (or can't define seller/matcher/buyer), check your configuration")

        case TransactionType.Lease =>
          (
            for {
              sender    <- randomFrom(accounts)
              recipient <- randomFrom(accounts.filter(_ != sender).map(_.toAddress)) orElse Some(preconditions.leaseRecipient.toAddress)
              tx <- logOption(
                LeaseTransaction
                  .create(
                    AddressScheme.current.chainId,
                    PublicKey(sender.publicKey()),
                    recipient,
                    random.nextLong(1, 100),
                    500000L,
                    timestamp,
                    Proofs.empty
                  )
                  .map(_.signWith(sender))
              )
            } yield tx
          ).logNone("Can't define recipient of transaction, check your configuration")

        case TransactionType.LeaseCancel =>
          (
            for {
              lease  <- activeLeaseTransactions.headOption
              sender <- accountByAddress(lease.sender.toAddress.toString)
              tx <- logOption(
                LeaseCancelTransaction
                  .create(PublicKey(sender.publicKey()), lease.id(), 500000L, timestamp, Proofs.empty, AddressScheme.current.chainId)
                  .map(_.signWith(sender))
              )
            } yield tx
          ).logNone("There is no active lease transactions, may be you need to increase lease transaction's probability")

        case TransactionType.MassTransfer =>
          (
            for {
              sender <- randomFrom(accounts)
              transferCount = random.nextInt(MassTransferTransaction.MaxTransferCount)
              transfers = for (_ <- 0 until transferCount) yield {
                val recipient = randomFrom(accounts).get.toAddress
                val amount    = 1000 / (transferCount + 1)
                ParsedTransfer(recipient, TxNonNegativeAmount.unsafeFrom(amount))
              }
              tx <- logOption(
                MassTransferTransaction
                  .create(
                    PublicKey(sender.publicKey()),
                    Hearth,
                    transfers.toList,
                    100000L + 50000L * transferCount + 400000L,
                    timestamp,
                    createAttachment(),
                    Proofs.empty
                  )
                  .map(_.signWith(sender))
              )
            } yield tx
          ).logNone("Can't define sender of transaction, check your configuration")

        case _ => ???
      }

      (
        tx.fold(allTxsWithValid)(tx => allTxsWithValid :+ tx),
        tx match {
          case Some(tx: LeaseTransaction)       => activeLeaseTransactions :+ tx
          case Some(tx: LeaseCancelTransaction) => activeLeaseTransactions.filter(_.id() != tx.leaseId)
          case _                                => activeLeaseTransactions
        }
      )
    }

    Universe.Leases = generated._2

    log.trace(s"Distribution:\n${generated._1.groupBy(_.getClass).view.mapValues(_.size).mkString("\t", "\n\t", "")}")

    generated._1
  }

  private def createAttachment(): ByteStr = {
    if (random.nextBoolean()) ByteStr.empty
    else ByteStr(Array.fill(random.nextInt(100))(random.nextInt().toByte))
  }

  private def logOption[T <: Transaction](txE: Either[ValidationError, T])(implicit m: ClassTag[T]): Option[T] = {
    txE match {
      case Left(e) =>
        log.warn(s"${m.runtimeClass.getName}: ${e.toString}")
        None
      case Right(tx) => Some(tx)
    }
  }

  private def accountByAddress(address: String): Option[SigningKey] =
    accounts
      .find(_.toAddress.toString == address)

  private implicit class OptionExt[A](opt: Option[A]) {
    def logNone(msg: => String): Option[A] =
      opt match {
        case None =>
          log.warn(msg)
          None
        case Some(_) => opt
      }
  }
}

object NarrowTransactionGenerator extends ConfigReaders {

  final case class Settings(
      transactions: Int,
      probabilities: Map[TransactionType, Double],
      tradeAssetId: Option[String],
      protobuf: Boolean
  ) derives ConfigReader

  object Settings {
    implicit val toPrintable: Show[Settings] = { x =>
      import x.*
      s"""transactions per iteration: $transactions
         |probabilities:
         |  ${probabilities.mkString("\n  ")}""".stripMargin
    }
  }

  final case class Preconditions(
      leaseRecipient: SigningKey,
      tradeAssetId: Option[ByteStr]
  )

  private def random = ThreadLocalRandom.current

  private def randomFrom[T](c: Seq[T]): Option[T] = if (c.nonEmpty) Some(c(random.nextInt(c.size))) else None

  def apply(settings: Settings, accounts: Seq[SigningKey]): NarrowTransactionGenerator = {
    val leaseRecipient = GeneratorSettings.toKeyPair("lease recipient")
    val tradeAssetId   = settings.tradeAssetId.map(id => ByteStr.decodeBase16(id).get)

    val preconditions = Preconditions(leaseRecipient, tradeAssetId)

    new NarrowTransactionGenerator(settings, preconditions, accounts, Seq.empty, Seq.empty)
  }
}
