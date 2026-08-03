package tech.hearth

import tech.hearth.common.utils.Base58
import tech.hearth.crypto.secureHash
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.crypto.SigningKey

import scala.util.{Failure, Success}

/** Docker-based integration test fixtures identify accounts by an arbitrary-length seed (a node's `account-seed`
  * config value, or a human-readable label like "buyer"). `SigningKey.fromSeed` only accepts a 32-byte Ed25519
  * seed, so these helpers hash the arbitrary input down first, the same way the account KeyPair this replaced
  * used to derive a key from a seed of any length.
  */
package object it {
  def keyPairFromSeed(seed: Array[Byte]): SigningKey = SigningKey.fromSeed(secureHash(seed))

  def keyPairFromSeed(base58Seed: String): Either[GenericError, SigningKey] = Base58.tryDecodeWithLimit(base58Seed) match {
    case Success(bytes) => Right(keyPairFromSeed(bytes))
    case Failure(e)     => Left(GenericError(s"Unable to get a private key from the seed '$base58Seed': ${e.getMessage}"))
  }
}
