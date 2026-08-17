package tech.hearth.state.diffs

import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.{HearthSettings, PredefinedSnapshotSettings}
import tech.hearth.state.GenesisBlockHeight
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.TxHelpers

import java.time.Instant

/** Covers the accept path with the same real Intel-signed Root CA CRL fixture IntelPkiTest uses (see
  * node/tests/src/test/resources/dcap/SOURCE.md) - genesis timestamp is pinned inside its validity window so a
  * transaction's own timestamp can land there too, rather than only exercising reject paths the way a fixture-less
  * test would be limited to.
  */
class UpdateCollateralTransactionDiffTest extends FreeSpec with WithDomain {
  private val sender = TxHelpers.defaultSigner

  private def resourceBytes(name: String): ByteStr = ByteStr(getClass.getResourceAsStream(s"/dcap/$name").readAllBytes())

  // Chains are "leaf first, root last" PEM (see IntelPki.verifyIssuerChain) - signing.der alone is just the TCB
  // Signing CA's own certificate, an intermediate that is not self-issued on its own.
  private def derToPem(der: Array[Byte]): Array[Byte] = {
    val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der))
    val sw   = new java.io.StringWriter()
    val pw   = new org.bouncycastle.util.io.pem.PemWriter(sw)
    pw.writeObject(new org.bouncycastle.util.io.pem.PemObject("CERTIFICATE", cert.getEncoded))
    pw.close()
    sw.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
  }

  private val rootCaCrl: ByteStr = resourceBytes("root_crl.der")
  private val tcbSigningIssuerChain: ByteStr =
    ByteStr(derToPem(resourceBytes("signing.der").arr) ++ derToPem(resourceBytes("root.der").arr))
  private val tcbInfo: ByteStr      = resourceBytes("tcb_info_v3_sgx.json")
  private val qeIdentity: ByteStr   = resourceBytes("qe_identity.json")
  private val tcbInfoFmspc: ByteStr = ByteStr.decodeBase16("00A067110000").get

  // Inside root_crl.der's validity window (2024-03-20 to 2025-04-03, see SOURCE.md).
  private val fixtureTime: Long = Instant.parse("2024-06-01T00:00:00Z").toEpochMilli

  // Inside the intersection of every fixture's validity window, including tcb_info_v3_sgx.json (2024-08-26 to
  // 2024-09-25) and qe_identity.json (2024-09-10 to 2024-10-10) - see SOURCE.md.
  private val jsonFixtureTime: Long = Instant.parse("2024-09-15T00:00:00Z").toEpochMilli

  private def settingsAtGenesis(
      dcapRootCaCrl: Option[ByteStr] = None,
      dcapTcbSigningIssuerChain: Option[ByteStr] = None,
      dcapTcbInfo: Seq[ByteStr] = Seq.empty,
      dcapQeIdentity: Option[ByteStr] = None,
      timestamp: Long = fixtureTime
  ): HearthSettings = {
    val base = DeterministicFinality
    base.copy(blockchainSettings =
      base.blockchainSettings.copy(
        genesisSettings = base.blockchainSettings.genesisSettings.copy(timestamp = timestamp),
        predefinedSnapshots = Seq(
          PredefinedSnapshotSettings(
            GenesisBlockHeight.toInt,
            dcapRootCaCrl = dcapRootCaCrl,
            dcapTcbSigningIssuerChain = dcapTcbSigningIssuerChain,
            dcapTcbInfo = dcapTcbInfo,
            dcapQeIdentity = dcapQeIdentity
          )
        )
      )
    )
  }

  "DCAP collateral" - {
    "seeded at genesis is visible immediately" in withDomain(
      settingsAtGenesis(dcapRootCaCrl = Some(rootCaCrl)),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      d.blockchain.dcapRootCaCrl shouldBe Some(rootCaCrl)
    }

    "a permissionless update with a real, validly-signed CRL is accepted" in withDomain(
      settingsAtGenesis(),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      d.blockchain.dcapRootCaCrl shouldBe None
      d.appendBlock(TxHelpers.updateCollateral(sender, rootCaCrl = Some(rootCaCrl), timestamp = fixtureTime + 60000))
      d.blockchain.dcapRootCaCrl shouldBe Some(rootCaCrl)
    }

    "resubmitting the same CRL (equal CRL number) is not treated as a downgrade" in withDomain(
      settingsAtGenesis(dcapRootCaCrl = Some(rootCaCrl)),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      d.appendBlockE(TxHelpers.updateCollateral(sender, rootCaCrl = Some(rootCaCrl), timestamp = fixtureTime + 60000)) shouldBe Symbol("right")
      d.blockchain.dcapRootCaCrl shouldBe Some(rootCaCrl)
    }

    "rollback undoes a collateral update" in withDomain(settingsAtGenesis(), AddrWithBalance.enoughBalances(sender)) { d =>
      val heightBeforeUpdate = d.blockchain.height
      d.blockchain.dcapRootCaCrl shouldBe None

      d.appendBlock(TxHelpers.updateCollateral(sender, rootCaCrl = Some(rootCaCrl), timestamp = fixtureTime + 60000))
      d.blockchain.dcapRootCaCrl shouldBe Some(rootCaCrl)

      d.rollbackTo(heightBeforeUpdate)
      d.blockchain.dcapRootCaCrl shouldBe None
    }

    "rejects a CRL not signed by the Intel Root CA" in withDomain(settingsAtGenesis(), AddrWithBalance.enoughBalances(sender)) { d =>
      val garbage = ByteStr(Array.fill(64)(1.toByte))
      d.appendBlockE(TxHelpers.updateCollateral(sender, rootCaCrl = Some(garbage), timestamp = fixtureTime + 60000)) should produce(
        "failed to verify CRL"
      )
    }

    "rejects a PCK CA issuer chain when Root CA CRL isn't set yet" in withDomain(settingsAtGenesis(), AddrWithBalance.enoughBalances(sender)) { d =>
      d.blockchain.dcapRootCaCrl shouldBe None
      val someChain = ByteStr(Array.fill(64)(1.toByte))
      d.appendBlockE(TxHelpers.updateCollateral(sender, pckCaIssuerChain = Some(someChain), timestamp = fixtureTime + 60000)) should produce(
        "Root CA CRL must be set"
      )
    }

    "tcbSigningIssuerChain, tcbInfo and qeIdentity seeded at genesis are all visible immediately" in withDomain(
      settingsAtGenesis(
        dcapRootCaCrl = Some(rootCaCrl),
        dcapTcbSigningIssuerChain = Some(tcbSigningIssuerChain),
        dcapTcbInfo = Seq(tcbInfo),
        dcapQeIdentity = Some(qeIdentity),
        timestamp = jsonFixtureTime
      ),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      d.blockchain.dcapTcbSigningIssuerChain shouldBe Some(tcbSigningIssuerChain)
      d.blockchain.dcapTcbInfo(tcbInfoFmspc) shouldBe Some(tcbInfo)
      d.blockchain.dcapQeIdentity shouldBe Some(qeIdentity)
    }

    "a permissionless update with real, validly-signed tcbInfo and qeIdentity is accepted" in withDomain(
      settingsAtGenesis(dcapRootCaCrl = Some(rootCaCrl), dcapTcbSigningIssuerChain = Some(tcbSigningIssuerChain), timestamp = jsonFixtureTime),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      d.blockchain.dcapTcbInfo(tcbInfoFmspc) shouldBe None
      d.blockchain.dcapQeIdentity shouldBe None

      d.appendBlock(
        TxHelpers.updateCollateral(sender, tcbInfo = Some(tcbInfo), qeIdentity = Some(qeIdentity), timestamp = jsonFixtureTime + 60000)
      )

      d.blockchain.dcapTcbInfo(tcbInfoFmspc) shouldBe Some(tcbInfo)
      d.blockchain.dcapQeIdentity shouldBe Some(qeIdentity)
    }

    "rollback undoes a per-FMSPC tcbInfo update" in withDomain(
      settingsAtGenesis(dcapRootCaCrl = Some(rootCaCrl), dcapTcbSigningIssuerChain = Some(tcbSigningIssuerChain), timestamp = jsonFixtureTime),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val heightBeforeUpdate = d.blockchain.height
      d.blockchain.dcapTcbInfo(tcbInfoFmspc) shouldBe None

      d.appendBlock(TxHelpers.updateCollateral(sender, tcbInfo = Some(tcbInfo), timestamp = jsonFixtureTime + 60000))
      d.blockchain.dcapTcbInfo(tcbInfoFmspc) shouldBe Some(tcbInfo)

      d.rollbackTo(heightBeforeUpdate)
      d.blockchain.dcapTcbInfo(tcbInfoFmspc) shouldBe None
    }

    "rejects tcbInfo when no TCB Signing issuer chain can be resolved" in withDomain(
      settingsAtGenesis(dcapRootCaCrl = Some(rootCaCrl), timestamp = jsonFixtureTime),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      d.appendBlockE(TxHelpers.updateCollateral(sender, tcbInfo = Some(tcbInfo), timestamp = jsonFixtureTime + 60000)) should produce(
        "no issuer chain submitted or already on chain"
      )
    }

    "rejects tcbInfo whose content doesn't match its own signature" in withDomain(
      settingsAtGenesis(dcapRootCaCrl = Some(rootCaCrl), dcapTcbSigningIssuerChain = Some(tcbSigningIssuerChain), timestamp = jsonFixtureTime),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      // A single-field substitution deep in the signed content: still well-formed JSON, still a validly-hex-encoded
      // signature, but the signed bytes no longer match what was actually signed.
      val tampered = new String(tcbInfo.arr, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\"tcbEvaluationDataNumber\":16", "\"tcbEvaluationDataNumber\":17")
      val tamperedPayload = ByteStr(tampered.getBytes(java.nio.charset.StandardCharsets.UTF_8))

      d.appendBlockE(
        TxHelpers.updateCollateral(sender, tcbInfo = Some(tamperedPayload), timestamp = jsonFixtureTime + 60000)
      ) should produce("signature verification failed")
    }
  }
}
