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

  private val rootCaCrl: ByteStr = ByteStr(getClass.getResourceAsStream("/dcap/root_crl.der").readAllBytes())
  // Inside root_crl.der's validity window (2024-03-20 to 2025-04-03, see SOURCE.md).
  private val fixtureTime: Long = Instant.parse("2024-06-01T00:00:00Z").toEpochMilli

  private def settingsAtGenesis(dcapRootCaCrl: Option[ByteStr] = None): HearthSettings = {
    val base = DeterministicFinality
    base.copy(blockchainSettings =
      base.blockchainSettings.copy(
        genesisSettings = base.blockchainSettings.genesisSettings.copy(timestamp = fixtureTime),
        predefinedSnapshots = Seq(PredefinedSnapshotSettings(GenesisBlockHeight.toInt, dcapRootCaCrl = dcapRootCaCrl))
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
  }
}
