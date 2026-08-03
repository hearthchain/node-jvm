package tech.hearth.account

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.PrivateKeyLength
import play.api.libs.json.{Format, Writes}

opaque type PrivateKey = ByteStr

object PrivateKey {
  def apply(privateKey: ByteStr): PrivateKey = {
    require(privateKey.arr.length == PrivateKeyLength, s"invalid private key length: ${privateKey.arr.length}")
    privateKey
  }

  def apply(privateKey: Array[Byte]): PrivateKey =
    apply(ByteStr(privateKey))

  def unapply(arg: Array[Byte]): Option[PrivateKey] =
    Some(apply(arg))

  given Format[PrivateKey] = Format[PrivateKey](
    tech.hearth.utils.byteStrFormat.map(this.apply),
    Writes(pk => tech.hearth.utils.byteStrFormat.writes(pk))
  )

  extension (sk: PrivateKey) {
    def arr: Array[Byte] = sk.arr
  }
}
