package tech.hearth.state.diffs

import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.state.{Blockchain, RegisteredEnclave, SnapshotBlockchain}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.TxHelpers

/** Same testing constraint as ReserveTransactionDiffTest: a registered enclave public key, in production, only
  * comes from a verified StartBoostTransaction, which no test fixture in this repo can drive to its accept path
  * (see StartBoostTransactionDiffTest's own doc comment). The accept path here is exercised by calling
  * BindApiKeyTransactionDiff directly against a Blockchain wrapper that injects a RegisteredEnclave entry.
  */
class BindApiKeyTransactionDiffTest extends FreeSpec with WithDomain {
  private val sender           = TxHelpers.defaultSigner
  private val validator        = TxHelpers.secondSigner.toAddress
  private val enclavePublicKey = ByteStr.fill(32)(1)

  private def withRegisteredEnclave(blockchain: Blockchain, enclavePublicKey: ByteStr): Blockchain =
    blockchainWithRegisteredEnclave(blockchain, RegisteredEnclave(enclavePublicKey, validator, validator))

  "BindApiKeyTransactionDiff" - {
    "rejects an enclave public key that is not registered" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      d.appendBlockE(TxHelpers.bindApiKey(sender, enclavePublicKey = enclavePublicKey)) should produce(
        "is not a registered enclave public key"
      )
    }

    "accepts and stores the binding for a registered enclave public key" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val blockchain      = withRegisteredEnclave(d.blockchain, enclavePublicKey)
      val encryptedApiKey = ByteStr.fill(100)(2)
      val tx              = TxHelpers.bindApiKey(sender, enclavePublicKey = enclavePublicKey, encryptedApiKey = encryptedApiKey)
      val snapshot        = BindApiKeyTransactionDiff(blockchain)(tx).explicitGet()

      snapshot.apiKeyBindings((enclavePublicKey, sender.toAddress)) shouldBe encryptedApiKey
    }

    "overwrites a previous binding for the same (enclavePublicKey, sender)" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val blockchain = withRegisteredEnclave(d.blockchain, enclavePublicKey)

      val firstKey = ByteStr.fill(100)(2)
      val snapshot1 = BindApiKeyTransactionDiff(blockchain)(
        TxHelpers.bindApiKey(sender, enclavePublicKey = enclavePublicKey, encryptedApiKey = firstKey)
      ).explicitGet()

      val blockchain2 = SnapshotBlockchain(blockchain, snapshot1)
      val secondKey   = ByteStr.fill(100)(3)
      val snapshot2 = BindApiKeyTransactionDiff(blockchain2)(
        TxHelpers.bindApiKey(sender, enclavePublicKey = enclavePublicKey, encryptedApiKey = secondKey)
      ).explicitGet()

      snapshot2.apiKeyBindings((enclavePublicKey, sender.toAddress)) shouldBe secondKey
    }
  }
}
