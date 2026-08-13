package tech.hearth.crypto.dcap

import cats.syntax.either.*
import tech.hearth.crypto.P256Curve

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.cert.{CertificateFactory, X509CRL, X509Certificate}
import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, PublicKey, Signature}
import java.util.{Base64, Date}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Intel's DCAP PKI, pinned per the StartBoost consensus plan: the Root CA is a fixed trust anchor baked into the
  * validator, never a transaction. Everything under it (PCK CRL/Root CA CRL issuer chains, TCB Info, QE Identity)
  * is permissionless, expiring collateral, verified against this pin.
  */
object IntelPki {

  /** Intel SGX/TDX Provisioning Certification Root CA public key (SubjectPublicKeyInfo, DER, base64), the same
    * constant dcap-rs pins as INTEL_ROOT_CA_PEM. Only the key is pinned, not a specific certificate encoding, since
    * a self-signed root can be validated against its own claimed public key rather than compared byte-for-byte.
    */
  private val RootCaPublicKeyDer: Array[Byte] = Base64.getDecoder.decode(
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEC6nEwMDIYZOj/iPWsCzaEKi71OiO" +
      "SLRFhWGjbnBVJfVnkY4u3IjkDYYL0MxO4mqsyYjlBalTVYxFP2sJBK5zlA=="
  )

  val rootCaPublicKey: PublicKey =
    KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(RootCaPublicKeyDer))

  private val certificateFactory = CertificateFactory.getInstance("X.509")

  /** Verifies a PEM certificate chain (leaf first, root last - matching Intel PCS's own issuer-chain header order)
    * terminates at `trustAnchor` (the pinned Intel Root CA in production; a throwaway test key otherwise) and is
    * valid at `atTime`, checking revocation against `crls` (DER-encoded). Returns the leaf's public key on success.
    *
    * `P256Curve.validateCertChain` trusts whatever certificate is last in the chain as the anchor - fine once
    * that certificate is independently confirmed to actually be signed by `trustAnchor`, which is the one check it
    * doesn't do itself, so it's done here first.
    */
  def verifyIssuerChain(
      chainPem: Array[Byte],
      atTime: Long,
      crls: Seq[Array[Byte]] = Seq.empty,
      trustAnchor: PublicKey = rootCaPublicKey
  ): Either[String, PublicKey] =
    try {
      val certs = certificateFactory
        .generateCertificates(new ByteArrayInputStream(chainPem))
        .asScala
        .toList
        .collect { case c: X509Certificate => c }

      for {
        _ <- Either.raiseUnless(certs.nonEmpty)("empty certificate chain")
        root = certs.last
        _       <- Either.raiseUnless(root.getSubjectX500Principal == root.getIssuerX500Principal)("root certificate is not self-issued")
        _       <- verifySignedBy(root, trustAnchor)
        _       <- P256Curve.validateCertChain(certs.map(_.getEncoded), crls, atTime)
        leafKey <- Right(certs.head.getPublicKey)
      } yield leafKey
    } catch {
      case NonFatal(e) => Left(s"failed to verify issuer chain: ${e.getMessage}")
    }

  private def verifySignedBy(cert: X509Certificate, key: PublicKey): Either[String, Unit] =
    try {
      cert.verify(key)
      Right(())
    } catch {
      case NonFatal(e) => Left(s"certificate signature verification failed: ${e.getMessage}")
    }

  /** Parses a DER CRL, verifies it was signed by `signedBy` and is valid at `atTime`, and returns its CRL Number
    * (RFC 5280 S5.2.3, OID 2.5.29.20) if present - the field a CA increments on every reissue, used here as the
    * monotonic-freshness guard against a stale CRL being resubmitted over a newer one.
    */
  def verifyCrl(der: Array[Byte], signedBy: PublicKey, atTime: Long): Either[String, Option[BigInt]] =
    try {
      val crl = certificateFactory.generateCRL(new ByteArrayInputStream(der)).asInstanceOf[X509CRL]
      crl.verify(signedBy)
      Either.raiseWhen(crl.getNextUpdate.before(new Date(atTime)))(s"CRL expired at ${crl.getNextUpdate}").map(_ => crlNumber(crl))
    } catch {
      case NonFatal(e) => Left(s"failed to verify CRL: ${e.getMessage}")
    }

  /** Reads the CRL Number out of a DER CRL already known to be valid (e.g. the currently-stored on-chain value,
    * verified once when it was first accepted) without re-checking its signature.
    */
  def crlNumberOf(der: Array[Byte]): Option[BigInt] =
    try
      crlNumber(certificateFactory.generateCRL(new ByteArrayInputStream(der)).asInstanceOf[X509CRL])
    catch {
      case NonFatal(_) => None
    }

  /** Verifies a raw (r||s, 64-byte) ECDSA-P256-SHA256 signature over `message` against an already-resolved public
    * key - the format Intel's PCS uses for tcbInfo/enclaveIdentity's own "signature" field, computed over the raw
    * bytes of the nested object as Intel served it (see JsonRawValue), not a reserialized copy of it.
    */
  def verifyRawSignature(message: Array[Byte], signatureRaw64: Array[Byte], key: PublicKey): Either[String, Unit] =
    if (signatureRaw64.length != 64) Left(s"signature must be 64 bytes, got ${signatureRaw64.length}")
    else
      try {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(key)
        sig.update(message)
        Either.raiseUnless(sig.verify(rawToDerSignature(signatureRaw64)))("signature verification failed")
      } catch {
        case NonFatal(e) => Left(s"failed to verify signature: ${e.getMessage}")
      }

  private def rawToDerSignature(raw64: Array[Byte]): Array[Byte] = {
    val v = new org.bouncycastle.asn1.ASN1EncodableVector()
    v.add(new org.bouncycastle.asn1.ASN1Integer(new BigInteger(1, raw64, 0, 32)))
    v.add(new org.bouncycastle.asn1.ASN1Integer(new BigInteger(1, raw64, 32, 32)))
    new org.bouncycastle.asn1.DERSequence(v).getEncoded
  }

  private val SgxExtensionOid = "1.2.840.113741.1.13.1"
  private val FmspcOid        = "1.2.840.113741.1.13.1.4"

  /** Reads the FMSPC (platform model id, 6 bytes) out of a PCK leaf certificate's SGX extension - the field that
    * selects which on-chain tcbInfo entry a quote's PCK chain has to be checked against (see DcapCollateral).
    * `chainPem` is a quote's own embedded PCK cert chain (leaf first, matching Intel PCS's own order); only the
    * leaf carries the SGX extension, so the rest of the chain is parsed but otherwise unused here.
    */
  def pckLeafFmspc(chainPem: Array[Byte]): Either[String, Array[Byte]] =
    try {
      val leaf = certificateFactory.generateCertificates(new ByteArrayInputStream(chainPem)).asScala.headOption.collect { case c: X509Certificate =>
        c
      }
      leaf.toRight("empty PCK certificate chain").flatMap(fmspcOf)
    } catch {
      case NonFatal(e) => Left(s"failed to parse PCK certificate chain: ${e.getMessage}")
    }

  private def fmspcOf(cert: X509Certificate): Either[String, Array[Byte]] =
    try {
      val sgxExtension = Option(cert.getExtensionValue(SgxExtensionOid)).toRight("PCK certificate has no SGX extension")
      sgxExtension.flatMap { raw =>
        // Same double DER-encoding as crlNumber below: an OCTET STRING wrapping the SGX extension's own SEQUENCE
        // of SEQUENCE{OID, value} entries (RFC 5280-style extension list, Intel's own private OID tree).
        val entries = org.bouncycastle.asn1.ASN1Sequence.getInstance(
          org.bouncycastle.asn1.ASN1OctetString.getInstance(raw).getOctets
        )
        (0 until entries.size())
          .map(i => org.bouncycastle.asn1.ASN1Sequence.getInstance(entries.getObjectAt(i)))
          .find(entry => org.bouncycastle.asn1.ASN1ObjectIdentifier.getInstance(entry.getObjectAt(0)).getId == FmspcOid)
          .map(entry => org.bouncycastle.asn1.ASN1OctetString.getInstance(entry.getObjectAt(1)).getOctets)
          .toRight("SGX extension has no FMSPC field")
      }
    } catch {
      case NonFatal(e) => Left(s"failed to read FMSPC from PCK certificate: ${e.getMessage}")
    }

  private def crlNumber(crl: X509CRL): Option[BigInt] =
    Option(crl.getExtensionValue("2.5.29.20")).map { raw =>
      // The extension value is itself DER-encoded twice over: an OCTET STRING wrapping the CRLNumber's own DER
      // INTEGER encoding (ASN1Integer). Both layers use a short tag+length header before the payload, so unwrap
      // it that same way instead of pulling in a full ASN.1 decoder for one field.
      val octetString = org.bouncycastle.asn1.ASN1OctetString.getInstance(raw)
      val integer     = org.bouncycastle.asn1.ASN1Integer.getInstance(octetString.getOctets)
      BigInt(integer.getValue)
    }
}
