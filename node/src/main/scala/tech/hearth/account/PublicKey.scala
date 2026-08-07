package tech.hearth.account

import com.google.common.collect.Interners
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.crypto.*
import tech.hearth.transaction.TxValidationError.InvalidAddress
import tech.hearth.utils.base16Length
import play.api.libs.json.{Format, Writes}

opaque type PublicKey = ByteStr

object PublicKey {
  private val interner = Interners.newWeakInterner[PublicKey]()

  private val KeyStringLength: Int = base16Length(KeyLength)

  def isValidSize(length: Int): Boolean = length == KeyLength || length == EthereumKeyLength

  def apply(publicKey: ByteStr): PublicKey = {
    require(isValidSize(publicKey.size), s"invalid public key length: ${publicKey.arr.length}")
    interner.intern(publicKey)
  }

  def apply(publicKey: Array[Byte]): PublicKey =
    apply(ByteStr(publicKey))

  def fromBase16String(base16: String): Either[InvalidAddress, PublicKey] =
    (for {
      _     <- Either.cond(base16.length <= KeyStringLength, (), "Bad public key string length")
      bytes <- Base16.tryDecodeWithLimit(base16).toEither.left.map(ex => s"Unable to decode base16: ${ex.getMessage}")
    } yield PublicKey(bytes)).left.map(err => InvalidAddress(s"Invalid sender: $err"))

  def unapply(arg: Array[Byte]): Option[PublicKey] =
    Some(apply(arg))

  extension (pk: PublicKey) {
    def arr: Array[Byte]   = pk.arr
    def byteStr: ByteStr   = pk
    def toAddress: Address = tech.hearth.account.Address.fromPublicKey(pk)
  }

  given Format[PublicKey] = Format[PublicKey](
    tech.hearth.utils.byteStrFormat.map(this.apply),
    Writes(pk => tech.hearth.utils.byteStrFormat.writes(pk))
  )
}
