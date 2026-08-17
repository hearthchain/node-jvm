package tech.hearth.crypto.dcap

import cats.syntax.either.*
import tech.hearth.common.state.ByteStr

/** Pure structural parsing of an Intel DCAP quote (SGX or TDX, ECDSA-P256 attestation key, quote versions 3 to 5) -
  * no blockchain access, no cryptographic verification. `IntelPki`/`DcapCollateral` verify collateral signatures;
  * a later StartBoost validation layer will verify a quote's own signatures (isv/qe) and its PCK cert chain against
  * that collateral, and apply policy (e.g. rejecting SGX outright). This module only turns quote bytes into a
  * structured value, or rejects bytes that aren't a well-formed quote.
  *
  * Layout mirrors `dcap-rs` (`automata-network/automata-dcap-attestation`, the same upstream `IntelPki`/
  * `JsonRawValue` already cross-check against) field for field, including which report-body fields Intel's own
  * spec reserves and dcap-rs itself never exposes (cpusvn, isvextprodid, config_id/config_svn, isv_family_id,
  * assorted padding) - this parser skips exactly those bytes rather than every other implementation detail dcap-rs
  * happens to make private, so a reserved field changing meaning upstream doesn't silently desync the two.
  */
object DcapQuote {

  val SgxTeeType: Long = 0x00000000L
  val TdxTeeType: Long = 0x00000081L

  val IntelQeVendorId: ByteStr = ByteStr(
    Array(0x93, 0x9a, 0x72, 0x33, 0xf7, 0x9c, 0x4c, 0xa9, 0x94, 0x0a, 0x0d, 0xb3, 0x95, 0x7f, 0x06, 0x07).map(_.toByte)
  )

  private val EcdsaP256AttestationKeyType = 2
  private val PckCertChainKeyType         = 5
  private val EcdsaSigAuxDataKeyType      = 6

  private val EnclaveReportBodySize = 384
  private val Td10ReportBodySize    = 584
  private val Td15ReportBodySize    = 648

  case class QuoteHeader(
      version: Int,
      attestationKeyType: Int,
      teeType: Long,
      qeSvn: Int,
      pceSvn: Int,
      qeVendorId: ByteStr,
      userData: ByteStr
  )

  case class EnclaveReportBody(
      miscSelect: Long,
      sgxAttributes: ByteStr,
      mrEnclave: ByteStr,
      mrSigner: ByteStr,
      isvProdId: Int,
      isvSvn: Int,
      userReportData: ByteStr
  )

  case class Td10ReportBody(
      teeTcbSvn: ByteStr,
      mrSeam: ByteStr,
      mrSignerSeam: ByteStr,
      seamAttributes: ByteStr,
      tdAttributes: ByteStr,
      xfam: ByteStr,
      mrTd: ByteStr,
      mrConfigId: ByteStr,
      mrOwner: ByteStr,
      mrOwnerConfig: ByteStr,
      rtmr0: ByteStr,
      rtmr1: ByteStr,
      rtmr2: ByteStr,
      rtmr3: ByteStr,
      userReportData: ByteStr
  )

  case class Td15ReportBody(td10: Td10ReportBody, teeTcbSvn2: ByteStr, mrServiceTd: ByteStr)

  sealed trait QuoteBody
  case class SgxQuoteBody(body: EnclaveReportBody) extends QuoteBody
  case class Td10QuoteBody(body: Td10ReportBody)   extends QuoteBody
  case class Td15QuoteBody(body: Td15ReportBody)   extends QuoteBody

  /** `certKeyType` 5 is a PCK cert chain (PEM, in `certData`), the only kind this repo's verification path needs;
    * other key types (cleartext/encrypted PPID, a bare PCK leaf) are parsed structurally the same way but left
    * uninterpreted here.
    */
  case class QuoteCertData(certKeyType: Int, certData: ByteStr) {
    def isPckCertChain: Boolean = certKeyType == PckCertChainKeyType
  }

  /** `qeReportBodyMessage` is the exact wire bytes `qeReportSignature` covers, kept as the original slice for the
    * same reason `Quote.isvSignedMessage` is - see there.
    */
  case class QuoteSignatureData(
      isvSignature: ByteStr,
      attestationPubKey: ByteStr,
      qeReportBody: EnclaveReportBody,
      qeReportBodyMessage: ByteStr,
      qeReportSignature: ByteStr,
      authData: ByteStr,
      certData: QuoteCertData
  )

  /** `isvSignedMessage` is the exact wire bytes the quote's own `signature.isvSignature` covers - the header, then
    * (version 5+ only) the explicit body-type/body-size prefix, then the body - kept as the original slice rather
    * than re-serialized from the parsed fields, so a signature check against it can never diverge from what the
    * quoting enclave actually signed due to a re-encoding bug here.
    */
  case class Quote(header: QuoteHeader, body: QuoteBody, signature: QuoteSignatureData, isvSignedMessage: ByteStr)

  /** Parses one quote from the front of `bytes`. Trailing bytes beyond the quote's own declared signature length
    * are ignored, not rejected - `bytes` may be a larger buffer a quote was merely embedded in.
    */
  def parse(bytes: Array[Byte]): Either[String, Quote] = {
    val r = new Reader(bytes)
    for {
      header <- readHeader(r)
      body   <- readBody(r, header)
      isvSignedMessage = ByteStr(bytes.slice(0, r.position))
      signature <- readSignature(r, header.version)
    } yield Quote(header, body, signature, isvSignedMessage)
  }

  private def readHeader(r: Reader): Either[String, QuoteHeader] =
    for {
      version            <- r.u16()
      _                  <- Either.raiseUnless(version >= 3 && version <= 5)(s"unsupported quote version: $version")
      attestationKeyType <- r.u16()
      _ <- Either.raiseUnless(attestationKeyType == EcdsaP256AttestationKeyType)(
        s"unsupported attestation key type: $attestationKeyType"
      )
      teeType    <- r.u32()
      qeSvn      <- r.u16()
      pceSvn     <- r.u16()
      qeVendorId <- r.takeByteStr(16)
      _          <- Either.raiseUnless(qeVendorId == IntelQeVendorId)(s"unsupported QE vendor id: $qeVendorId")
      userData   <- r.takeByteStr(20)
    } yield QuoteHeader(version, attestationKeyType, teeType, qeSvn, pceSvn, qeVendorId, userData)

  private def readEnclaveReportBody(r: Reader): Either[String, EnclaveReportBody] =
    for {
      _              <- r.take(16) // cpusvn, reserved by Intel spec, not exposed upstream
      miscSelect     <- r.u32()
      _              <- r.take(12) // reserved1
      _              <- r.take(16) // isvextprodid, reserved
      sgxAttributes  <- r.takeByteStr(16)
      mrEnclave      <- r.takeByteStr(32)
      _              <- r.take(32) // reserved2
      mrSigner       <- r.takeByteStr(32)
      _              <- r.take(32) // reserved3
      _              <- r.take(64) // configid, reserved
      isvProdId      <- r.u16()
      isvSvn         <- r.u16()
      _              <- r.u16()    // configsvn, reserved
      _              <- r.take(42) // reserved4
      _              <- r.take(16) // isv_family_id, reserved
      userReportData <- r.takeByteStr(64)
    } yield EnclaveReportBody(miscSelect, sgxAttributes, mrEnclave, mrSigner, isvProdId, isvSvn, userReportData)

  private def readTd10ReportBody(r: Reader): Either[String, Td10ReportBody] =
    for {
      teeTcbSvn      <- r.takeByteStr(16)
      mrSeam         <- r.takeByteStr(48)
      mrSignerSeam   <- r.takeByteStr(48)
      seamAttributes <- r.takeByteStr(8)
      tdAttributes   <- r.takeByteStr(8)
      xfam           <- r.takeByteStr(8)
      mrTd           <- r.takeByteStr(48)
      mrConfigId     <- r.takeByteStr(48)
      mrOwner        <- r.takeByteStr(48)
      mrOwnerConfig  <- r.takeByteStr(48)
      rtmr0          <- r.takeByteStr(48)
      rtmr1          <- r.takeByteStr(48)
      rtmr2          <- r.takeByteStr(48)
      rtmr3          <- r.takeByteStr(48)
      userReportData <- r.takeByteStr(64)
    } yield Td10ReportBody(
      teeTcbSvn,
      mrSeam,
      mrSignerSeam,
      seamAttributes,
      tdAttributes,
      xfam,
      mrTd,
      mrConfigId,
      mrOwner,
      mrOwnerConfig,
      rtmr0,
      rtmr1,
      rtmr2,
      rtmr3,
      userReportData
    )

  private def readTd15ReportBody(r: Reader): Either[String, Td15ReportBody] =
    for {
      td10        <- readTd10ReportBody(r)
      teeTcbSvn2  <- r.takeByteStr(16)
      mrServiceTd <- r.takeByteStr(48)
    } yield Td15ReportBody(td10, teeTcbSvn2, mrServiceTd)

  /** Versions up to 4 have exactly one possible body shape per TEE type, implicit in `header.teeType`; version 5
    * makes the body type and size explicit on the wire instead, and cross-checks them against `header.teeType`.
    */
  private def readBody(r: Reader, header: QuoteHeader): Either[String, QuoteBody] =
    if (header.version <= 4)
      header.teeType match {
        case SgxTeeType => readEnclaveReportBody(r).map(SgxQuoteBody(_))
        case TdxTeeType => readTd10ReportBody(r).map(Td10QuoteBody(_))
        case other      => Left(f"unsupported TEE type: 0x$other%08x")
      }
    else
      for {
        bodyTypeCode <- r.u16()
        bodySize     <- r.u32()
        body <- bodyTypeCode match {
          case 1 =>
            for {
              _    <- Either.raiseUnless(header.teeType == SgxTeeType)("quote body type 1 must be SGX TEE type")
              _    <- Either.raiseUnless(bodySize == EnclaveReportBodySize)(s"body size mismatch for SGX TEE type: $bodySize")
              body <- readEnclaveReportBody(r)
            } yield SgxQuoteBody(body)
          case 2 =>
            for {
              _    <- Either.raiseUnless(header.teeType == TdxTeeType)("quote body type 2 must be TDX TEE type")
              _    <- Either.raiseUnless(bodySize == Td10ReportBodySize)(s"body size mismatch for TDX TEE type: $bodySize")
              body <- readTd10ReportBody(r)
            } yield Td10QuoteBody(body)
          case 3 =>
            for {
              _    <- Either.raiseUnless(header.teeType == TdxTeeType)("quote body type 3 must be TDX TEE type")
              _    <- Either.raiseUnless(bodySize == Td15ReportBodySize)(s"body size mismatch for TDX TEE type: $bodySize")
              body <- readTd15ReportBody(r)
            } yield Td15QuoteBody(body)
          case other => Left(s"unsupported quote body type: $other")
        }
      } yield body

  private def readCertData(r: Reader): Either[String, QuoteCertData] =
    for {
      certKeyType  <- r.u16()
      certDataSize <- r.u32()
      _            <- Either.raiseUnless(certDataSize <= Int.MaxValue)(s"cert data size overflow: $certDataSize")
      certData     <- r.takeByteStr(certDataSize.toInt)
    } yield QuoteCertData(certKeyType, certData)

  /** v3's signature data lists the QE report and its own cert data as plain sibling fields. */
  private def readV3Signature(bytes: Array[Byte]): Either[String, QuoteSignatureData] = {
    val r = new Reader(bytes)
    for {
      isvSignature      <- r.takeByteStr(64)
      attestationPubKey <- r.takeByteStr(64)
      qeReportStart = r.position
      qeReportBody <- readEnclaveReportBody(r)
      qeReportBodyMessage = ByteStr(bytes.slice(qeReportStart, r.position))
      qeReportSignature <- r.takeByteStr(64)
      authDataLen       <- r.u16()
      authData          <- r.takeByteStr(authDataLen)
      certData          <- readCertData(r)
      _                 <- Either.raiseUnless(r.remaining == 0)(s"signature data has ${r.remaining} trailing bytes")
    } yield QuoteSignatureData(isvSignature, attestationPubKey, qeReportBody, qeReportBodyMessage, qeReportSignature, authData, certData)
  }

  /** v4+ nests the same QE report/signature/auth-data/cert-data quadruple one level down, inside an outer cert-data
    * frame tagged `EcdsaSigAuxDataKeyType` - the outer frame's own `certKeyType` is the format tag, not meaningful
    * collateral, so it's checked here and then discarded rather than carried into `QuoteSignatureData`.
    */
  private def readV4Signature(bytes: Array[Byte]): Either[String, QuoteSignatureData] = {
    val r = new Reader(bytes)
    for {
      isvSignature      <- r.takeByteStr(64)
      attestationPubKey <- r.takeByteStr(64)
      outerCertData     <- readCertData(r)
      _ <- Either.raiseUnless(outerCertData.certKeyType == EcdsaSigAuxDataKeyType)(
        s"expected cert key type $EcdsaSigAuxDataKeyType (ECDSA sig aux data), got ${outerCertData.certKeyType}"
      )
      innerBytes    = outerCertData.certData.arr
      inner         = new Reader(innerBytes)
      qeReportStart = inner.position
      qeReportBody <- readEnclaveReportBody(inner)
      qeReportBodyMessage = ByteStr(innerBytes.slice(qeReportStart, inner.position))
      qeReportSignature <- inner.takeByteStr(64)
      authDataLen       <- inner.u16()
      authData          <- inner.takeByteStr(authDataLen)
      certData          <- readCertData(inner)
      _                 <- Either.raiseUnless(inner.remaining == 0)(s"quoting enclave cert data has ${inner.remaining} trailing bytes")
      _                 <- Either.raiseUnless(r.remaining == 0)(s"signature data has ${r.remaining} trailing bytes")
    } yield QuoteSignatureData(isvSignature, attestationPubKey, qeReportBody, qeReportBodyMessage, qeReportSignature, authData, certData)
  }

  private def readSignature(r: Reader, version: Int): Either[String, QuoteSignatureData] =
    for {
      sigLen    <- r.u32()
      _         <- Either.raiseUnless(sigLen <= Int.MaxValue)(s"signature length overflow: $sigLen")
      sigBytes  <- r.take(sigLen.toInt)
      signature <- if (version == 3) readV3Signature(sigBytes) else readV4Signature(sigBytes)
    } yield signature

  private final class Reader(bytes: Array[Byte]) {
    private var pos = 0

    def position: Int = pos

    def remaining: Int = bytes.length - pos

    def take(n: Int): Either[String, Array[Byte]] =
      if (n < 0 || remaining < n) Left(s"buffer underflow: need $n bytes, only $remaining remaining")
      else {
        val slice = bytes.slice(pos, pos + n)
        pos += n
        Right(slice)
      }

    def takeByteStr(n: Int): Either[String, ByteStr] = take(n).map(ByteStr(_))

    def u16(): Either[String, Int] = take(2).map(b => (b(0) & 0xff) | ((b(1) & 0xff) << 8))

    def u32(): Either[String, Long] =
      take(4).map(b => (b(0) & 0xffL) | ((b(1) & 0xffL) << 8) | ((b(2) & 0xffL) << 16) | ((b(3) & 0xffL) << 24))
  }
}
