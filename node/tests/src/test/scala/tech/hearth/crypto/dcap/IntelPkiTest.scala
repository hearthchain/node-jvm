package tech.hearth.crypto.dcap

import tech.hearth.test.FreeSpec
import org.bouncycastle.asn1.x509.{CRLNumber, Extension}
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.{PemObject, PemWriter}

import java.io.StringWriter
import java.math.BigInteger
import java.security.cert.X509CRL
import java.security.{KeyPair, KeyPairGenerator, Security}
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date

/** IntelPki carries the only cryptographic trust decisions in the DCAP collateral path (see the StartBoost
  * consensus plan), so it's covered directly against real, BouncyCastle-built X.509 fixtures rather than through
  * UpdateCollateralTransactionDiff/PredefinedSnapshot - those can only be tested on their reject paths, since a
  * genuine accept path needs a certificate/CRL actually signed by Intel's real Root CA private key, which nothing
  * here has.
  */
class IntelPkiTest extends FreeSpec {
  Security.addProvider(new BouncyCastleProvider)

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
