package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.P256Curve
import tech.hearth.crypto.dcap.{DcapQuote, IntelPki}
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.StartBoostTransaction
import tech.hearth.transaction.TxValidationError.{ActivationError, GenericError}

/** StartBoostTransaction semantics: verify a TDX quote end to end and register its enclave for the next generation
  * period (see the StartBoost consensus plan). `StartBoostTxValidator` already rejected a malformed or SGX quote
  * before this ever runs, but re-parses here too since nothing carries the parsed value across from validation.
  *
  * Deliberately out of scope for this pass, both flagged rather than silently skipped: the on-chain tcbInfo for the
  * quote's FMSPC is only checked for *presence*, not for its TCB status (UpToDate/OutOfDate/Revoked, S6.2.2 of
  * Intel's DCAP spec) or re-checked for freshness at StartBoost time (only at the UpdateCollateral submission that
  * put it on chain) - a TEE running under a since-downgraded TCB is not yet rejected. Neither has a settled design
  * yet; don't guess at either from this comment without checking whether one has landed since.
  */
object StartBoostTransactionDiff {

  /** How many blocks back a quote's embedded block id may be and still count as fresh - see "Freshness window" in
    * the StartBoost consensus plan (K=100). Bounds how long a leaked quote stays replayable, while leaving enough
    * room for a TEE miner's fetch-block/generate-quote/broadcast round trip to land inside the window.
    */
  val FreshnessWindowBlocks = 100

  def apply(blockchain: Blockchain)(tx: StartBoostTransaction): Either[ValidationError, StateSnapshot] =
    for {
      quote <- DcapQuote.parse(tx.tdxQuote.arr).leftMap(e => GenericError(s"Invalid quote: $e"))
      _     <- Either.raiseUnless(quote.header.teeType == DcapQuote.TdxTeeType)(GenericError("SGX quotes are not accepted"))
      tdReportData <- quote.body match {
        case DcapQuote.Td10QuoteBody(body) => Right(body.userReportData)
        case DcapQuote.Td15QuoteBody(body) => Right(body.td10.userReportData)
        case DcapQuote.SgxQuoteBody(_)     => Left(GenericError("SGX quotes are not accepted"))
      }
      current <- blockchain.currentGenerationPeriod.toRight(ActivationError("DeterministicFinality is not yet activated"))
      next = current.next
      _ <- Either.raiseUnless(tx.generationPeriodStart == next.start) {
        GenericError(s"Expected the next period start height ${next.start}, got ${tx.generationPeriodStart}")
      }
      _ <- Either.raiseUnless(blockchain.committedGenerators(next).exists(_.address == tx.validator)) {
        GenericError(s"${tx.validator} is not a committed generator of period $next")
      }
      _ <- verifyFreshness(blockchain, tx, tdReportData).leftMap(GenericError(_))
      _ <- verifyQuoteSignatures(blockchain, tx, quote).leftMap(GenericError(_))
      _ <- verifyTcbStatus(blockchain, quote).leftMap(GenericError(_))
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(tx.sender.toAddress -> Portfolio(balance = -tx.fee.value)),
        nextRegisteredEnclaves = Seq(RegisteredEnclave(quote.signature.attestationPubKey, tx.validator))
      )
    } yield snapshot

  /** The TD report's 64-byte report_data must equal blockId(32) ++ senderPublicKey(32) - a recent block id proves
    * the quote wasn't generated long ago, and binding the sender directly (no hashing needed: the report_data slot
    * already fits both raw, and unforgeability comes from the quote's own hardware signature over it, not from
    * hiding either value) stops a quote observed in the mempool from being replayed by a different sender.
    */
  private def verifyFreshness(blockchain: Blockchain, tx: StartBoostTransaction, reportData: ByteStr): Either[String, Unit] = {
    val (claimedBlockId, claimedSender) = reportData.arr.splitAt(32)
    for {
      _ <- Either.raiseUnless(claimedSender.sameElements(tx.sender.arr)) {
        "Quote's report data does not commit to this transaction's sender"
      }
      height <- blockchain.heightOf(ByteStr(claimedBlockId)).toRight("Quote's report data does not reference a known block")
      currentHeight = blockchain.height
      _ <- Either.raiseWhen(height <= currentHeight - FreshnessWindowBlocks || height > currentHeight) {
        s"Quote references block at height $height, outside the freshness window of the last $FreshnessWindowBlocks blocks"
      }
    } yield ()
  }

  /** Chain of trust: the quote's own embedded PCK cert chain (leaf, PCK CA, root) is checked against the pinned
    * Intel root - it doesn't need the on-chain pckCaIssuerChain too, since the chain the quote carries is already
    * self-contained; that field only backs verifying pckCrl's own signature, at UpdateCollateral submission time
    * (see DcapCollateral). It does need both on-chain CRLs for revocation, though: PKIX checks every certificate in
    * the path (leaf and the PCK CA intermediate, since the last cert - root - is the trust anchor, excluded from
    * the path), so pckCrl alone only covers the leaf; the intermediate's own issuer is Root CA, so its revocation
    * status needs dcapRootCaCrl. The PCK leaf's key then verifies qeReportSignature, and qeReportBody's own
    * user_report_data (checked by DcapQuote's own invariant, re-verified here) proves the QE vouches for this
    * specific attestation key; that attestation key finally verifies isvSignature.
    */
  private def verifyQuoteSignatures(blockchain: Blockchain, tx: StartBoostTransaction, quote: DcapQuote.Quote): Either[String, Unit] =
    for {
      _ <- Either.raiseUnless(quote.signature.certData.isPckCertChain) {
        s"Quote's cert data is not a PCK certificate chain (type ${quote.signature.certData.certKeyType})"
      }
      pckCrl <- blockchain.dcapPckCrl.toRight("PCK CRL must be set (via UpdateCollateral) before a StartBoost quote can be verified")
      rootCaCrl <- blockchain.dcapRootCaCrl.toRight(
        "Root CA CRL must be set (via UpdateCollateral) before a StartBoost quote can be verified"
      )
      pckLeafKey <- IntelPki
        .verifyIssuerChain(quote.signature.certData.certData.arr, tx.timestamp, crls = Seq(pckCrl.arr, rootCaCrl.arr))
        .leftMap(e => s"Invalid PCK certificate chain: $e")
      _ <- P256Curve
        .verify(quote.signature.qeReportBodyMessage.arr, quote.signature.qeReportSignature.arr, P256Curve.publicKeyToBytes(pckLeafKey))
        .leftMap(e => s"Failed to verify QE report signature: $e")
        .flatMap(Either.raiseUnless(_)("QE report signature does not verify against the PCK leaf certificate"))
      _ <- verifyQeReportBinding(quote)
      _ <- P256Curve
        .verify(quote.isvSignedMessage.arr, quote.signature.isvSignature.arr, quote.signature.attestationPubKey.arr)
        .leftMap(e => s"Failed to verify ISV signature: $e")
        .flatMap(Either.raiseUnless(_)("ISV signature does not verify against the quote's attestation key"))
    } yield ()

  /** Per Intel's spec, the QE report's own user_report_data must be SHA256(attestation_pub_key ++ auth_data)
    * followed by 32 zero bytes - this is what lets the QE's signature (over qeReportBody alone) also vouch for the
    * specific attestation key and auth data the rest of the quote uses, without the QE having to sign those
    * directly.
    */
  private def verifyQeReportBinding(quote: DcapQuote.Quote): Either[String, Unit] = {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(quote.signature.attestationPubKey.arr)
    digest.update(quote.signature.authData.arr)
    val expected = digest.digest() ++ new Array[Byte](32)
    Either.raiseUnless(java.util.Arrays.equals(expected, quote.signature.qeReportBody.userReportData.arr)) {
      "QE report's user_report_data is not SHA256(attestation key || auth data), zero-padded"
    }
  }

  private def verifyTcbStatus(blockchain: Blockchain, quote: DcapQuote.Quote): Either[String, Unit] =
    for {
      fmspc <- IntelPki.pckLeafFmspc(quote.signature.certData.certData.arr)
      _ <- Either.raiseUnless(blockchain.dcapTcbInfo(ByteStr(fmspc)).isDefined) {
        s"No on-chain TCB Info for FMSPC ${ByteStr(fmspc)}; submit one via UpdateCollateral first"
      }
    } yield ()
}
