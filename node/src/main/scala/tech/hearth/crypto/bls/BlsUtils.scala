package tech.hearth.crypto.bls

import cats.syntax.either.*
import tech.hearth.crypto.BlsKey as JBlsKey

import scala.jdk.CollectionConverters.*

/** Byte-array-only BLS operations backing [[BlsPublicKey]]/[[BlsSignature]], on the Basic (NUL DST) ciphersuite of
  * [[tech.hearth.crypto.BlsKey]] - see that class's doc for why: this codebase implements its own period-bound
  * proof of possession (`CommitToGenerationTransaction.mkPopMessage`) rather than relying on the POP ciphersuite's
  * dedicated PoP DST.
  */
private[bls] object BlsUtils {
  val PublicKeySizeInBytes = JBlsKey.PUBLIC_KEY_BYTES
  val SignatureSizeInBytes = JBlsKey.SIGNATURE_BYTES

  def verifyBasic(blsSigBytes: Array[Byte], message: Array[Byte], blsPkBytes: Array[Byte]): Either[String, Unit] =
    Either.raiseUnless(JBlsKey.verifyBasic(blsPkBytes, message, blsSigBytes))("Wrong BLS signature")

  /** @param sigs Validated internally
    * @return Not validated, but must be in the group
    */
  def aggSig(sigs: Iterable[Array[Byte]]): Either[String, Array[Byte]] = for {
    _ <- Either.raiseWhen(sigs.isEmpty)("Empty BLS signature list")
    aggSig <- Either
      .catchNonFatal(JBlsKey.aggregate(sigs.toList.asJava))
      .leftMap(e => s"Error aggregating BLS signatures: ${e.getMessage}")
  } yield aggSig

  /** @param aggSigBytes Validated internally
    * @param blsPks Expected to have validated public keys
    * @see https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-bls-signature-05#name-fastaggregateverify
    */
  def verifyAgg(aggSigBytes: Array[Byte], message: Array[Byte], blsPks: Iterable[Array[Byte]]): Either[String, Unit] = for {
    _ <- Either.raiseWhen(blsPks.isEmpty)("Empty BLS public key list")
    verified <- Either
      .catchNonFatal(JBlsKey.fastAggregateVerifyBasic(blsPks.toList.asJava, message, aggSigBytes))
      .leftMap(e => s"Error aggregating BLS public keys: ${e.getMessage}")
    _ <- Either.raiseUnless(verified)("Wrong BLS signature")
  } yield ()

  def validatePublicKey(bytes: Array[Byte]): Either[String, Unit] =
    Either.raiseUnless(JBlsKey.isValidPublicKey(bytes))("Invalid BLS public key")

  def sanityCheckPublicKey(bytes: Array[Byte]): Either[String, Unit] =
    Either.raiseUnless(bytes.length == PublicKeySizeInBytes) {
      s"Unexpected BLS public key length: ${bytes.length}, expected $PublicKeySizeInBytes"
    }

  // Not validating like public key, because it is validated internally
  def sanityCheckSignature(bytes: Array[Byte]): Either[String, Unit] =
    Either.raiseUnless(bytes.length == SignatureSizeInBytes) {
      s"Unexpected BLS signature length: ${bytes.length}, expected $SignatureSizeInBytes"
    }
}
