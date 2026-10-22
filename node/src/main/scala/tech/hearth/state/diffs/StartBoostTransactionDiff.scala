package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.crypto.P256Curve
import tech.hearth.crypto.dcap.{DcapQuote, IntelPki, QeIdentity, TdxTcbInfo}
import tech.hearth.crypto.dcap.TdxTcbInfo.TcbStatus
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.StartBoostTransaction
import tech.hearth.transaction.TxValidationError.{ActivationError, GenericError}

import java.nio.{ByteBuffer, ByteOrder}
import java.time.Instant

/** StartBoostTransaction semantics: verify a TDX quote end to end and register its enclave for the next generation
  * period (see the StartBoost consensus plan). `StartBoostTxValidator` already rejected a malformed or SGX quote
  * before this ever runs, but re-parses here too since nothing carries the parsed value across from validation.
  *
  * The quote's platform, TDX module and Quoting Enclave are rated against the on-chain tcbInfo for its FMSPC and the
  * QE Identity (TdxTcbInfo.evaluate, QeIdentity.evaluate), and have to come out with a status the network accepts
  * (FunctionalitySettings.acceptedTcbStatuses).
  *
  * TD measurements are pinned in consensus (PinnedMeasurements) rather than in a governance-updatable registry, #40.
  */
object StartBoostTransactionDiff {

  /** How many blocks back a quote's embedded block id may be and still count as fresh - see "Freshness window" in
    * the StartBoost consensus plan (K=100). Bounds how long a leaked quote stays replayable, while leaving enough
    * room for a TEE miner's fetch-block/generate-quote/broadcast round trip to land inside the window.
    */
  val FreshnessWindowBlocks = 100

  /** A TD's launch measurement (MRTD) and its runtime measurement registers. RTMR3 (the workload) is only as
    * trustworthy as the firmware, kernel and command line in RTMR0-2 that extended it: an unpinned kernel can replay
    * RTMR3's expected event log, so all four are pinned together.
    */
  case class TdMeasurement(mrTd: ByteStr, rtmrs: Seq[ByteStr])

  object TdMeasurement {
    def apply(report: DcapQuote.Td10ReportBody): TdMeasurement =
      TdMeasurement(report.mrTd, Seq(report.rtmr0, report.rtmr1, report.rtmr2, report.rtmr3))

    private[StartBoostTransactionDiff] def hex(mrTd: String, rtmrs: String*): TdMeasurement =
      TdMeasurement(ByteStr(Base16.decode(mrTd)), rtmrs.map(r => ByteStr(Base16.decode(r))))
  }

  /** The TEE miner images a StartBoost quote may run, fixed in consensus until a governance-updatable registry
    * replaces it (#40). Collected from every StartBoost on testnet: the image in use since height 8394. The two
    * earlier RTMR3 values (heights 5028-8359) only pass below strictTxValidationHeight.
    */
  val PinnedMeasurements: Set[TdMeasurement] = Set(
    TdMeasurement.hex(
      "ba87a347454466680bfd267446df89d8117c04ea9f28234dd3d84e1a8a957d5adaf02d4aa88433b559fb13bd40f0109e",
      "09d747e568e012bb560b37604e25f1a83988037d8286e81bb97e46fb31ec4d35edf9e23236ae573265417e7ed2f89b99",
      "a7fabeb3d8e66993a5317851812ae8e0bdbe323069c15a08cf6a2a9d50d6dc8a68c31c84d203b8592f02cc2ed32d3c34",
      "7f78757985a18b275a41eef903c8a796510298eb2b616046b62b8eff0790987c69deea773f13974420726d9ef1a08946",
      "8cbb141233f31f5e76b569e4bf31246fffca005506cd24c902a191533c9be7e2bcdc9a72dfab34e7cf9a5b2b35dbd6e8"
    )
  )

  /** Advisories the pinned images are known to mitigate, for a TCB status that leaves them to the TD's software
    * (SWHardeningNeeded, ConfigurationAndSWHardeningNeeded): the platform is as patched as Intel can make it.
    * Empty, since testnet's platform rates UpToDate (see SOURCE.md's testnet tcbInfo); an advisory goes in with
    * the image that mitigates it. Other statuses' advisories need microcode or firmware no image can supply, so
    * FunctionalitySettings.acceptedTcbStatuses alone decides those.
    */
  val MitigatedAdvisories: Set[String] = Set.empty

  private val SoftwareHardeningStatuses = Set(TcbStatus.SWHardeningNeeded, TcbStatus.ConfigurationAndSWHardeningNeeded)

  /** Every collateral and certificate validity window is checked at the block's timestamp (validityTime), which its
    * generator sets within consensus bounds, rather than the transaction's, which its sender picks and may backdate by
    * up to max-transaction-time-back-offset to stretch an expired one.
    */
  def apply(blockchain: Blockchain)(tx: StartBoostTransaction): Either[ValidationError, StateSnapshot] =
    for {
      blockTimestamp <- DcapCollateral.validityTime(blockchain).leftMap(GenericError(_))
      quote          <- DcapQuote.parse(tx.tdxQuote.arr).leftMap(e => GenericError(s"Invalid quote: $e"))
      _              <- Either.raiseUnless(quote.header.teeType == DcapQuote.TdxTeeType)(GenericError("SGX quotes are not accepted"))
      tdReport <- quote.body match {
        case DcapQuote.Td10QuoteBody(body) => Right(body)
        case DcapQuote.Td15QuoteBody(body) => Right(body.td10)
        case DcapQuote.SgxQuoteBody(_)     => Left(GenericError("SGX quotes are not accepted"))
      }
      tdReportData = tdReport.userReportData
      current <- blockchain.currentGenerationPeriod.toRight(ActivationError("DeterministicFinality is not yet activated"))
      next = current.next
      _ <- Either.raiseUnless(tx.generationPeriodStart == next.start) {
        GenericError(s"Expected the next period start height ${next.start}, got ${tx.generationPeriodStart}")
      }
      _ <- Either.raiseUnless(blockchain.committedGenerators(next).exists(_.address == tx.validator)) {
        GenericError(s"${tx.validator} is not a committed generator of period $next")
      }
      enclaveKey <- extractEnclaveKey(tdReportData).leftMap(GenericError(_))
      _          <- verifyFreshness(blockchain, tdReportData).leftMap(GenericError(_))
      _ <- Either.raiseWhen(blockchain.registeredEnclaves(next).exists(_.enclavePublicKey == enclaveKey)) {
        GenericError(s"Enclave $enclaveKey is already registered for period $next")
      }
      _ <- verifyMeasurement(blockchain, tdReport).leftMap(GenericError(_))
      _ <- verifyTcbStatus(blockchain, blockTimestamp, quote).leftMap(GenericError(_))
      _ <- verifyQuoteSignatures(blockchain, blockTimestamp, quote).leftMap(GenericError(_))
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(tx.sender.toAddress -> Portfolio(balance = -tx.fee.value)),
        nextRegisteredEnclaves = Seq(RegisteredEnclave(enclaveKey, tx.validator, tx.sender.toAddress))
      )
    } yield snapshot

  /** The TD report's 64-byte report_data is blockId(32) ++ enclavePublicKey(32): report_data[32:64] carries the
    * enclave's own ephemeral Ed25519 key, the only key generated inside the TEE and therefore the only one worth
    * registering (both values fit the slot raw; unforgeability comes from the quote's hardware signature over
    * them). The transaction sender is deliberately not bound into the quote: the sender becomes the enclave's
    * operator on a first-registration-wins basis, and a hijacked registration is escaped by restarting the enclave.
    */
  private def extractEnclaveKey(reportData: ByteStr): Either[String, ByteStr] = {
    val key = ByteStr(reportData.arr.drop(32))
    Either.raiseWhen(key.arr.forall(_ == 0))("Quote's report data carries an all-zero enclave key").map(_ => key)
  }

  /** A recent block id in report_data[0:32] proves the quote wasn't generated long ago: the quote itself carries no
    * timestamp, and a block id is unpredictable before the block exists. The current block is excluded: its id
    * changes with every microblock, so a quote of it stops resolving once the block is finished and the block
    * carrying the StartBoost no longer validates. Below strictTxValidationHeight such quotes are on testnet, so an
    * unknown id is let through there.
    */
  private def verifyFreshness(blockchain: Blockchain, reportData: ByteStr): Either[String, Unit] = {
    val claimedBlockId = ByteStr(reportData.arr.take(32))
    val currentHeight  = blockchain.height
    val strict         = currentHeight >= blockchain.settings.functionalitySettings.strictTxValidationHeight
    blockchain.heightOf(claimedBlockId) match {
      case None if strict => Left("Quote's report data does not reference a known block")
      case None           => Right(())
      case Some(height) =>
        val newest = if (strict) currentHeight - 1 else currentHeight
        Either.raiseWhen(height <= currentHeight - FreshnessWindowBlocks || height > newest) {
          s"Quote references block at height $height, outside the freshness window of the last $FreshnessWindowBlocks blocks"
        }
    }
  }

  /** Checked before the signatures only because it is cheaper; it means something only once they verify. TD
    * attributes are checked regardless of the measurements, which don't cover them: the host picks them at TD
    * creation, and a debug or migratable TD leaks its memory, and so the enclave key, however trusted its image.
    */
  private def verifyMeasurement(blockchain: Blockchain, report: DcapQuote.Td10ReportBody): Either[String, Unit] =
    if (blockchain.height < blockchain.settings.functionalitySettings.strictTxValidationHeight) Right(())
    else {
      val measurement = TdMeasurement(report)
      val attributes  = ByteBuffer.wrap(report.tdAttributes.arr).order(ByteOrder.LITTLE_ENDIAN).getLong
      for {
        _ <- Either.raiseWhen((attributes & ~AllowedTdAttributes) != 0) {
          if ((attributes & DebugTdAttribute) != 0) "Debug TDs are not accepted"
          else if ((attributes & MigratableTdAttribute) != 0) "Migratable TDs are not accepted"
          else s"Quote's TD attributes ${Base16.encode(report.tdAttributes.arr)} set reserved bits"
        }
        _ <- Either.raiseUnless(PinnedMeasurements.contains(measurement)) {
          s"Quote's TD measurement (MRTD ${measurement.mrTd}, RTMR0-3 ${measurement.rtmrs.mkString(", ")}) is not pinned"
        }
      } yield ()
    }

  // TDATTRIBUTES bits (Intel TDX module spec, TD_PARAMS), read little-endian. DEBUG lets the host read the TD's memory,
  // MIGRATABLE lets a migration TD move it, and a reserved bit has no meaning the pinned image was built for, so only
  // the bits a hardened TD legitimately sets are allowed: SEPT_VE_DISABLE (28), PKS (30), KL (31), PERFMON (63).
  private val DebugTdAttribute      = 1L << 0
  private val MigratableTdAttribute = 1L << 29
  private val AllowedTdAttributes   = (1L << 28) | (1L << 30) | (1L << 31) | (1L << 63)

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
  private def verifyQuoteSignatures(blockchain: Blockchain, atTime: Long, quote: DcapQuote.Quote): Either[String, Unit] =
    for {
      _ <- Either.raiseUnless(quote.signature.certData.isPckCertChain) {
        s"Quote's cert data is not a PCK certificate chain (type ${quote.signature.certData.certKeyType})"
      }
      pckCrl <- blockchain.dcapPckCrl.toRight("PCK CRL must be set (via UpdateCollateral) before a StartBoost quote can be verified")
      rootCaCrl <- blockchain.dcapRootCaCrl.toRight(
        "Root CA CRL must be set (via UpdateCollateral) before a StartBoost quote can be verified"
      )
      pckLeafKey <- IntelPki
        .verifyIssuerChain(quote.signature.certData.certData.arr, atTime, crls = Seq(pckCrl.arr, rootCaCrl.arr))
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

  /** Checked before the signatures, like the measurement, because it is cheaper: it rejects a quote by what the
    * still-unverified quote claims, and only the signatures verifying makes a passing quote mean anything.
    */
  private def verifyTcbStatus(blockchain: Blockchain, atTime: Long, quote: DcapQuote.Quote): Either[String, Unit] =
    for {
      pck <- IntelPki.pckTcb(quote.signature.certData.certData.arr)
      storedTcbInfo <- blockchain.dcapTcbInfo(pck.fmspc).toRight {
        s"No on-chain TCB Info for FMSPC ${pck.fmspc}; submit one via UpdateCollateral first"
      }
      storedQeIdentity <- blockchain.dcapQeIdentity.toRight(
        "QE Identity must be set (via UpdateCollateral) before a StartBoost quote can be verified"
      )
      tcbInfo    <- TdxTcbInfo.parse(storedTcbInfo.arr)
      qeIdentity <- QeIdentity.parse(storedQeIdentity.arr)
      _          <- checkTcb(tcbInfo, qeIdentity, pck, quote, blockchain.settings.functionalitySettings.acceptedTcbStatuses, atTime)
    } yield ()

  /** Expired collateral is rejected rather than evaluated: it can't show a TCB level Intel has since downgraded. */
  private[diffs] def checkTcb(
      tcbInfo: TdxTcbInfo,
      qeIdentity: QeIdentity,
      pck: IntelPki.PckTcb,
      quote: DcapQuote.Quote,
      accepted: Set[TcbStatus],
      atTime: Long
  ): Either[String, Unit] = {
    val at = Instant.ofEpochMilli(atTime)
    for {
      _ <- Either.raiseWhen(at.isAfter(tcbInfo.nextUpdate)) {
        s"On-chain TCB Info for FMSPC ${pck.fmspc} expired at ${tcbInfo.nextUpdate}; submit a fresh one via UpdateCollateral"
      }
      _ <- Either.raiseWhen(at.isAfter(qeIdentity.nextUpdate)) {
        s"On-chain QE Identity expired at ${qeIdentity.nextUpdate}; submit a fresh one via UpdateCollateral"
      }
      qe    <- qeIdentity.evaluate(quote.signature.qeReportBody)
      level <- tcbInfo.evaluate(pck, quote, qe)
      _     <- Either.raiseUnless(accepted.contains(level.status))(s"TCB status ${level.status} is not accepted")
      unmitigated = if (SoftwareHardeningStatuses.contains(level.status)) level.advisoryIds.filterNot(MitigatedAdvisories) else Seq.empty
      _ <- Either.raiseWhen(unmitigated.nonEmpty) {
        s"TCB status ${level.status} needs mitigations for ${unmitigated.mkString(", ")}, which the pinned images are not known to have"
      }
    } yield ()
  }
}
