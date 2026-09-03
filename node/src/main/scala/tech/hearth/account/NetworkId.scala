package tech.hearth.account

import play.api.libs.json.*
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import tech.hearth.crypto.Address

/** A network's identity: the bech32m human-readable prefix (HRP) every address on it carries - `hrth` on mainnet,
  * `thrth` on testnet, `shrth` on stagenet. It is the chain identifier: transactions, block headers and orders all
  * carry it, and it is the same value [[tech.hearth.crypto.Address.setDefaultHrp]] renders addresses with, so a node
  * cannot render addresses for one network while accepting transactions for another.
  *
  * The rule is `Address`'s own HRP rule (1 to [[MaxLength]] lowercase ASCII letters), applied strictly: unlike
  * `Address.setDefaultHrp`, nothing is trimmed or lowercased, so a value read off the wire is accepted only in the
  * exact form it was meant to be signed in.
  */
opaque type NetworkId = String

object NetworkId {

  /** bech32's own ceiling on an HRP's length. */
  val MaxLength: Int = 83

  val Mainnet: NetworkId  = unsafeFromString(Address.MAINNET_HRP)
  val Testnet: NetworkId  = unsafeFromString(Address.TESTNET_HRP)
  val Stagenet: NetworkId = unsafeFromString("shrth")

  def fromString(s: String): Either[String, NetworkId] =
    if (s.isEmpty || s.length > MaxLength) Left(s"Invalid network id '$s': expected 1 to $MaxLength characters")
    else if (!s.forall(c => c >= 'a' && c <= 'z')) Left(s"Invalid network id '$s': expected lowercase ASCII letters only")
    else Right(s)

  def unsafeFromString(s: String): NetworkId =
    fromString(s).fold(e => throw new IllegalArgumentException(e), identity)

  /** The network this JVM runs on, as pinned by [[tech.hearth.crypto.Address.setDefaultHrp]]; throws until that has
    * happened, the same way rendering an address does.
    */
  def current: NetworkId = Address.defaultHrp()

  // Both readers name the String one explicitly. Inside this object the opaque type is transparent, so an implicit
  // ConfigReader[String]/Reads[String] resolves to the one being defined here and recurses until the stack blows.
  given ConfigReader[NetworkId] =
    ConfigReader.fromString(s => fromString(s).left.map(CannotConvert(s, "NetworkId", _)))

  implicit val jsonFormat: Format[NetworkId] = Format[NetworkId](
    Reads(jsValue => Reads.StringReads.reads(jsValue).flatMap(s => fromString(s).fold(JsError(_), JsSuccess(_)))),
    Writes(id => JsString(id))
  )

  extension (id: NetworkId) def value: String = id
}
