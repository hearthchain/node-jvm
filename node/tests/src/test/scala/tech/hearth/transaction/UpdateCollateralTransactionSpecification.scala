package tech.hearth.transaction

import tech.hearth.common.state.ByteStr
import tech.hearth.test.*

class UpdateCollateralTransactionSpecification extends FreeSpec {
  private val sender = TxHelpers.defaultSigner.publicKey

  private def create(
      rootCaCrl: Option[ByteStr] = None,
      pckCrl: Option[ByteStr] = None,
      tcbInfo: Option[ByteStr] = None,
      qeIdentity: Option[ByteStr] = None,
      tcbSigningIssuerChain: Option[ByteStr] = None,
      pckCaIssuerChain: Option[ByteStr] = None
  ) =
    UpdateCollateralTransaction.create(
      tech.hearth.account.PublicKey(sender),
      rootCaCrl,
      pckCrl,
      tcbInfo,
      qeIdentity,
      tcbSigningIssuerChain,
      pckCaIssuerChain,
      1000000,
      System.currentTimeMillis(),
      Proofs.empty
    )

  "validation" - {
    "rejects a transaction with no collateral fields set" in {
      create() should produce("UpdateCollateral transaction must set at least one field")
    }

    "accepts a transaction with only rootCaCrl set" in {
      create(rootCaCrl = Some(ByteStr.empty)) shouldBe Symbol("right")
    }

    "accepts a transaction with only tcbSigningIssuerChain set" in {
      create(tcbSigningIssuerChain = Some(ByteStr.empty)) shouldBe Symbol("right")
    }

    "accepts a transaction with only pckCaIssuerChain set" in {
      create(pckCaIssuerChain = Some(ByteStr.empty)) shouldBe Symbol("right")
    }
  }
}
