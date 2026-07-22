package com.wavesplatform.account

import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.TxValidationError.InvalidAddress
import com.wavesplatform.utils.base16Length
import play.api.libs.json.*

import scala.jdk.OptionConverters.*

type Address = tech.hearth.crypto.Address

object Address {
  val Prefix: String           = "address:"
  val AddressVersion: Byte     = 1
  val ChecksumLength: Int      = 4
  val HashLength: Int          = 20
  val AddressLength: Int       = 1 + 1 + HashLength + ChecksumLength
  val AddressStringLength: Int = base16Length(AddressLength)

  def fromPublicKey(publicKey: PublicKey): Address = tech.hearth.crypto.Address.fromPublicKey(publicKey.arr)

  def fromBytes(addressBytes: Array[Byte]): Either[InvalidAddress, Address] =
    tech.hearth.crypto.Address.fromBytes(addressBytes).toScala.toRight(InvalidAddress("Invalid address"))

  def fromString(addressStr: String): Either[ValidationError, Address] =
    tech.hearth.crypto.Address.parse(addressStr).toScala.toRight(InvalidAddress("Invalid address"))

  implicit val jsonFormat: Format[Address] = Format[Address](
    Reads(jsValue => fromString(jsValue.as[String]).fold(err => JsError(err.toString), JsSuccess(_))),
    Writes(addr => JsString(addr.toBech32))
  )
}
