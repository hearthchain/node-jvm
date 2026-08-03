package tech.hearth.generator

import com.google.common.primitives.{Bytes, Ints}
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.explicitGet
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.lease.LeaseTransaction
import tech.hearth.transaction.{Transaction, TxHelpers}
import tech.hearth.utils.Time
import pureconfig.ConfigReader
import tech.hearth.crypto.SigningKey

import java.nio.charset.StandardCharsets
import scala.util.{Random, Try}

// Issue is gone (see CLAUDE.md's Transaction JSON notes), so there is no way to mint a fresh asset for preconditions
// any more - only transfers and leases survive here.
object Preconditions {
  private val Fee = 1500000L

  given ConfigReader[SigningKey] = ConfigReader[String].map(s =>
    SigningKey.fromSeed(tech.hearth.crypto.secureHash(Bytes.concat(Ints.toByteArray(0), s.getBytes(StandardCharsets.UTF_8))))
  )

  given ConfigReader[Address] = ConfigReader.fromStringTry(str => Try(Address.fromString(str).explicitGet()))

  final case class PGenSettings(faucet: SigningKey, balance: Long, leasesCount: Int) derives ConfigReader

  final case class UniverseHolder(leases: List[LeaseTransaction] = Nil)

  def mk(
      settings: PGenSettings,
      accounts: Seq[SigningKey],
      time: Time
  ): (UniverseHolder, List[Transaction], List[Transaction]) = {
    val transfers = accounts.map { account =>
      TxHelpers.transfer(settings.faucet, account.toAddress, settings.balance, Waves, Fee, Waves, ByteStr.empty, time.correctedTime())
    }.toList

    val leaseTxs = (1 to settings.leasesCount).map { _ =>
      val rndAccount = Random.nextInt(accounts.size - 1)

      TxHelpers.lease(
        accounts(rndAccount),
        GeneratorSettings.toKeyPair(Random.nextString(10)).toAddress,
        1 + Random.nextInt(1000),
        Fee,
        time.correctedTime()
      )
    }.toList

    (UniverseHolder(leaseTxs), transfers, leaseTxs)
  }

}
