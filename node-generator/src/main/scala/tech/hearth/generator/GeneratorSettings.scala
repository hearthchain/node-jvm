package tech.hearth.generator

import cats.Show
import cats.implicits.showInterpolator
import com.google.common.primitives.{Bytes, Ints}
import tech.hearth.generator.GeneratorSettings.NodeAddress
import tech.hearth.generator.config.ConfigReaders
import pureconfig.ConfigReader
import pureconfig.generic.derivation.*
import tech.hearth.crypto.SigningKey

import java.net.{InetSocketAddress, URI, URL}
import java.nio.charset.StandardCharsets
import scala.util.Try

case class GeneratorSettings(
    chainId: String,
    accounts: Seq[String],
    sendTo: Seq[NodeAddress],
    worker: Worker.Settings,
    mode: Mode,
    narrow: NarrowTransactionGenerator.Settings,
    wide: WideTransactionGenerator.Settings,
    dynWide: DynamicWideTransactionGenerator.Settings
) derives ConfigReader {
  val addressScheme: Char                 = chainId.head
  val privateKeyAccounts: Seq[SigningKey] = accounts.map(s => GeneratorSettings.toKeyPair(s))
}

object GeneratorSettings extends ConfigReaders {
  given ConfigReader[InetSocketAddress] = ConfigReader.fromStringTry(str =>
    Try {
      val url = new URI(s"my://$str")
      new InetSocketAddress(url.getHost, url.getPort)
    }
  )

  given ConfigReader[URL] = ConfigReader[String].map(str => URI.create(str).toURL)

  case class NodeAddress(networkAddress: InetSocketAddress, apiAddress: URL) derives ConfigReader

  implicit val toPrintable: Show[GeneratorSettings] = { x =>
    import x.*

    val modeSettings: String = (mode: @unchecked) match {
      case Mode.NARROW   => show"$narrow"
      case Mode.WIDE     => show"$wide"
      case Mode.DYN_WIDE => show"$dynWide"
    }

    s"""network byte: $chainId
       |rich accounts:
       |  ${accounts.mkString("\n  ")}
       |recipient nodes:
       |  ${sendTo.mkString("\n  ")}
       |worker:
       |  ${show"$worker".split('\n').mkString("\n  ")}
       |mode: $mode
       |$mode settings:
       |  ${modeSettings.split('\n').mkString("\n  ")}""".stripMargin
  }

  def toKeyPair(seedText: String): SigningKey = {
    SigningKey.fromSeed(tech.hearth.crypto.secureHash(Bytes.concat(Ints.toByteArray(0), seedText.getBytes(StandardCharsets.UTF_8))))
  }
}
