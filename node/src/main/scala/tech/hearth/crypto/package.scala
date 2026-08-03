package tech.hearth

import tech.hearth.account.{PrivateKey, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.utils.*
import tech.hearth.crypto.{Crypto, Ecvrf}

import scala.jdk.OptionConverters.*

package object crypto {
  // Constants
  val SignatureLength: Int   = 64
  val KeyLength: Int         = 32
  val PrivateKeyLength       = 64
  val DigestLength: Int      = 32
  val EthereumKeyLength: Int = 64

  // Digests
  def fastHash(m: Array[Byte]): Array[Byte]   = Blake2b256.hash(m)
  def fastHash(s: String): Array[Byte]        = fastHash(s.utf8Bytes)
  def secureHash(m: Array[Byte]): Array[Byte] = Keccak256.hash(Blake2b256.hash(m))
  def secureHash(s: String): Array[Byte]      = secureHash(s.utf8Bytes)

  // Signatures
  def sign(account: PrivateKey, message: Array[Byte]): ByteStr =
    ByteStr(Crypto.defaultBackend().signDetached(message, account.arr))

  def verify(signature: ByteStr, message: Array[Byte], publicKey: PublicKey): Boolean =
    Crypto.defaultBackend().verifyDetached(signature.arr, message, publicKey.arr)

  /** Verifies an ECVRF proof and returns its output beta, which is the next hit source.
    *
    * @param signature
    *   The proof pi
    * @param message
    *   The VRF input alpha, i.e. the previous hit source
    * @param publicKey
    *   The VRF public key the proof is checked against
    */
  /** @param vrfPublicKey
    *   A VRF public key, which is derived independently of the account's signing key and so is not a PublicKey
    */
  def verifyVRF(signature: ByteStr, message: Array[Byte], vrfPublicKey: ByteStr): Either[ValidationError, ByteStr] =
    Ecvrf
      .verify(vrfPublicKey.arr, message, signature.arr)
      .toScala
      .map(ByteStr(_))
      .toRight(GenericError(s"Invalid VRF proof $signature"))
}
