package tech.hearth.crypto.dcap

import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.FreeSpec
import org.bouncycastle.asn1.x509.{CRLNumber, Extension}
import play.api.libs.json.Json
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.{PemObject, PemWriter}

import java.io.StringWriter
import java.math.BigInteger
import java.security.cert.{CertificateFactory, X509CRL}
import java.security.{KeyPair, KeyPairGenerator, Security}
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date

/** IntelPki carries the only cryptographic trust decisions in the DCAP collateral path (see the StartBoost
  * consensus plan). The "real fixtures" group below exercises the actual accept path against production code's
  * pinned rootCaPublicKey, using genuine Intel-signed artifacts (src/test/resources/dcap, see SOURCE.md there) -
  * not a synthetic throwaway root, and not requiring the trustAnchor injection point at all. The synthetic-fixture
  * groups below that cover every reject path, which a handful of fixed real artifacts can't exercise on their own.
  */
class IntelPkiTest extends FreeSpec {
  Security.addProvider(new BouncyCastleProvider)

  // Inside both root_crl.der's (2024-03-20 to 2025-04-03) and signing.der's (2018-05-21 to 2025-05-21) validity
  // windows - see SOURCE.md.
  private val realFixtureAtTime = Instant.parse("2024-06-01T00:00:00Z").toEpochMilli

  private def resourceBytes(name: String): Array[Byte] =
    getClass.getResourceAsStream(s"/dcap/$name").readAllBytes()

  private def derToPem(der: Array[Byte]): Array[Byte] = {
    val cert = CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der))
    pem(cert.asInstanceOf[java.security.cert.X509Certificate])
  }

  "against real Intel-signed fixtures" - {
    "verifyCrl accepts the real Root CA CRL against the pinned rootCaPublicKey" in {
      val result = IntelPki.verifyCrl(resourceBytes("root_crl.der"), IntelPki.rootCaPublicKey, realFixtureAtTime)
      result shouldBe Right(Some(BigInt(1)))
    }

    "verifyIssuerChain accepts the real TCB Signing CA chain against the pinned rootCaPublicKey" in {
      val chainPem = derToPem(resourceBytes("signing.der")) ++ derToPem(resourceBytes("root.der"))
      val expectedKey = CertificateFactory
        .getInstance("X.509")
        .generateCertificate(new java.io.ByteArrayInputStream(resourceBytes("signing.der")))
        .getPublicKey

      val result = IntelPki.verifyIssuerChain(chainPem, realFixtureAtTime, crls = Seq(resourceBytes("root_crl.der")))
      result shouldBe Right(expectedKey)
    }

    "verifyRawSignature accepts the real tcbInfo signature against the TCB Signing CA's key" in {
      val payload      = resourceBytes("tcb_info_v3_sgx.json")
      val raw          = JsonRawValue.extractObject(payload, "tcbInfo").explicitGet()
      val json         = Json.parse(payload)
      val signatureHex = (json \ "signature").as[String]
      val signature    = tech.hearth.common.utils.Base16.decode(signatureHex)
      val signingKey = CertificateFactory
        .getInstance("X.509")
        .generateCertificate(new java.io.ByteArrayInputStream(resourceBytes("signing.der")))
        .getPublicKey

      IntelPki.verifyRawSignature(raw, signature, signingKey) shouldBe Right(())
    }

    "verifyRawSignature rejects the real tcbInfo signature against an unrelated key" in {
      val payload      = resourceBytes("tcb_info_v3_sgx.json")
      val raw          = JsonRawValue.extractObject(payload, "tcbInfo").explicitGet()
      val json         = Json.parse(payload)
      val signatureHex = (json \ "signature").as[String]
      val signature    = tech.hearth.common.utils.Base16.decode(signatureHex)

      IntelPki.verifyRawSignature(raw, signature, IntelPki.rootCaPublicKey) shouldBe a[Left[?, ?]]
    }

    "pckLeafFmspc reads the real FMSPC out of a quote's embedded PCK cert chain" in {
      val quoteBytes = tech.hearth.common.utils.Base16.decode(new String(resourceBytes("quotev3.hex")).trim)
      val quote      = tech.hearth.crypto.dcap.DcapQuote.parse(quoteBytes).explicitGet()
      quote.signature.certData.isPckCertChain shouldBe true

      val fmspc = IntelPki.pckLeafFmspc(quote.signature.certData.certData.arr).explicitGet()
      // Cross-checked independently: `openssl x509 -text` on the leaf cert's SGX extension (OID
      // 1.2.840.113741.1.13.1), sub-field 1.2.840.113741.1.13.1.4 (FMSPC), before this test was written.
      fmspc shouldBe tech.hearth.common.utils.Base16.decode("00606a000000")
    }

    "pckLeafFmspc rejects a chain with no SGX extension" in {
      val chainPem = derToPem(resourceBytes("signing.der")) ++ derToPem(resourceBytes("root.der"))
      IntelPki.pckLeafFmspc(chainPem) shouldBe a[Left[?, ?]]
    }
  }

  private def genKeyPair(): KeyPair = {
    val gen = KeyPairGenerator.getInstance("EC", "BC")
    gen.initialize(new ECGenParameterSpec("secp256r1"))
    gen.generateKeyPair()
  }

  private def selfSignedCert(kp: KeyPair, cn: String): java.security.cert.X509Certificate = {
    val now    = Instant.now()
    val name   = new org.bouncycastle.asn1.x500.X500Name(cn)
    val serial = BigInteger.valueOf(now.toEpochMilli)
    val builder = new JcaX509v3CertificateBuilder(
      name,
      serial,
      Date.from(now.minusSeconds(3600)),
      Date.from(now.plusSeconds(3600)),
      name,
      kp.getPublic
    )
    val signer = new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate)
    new JcaX509CertificateConverter().getCertificate(builder.build(signer))
  }

  private def signedCert(issuerKp: KeyPair, issuerName: String, subjectKp: KeyPair, cn: String): java.security.cert.X509Certificate = {
    val now    = Instant.now()
    val serial = BigInteger.valueOf(now.toEpochMilli + 1)
    val builder = new JcaX509v3CertificateBuilder(
      new org.bouncycastle.asn1.x500.X500Name(issuerName),
      serial,
      Date.from(now.minusSeconds(3600)),
      Date.from(now.plusSeconds(3600)),
      new org.bouncycastle.asn1.x500.X500Name(cn),
      subjectKp.getPublic
    )
    val signer = new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKp.getPrivate)
    new JcaX509CertificateConverter().getCertificate(builder.build(signer))
  }

  private def pem(cert: java.security.cert.X509Certificate): Array[Byte] = {
    val sw = new StringWriter()
    val pw = new PemWriter(sw)
    pw.writeObject(new PemObject("CERTIFICATE", cert.getEncoded))
    pw.close()
    sw.toString.getBytes("UTF-8")
  }

  private def signedCrl(issuerKp: KeyPair, issuerName: String, crlNumber: Long): X509CRL = {
    val now     = Instant.now()
    val builder = new X509v2CRLBuilder(new org.bouncycastle.asn1.x500.X500Name(issuerName), Date.from(now.minusSeconds(60)))
    builder.setNextUpdate(Date.from(now.plusSeconds(3600)))
    builder.addExtension(Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(crlNumber)))
    val signer = new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKp.getPrivate)
    new org.bouncycastle.cert.jcajce.JcaX509CRLConverter().getCRL(builder.build(signer))
  }

  // CRL Number is RFC 5280 optional (unlike a real Intel CRL, which always carries one, per the "against real
  // Intel-signed fixtures" group above) - DcapCollateral.rejectDowngrade must treat this the same as an
  // unparseable one: fail closed, not silently skip the freshness check.
  private def signedCrlWithoutNumber(issuerKp: KeyPair, issuerName: String): X509CRL = {
    val now     = Instant.now()
    val builder = new X509v2CRLBuilder(new org.bouncycastle.asn1.x500.X500Name(issuerName), Date.from(now.minusSeconds(60)))
    builder.setNextUpdate(Date.from(now.plusSeconds(3600)))
    val signer = new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKp.getPrivate)
    new org.bouncycastle.cert.jcajce.JcaX509CRLConverter().getCRL(builder.build(signer))
  }

  "verifyIssuerChain" - {
    "accepts a chain that terminates at the given trust anchor" in {
      val rootKp = genKeyPair()
      val root   = selfSignedCert(rootKp, "CN=Test Root")
      val leafKp = genKeyPair()
      val leaf   = signedCert(rootKp, "CN=Test Root", leafKp, "CN=Test Leaf")
      // Revocation checking is mandatory (see P256Curve.validateCertChain), so the leaf's issuer needs a CRL to
      // check it against, exactly like DcapCollateral requires Root CA CRL to already be on chain in production.
      val crl = signedCrl(rootKp, "CN=Test Root", crlNumber = 1)

      val chainPem = pem(leaf) ++ pem(root)
      val result =
        IntelPki.verifyIssuerChain(chainPem, System.currentTimeMillis(), crls = Seq(crl.getEncoded), trustAnchor = rootKp.getPublic)

      result shouldBe Right(leafKp.getPublic)
    }

    "rejects a chain whose root is not signed by the trust anchor" in {
      val rootKp   = genKeyPair()
      val root     = selfSignedCert(rootKp, "CN=Test Root")
      val leafKp   = genKeyPair()
      val leaf     = signedCert(rootKp, "CN=Test Root", leafKp, "CN=Test Leaf")
      val otherKp  = genKeyPair()
      val chainPem = pem(leaf) ++ pem(root)

      IntelPki.verifyIssuerChain(chainPem, System.currentTimeMillis(), trustAnchor = otherKp.getPublic) shouldBe a[Left[?, ?]]
    }

    "rejects a chain whose claimed root is not actually self-issued" in {
      // A "root" that is really signed by yet another key, submitted as if it were the anchor - the chain must not
      // be trusted just because its own last certificate claims to be a root.
      val realRootKp = genKeyPair()
      val fakeRootKp = genKeyPair()
      val fakeRoot   = signedCert(realRootKp, "CN=Real Root", fakeRootKp, "CN=Fake Root")
      val leafKp     = genKeyPair()
      val leaf       = signedCert(fakeRootKp, "CN=Fake Root", leafKp, "CN=Test Leaf")

      val chainPem = pem(leaf) ++ pem(fakeRoot)
      IntelPki.verifyIssuerChain(chainPem, System.currentTimeMillis(), trustAnchor = fakeRootKp.getPublic) shouldBe a[Left[?, ?]]
    }

    "rejects an empty chain" in {
      IntelPki.verifyIssuerChain(Array.emptyByteArray, System.currentTimeMillis()) shouldBe a[Left[?, ?]]
    }
  }

  "verifyCrl and crlNumberOf" - {
    "accepts a CRL signed by the given key and extracts its CRL number" in {
      val kp  = genKeyPair()
      val crl = signedCrl(kp, "CN=Test Root", crlNumber = 7)

      IntelPki.verifyCrl(crl.getEncoded, kp.getPublic, System.currentTimeMillis()) shouldBe Right(Some(BigInt(7)))
      IntelPki.crlNumberOf(crl.getEncoded) shouldBe Some(BigInt(7))
    }

    "accepts a validly-signed CRL with no CRL Number extension, but reports no number" in {
      val kp  = genKeyPair()
      val crl = signedCrlWithoutNumber(kp, "CN=Test Root")

      IntelPki.verifyCrl(crl.getEncoded, kp.getPublic, System.currentTimeMillis()) shouldBe Right(None)
      IntelPki.crlNumberOf(crl.getEncoded) shouldBe None
    }

    "rejects a CRL signed by a different key" in {
      val kp      = genKeyPair()
      val otherKp = genKeyPair()
      val crl     = signedCrl(kp, "CN=Test Root", crlNumber = 1)

      IntelPki.verifyCrl(crl.getEncoded, otherKp.getPublic, System.currentTimeMillis()) shouldBe a[Left[?, ?]]
    }

    "rejects garbage bytes" in {
      IntelPki.verifyCrl(Array[Byte](1, 2, 3), IntelPki.rootCaPublicKey, System.currentTimeMillis()) shouldBe a[Left[?, ?]]
      IntelPki.crlNumberOf(Array[Byte](1, 2, 3)) shouldBe None
    }
  }
}
