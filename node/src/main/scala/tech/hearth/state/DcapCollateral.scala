package tech.hearth.state

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.dcap.IntelPki

/** Verification shared by every path that can set DCAP collateral: the permissionless UpdateCollateralTransaction
  * (see the StartBoost consensus plan) and PredefinedSnapshot, which seeds a network's initial collateral at
  * genesis - everything required to run the chain (including the Root CA CRL later issuer-chain verifications
  * depend on for revocation checking) is set up there, not left to arrive from the first block onward.
  *
  * Each function is pure given the blockchain to read the currently-stored value from: parse, verify the Intel
  * signature, and reject a payload that is not at least as fresh as what's already stored (a stale resubmission
  * downgrading, say, a revoked TCB Info back to one that reads UpToDate).
  */
object DcapCollateral {

  /** Root CA CRL is self-issued by the pinned Intel Root CA directly - no issuer chain needed, unlike every other
    * field below.
    */
  def verifyRootCaCrl(payload: ByteStr, blockchain: Blockchain, atTime: Long): Either[String, ByteStr] =
    for {
      newNumber <- IntelPki.verifyCrl(payload.arr, IntelPki.rootCaPublicKey, atTime)
      storedNumber = blockchain.dcapRootCaCrl.flatMap(stored => IntelPki.crlNumberOf(stored.arr))
      _ <- rejectDowngrade(newNumber, storedNumber)
    } yield payload

  /** pckCrl is signed by whichever PCK Platform/Processor CA issued it, an intermediate under Root CA - so its
    * issuer's public key has to come from issuerChainPayload (verified up to the pinned Root CA, checked for
    * revocation against the already-on-chain Root CA CRL) rather than from a fixed constant.
    */
  def verifyPckCrl(
      payload: ByteStr,
      issuerChainPayload: Option[ByteStr],
      blockchain: Blockchain,
      atTime: Long
  ): Either[String, ByteStr] =
    for {
      issuerKey <- resolvePckCaKey(issuerChainPayload, blockchain, atTime)
      newNumber <- IntelPki.verifyCrl(payload.arr, issuerKey, atTime)
      storedNumber = blockchain.dcapPckCrl.flatMap(stored => IntelPki.crlNumberOf(stored.arr))
      _ <- rejectDowngrade(newNumber, storedNumber)
    } yield payload

  /** The PCK CA issuer chain itself: verified and stored so a pckCrl update on its own (without resubmitting the
    * chain every time) can still resolve an issuer, the same way tcbSigningIssuerChain will for tcbInfo/qeIdentity.
    */
  def verifyPckCaIssuerChain(payload: ByteStr, blockchain: Blockchain, atTime: Long): Either[String, ByteStr] =
    resolveRootCaCrlForRevocation(blockchain).flatMap { crls =>
      IntelPki.verifyIssuerChain(payload.arr, atTime, crls).map(_ => payload)
    }

  private def resolvePckCaKey(
      issuerChainPayload: Option[ByteStr],
      blockchain: Blockchain,
      atTime: Long
  ): Either[String, java.security.PublicKey] =
    issuerChainPayload
      .map(chain => resolveRootCaCrlForRevocation(blockchain).flatMap(crls => IntelPki.verifyIssuerChain(chain.arr, atTime, crls)))
      .getOrElse {
        blockchain.dcapPckCaIssuerChain
          .toRight("no PCK CA issuer chain submitted or already on chain")
          .flatMap(chain => resolveRootCaCrlForRevocation(blockchain).flatMap(crls => IntelPki.verifyIssuerChain(chain.arr, atTime, crls)))
      }

  private def resolveRootCaCrlForRevocation(blockchain: Blockchain): Either[String, Seq[Array[Byte]]] =
    blockchain.dcapRootCaCrl
      .toRight("Root CA CRL must be set (in genesis or by an earlier UpdateCollateral) before an issuer chain can be verified")
      .map(crl => Seq(crl.arr))

  private def rejectDowngrade(newNumber: Option[BigInt], storedNumber: Option[BigInt]): Either[String, Unit] =
    (newNumber, storedNumber) match {
      case (Some(n), Some(s)) if n < s => Left(s"stale update: CRL number $n is behind the stored $s")
      case _                           => Right(())
    }
}
