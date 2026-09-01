package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.account.{Address, NetworkId, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.{Blockchain, CommittedGenerator, GenerationPeriod, RegisteredEnclave, SnapshotBlockchain, StateSnapshot}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.{Hearth, IssuedAsset}
import tech.hearth.transaction.SettleTransaction.{MaxSettlementCount, Settlement}

/** Same testing constraint as ReserveTransactionDiffTest/BindApiKeyTransactionDiffTest: a registered enclave public
  * key, in production, only comes from a verified StartBoostTransaction, which no test fixture in this repo can
  * drive to its accept path. The accept path here is exercised by calling SettleTransactionDiff directly against a
  * Blockchain wrapper that injects a RegisteredEnclave entry, and Blockchain.reservedAmount/settledAmount via a
  * SnapshotBlockchain layered on top of that.
  */
class SettleTransactionDiffTest extends FreeSpec with WithDomain {
  private val sender           = TxHelpers.defaultSigner
  private val miner            = sender.toAddress
  private val client           = TxHelpers.secondSigner.toAddress
  private val enclaveKey       = TxHelpers.signer(9)
  private val enclavePublicKey = ByteStr(enclaveKey.publicKey())

  // Only an issued asset can be settled (see SettleTransaction.Settlement), and SettleTransactionDiff rejects one
  // that was never issued - so every test settles this genesis asset, held in full by the client that reserves it.
  private val asset         = IssuedAsset(ByteStr.fill(32)(7))
  private val assetQuantity = 1000L

  private def withSettleDomain[A](f: Domain => A): A =
    withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender) :+ AddrWithBalance(client, assets = Map(asset -> assetQuantity)),
      assets = Seq(GenesisAssetSettings(asset.id, "Settled", decimals = 0, quantity = assetQuantity, minFee = TestValues.fee))
    )(f)

  private def withRegisteredEnclave(blockchain: Blockchain, operator: Address = miner, validator: Address = miner): Blockchain =
    blockchainWithRegisteredEnclave(blockchain, RegisteredEnclave(enclavePublicKey, validator, operator))

  // SettleTransactionDiff requires registered.validator to be a committed generator of the *current* period (see
  // its own comment on that check) - miner already is one (withDomain auto-commits the default signer for the
  // genesis period), but a synthetic validator distinct from the miner needs this injected the same way
  // withRegisteredEnclave injects the RegisteredEnclave itself.
  private def withCommittedGenerator(blockchain: Blockchain, period: GenerationPeriod, validator: Address): Blockchain =
    new Blockchain {
      export blockchain.{committedGenerators as _, *}
      override def committedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator] =
        if (at == period) blockchain.committedGenerators(at) :+ CommittedGenerator(validator, TxHelpers.defaultBlsKey.publicKey, ByteStr.empty)
        else blockchain.committedGenerators(at)
    }

  private def withReservation(blockchain: Blockchain, amount: Long): Blockchain = {
    val snapshot = StateSnapshot.build(blockchain, reservedAmounts = Map((client, miner, asset) -> amount)).explicitGet()
    SnapshotBlockchain(blockchain, snapshot)
  }

  private def settleTx(cumulativeSpent: Long): SettleTransaction =
    TxHelpers.settle(
      sender = sender,
      enclaveKey = enclaveKey,
      settlements = Seq(Settlement(client, asset, TxNonNegativeAmount.unsafeFrom(cumulativeSpent)))
    )

  "SettleTransactionDiff" - {
    "rejects an enclave public key that is not registered" in withSettleDomain { d =>
      d.appendBlockE(settleTx(1L)) should produce("is not a registered enclave public key")
    }

    "rejects a sender that is not the enclave's operator" in withSettleDomain { d =>
      val blockchain = withRegisteredEnclave(d.blockchain, operator = client)
      SettleTransactionDiff(blockchain)(settleTx(1L)) should produce("is not the operator of enclave")
    }

    "rejects an invalid enclave signature" in withSettleDomain { d =>
      val blockchain  = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      val settlements = Seq(Settlement(client, asset, TxNonNegativeAmount.unsafeFrom(50L)))
      // Same (registered) enclavePublicKey as settleTx, but signed by a different key - the registry lookup
      // succeeds, so this actually exercises the signature check rather than failing earlier on it.
      val message        = SettleTransaction.mkSettlementMessage(NetworkId.current, enclavePublicKey, miner, 1, settlements)
      val wrongSignature = ByteStr(TxHelpers.signer(10).sign(message))
      val forged = SettleTransaction
        .create(PublicKey(sender.publicKey()), enclavePublicKey, settlements, wrongSignature, 100000, TxHelpers.timestamp, Proofs.empty)
        .explicitGet()
        .signWith(sender)
      SettleTransactionDiff(blockchain)(forged) should produce("Invalid enclave signature")
    }

    // The diff rebuilds the signed message from the transaction's networkId, the sender (operator) and the chain's
    // current period, never from the batch, so a batch the enclave signed for a different context does not verify.
    // Each case signs a valid batch with the correct enclave key but a wrong prefix field.
    def settleSignedFor(networkId: NetworkId, operator: Address, periodStart: Int): SettleTransaction = {
      val settlements = Seq(Settlement(client, asset, TxNonNegativeAmount.unsafeFrom(50L)))
      val message     = SettleTransaction.mkSettlementMessage(networkId, enclavePublicKey, operator, periodStart, settlements)
      val signature   = ByteStr(enclaveKey.sign(message))
      SettleTransaction
        .create(PublicKey(sender.publicKey()), enclavePublicKey, settlements, signature, 100000, TxHelpers.timestamp, Proofs.empty)
        .explicitGet()
        .signWith(sender)
    }

    "rejects a batch the enclave signed for another operator" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      val blockchain = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      SettleTransactionDiff(blockchain)(settleSignedFor(NetworkId.current, client, 1)) should
        produce("Invalid enclave signature")
    }

    "rejects a batch the enclave signed for another period" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      val blockchain = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      SettleTransactionDiff(blockchain)(settleSignedFor(NetworkId.current, miner, 2)) should
        produce("Invalid enclave signature")
    }

    "rejects a batch the enclave signed for another network" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      val blockchain = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      SettleTransactionDiff(blockchain)(settleSignedFor(NetworkId.Mainnet, miner, 1)) should
        produce("Invalid enclave signature")
    }

    "rejects settling for a client with no open reservation" in withSettleDomain { d =>
      val blockchain = withRegisteredEnclave(d.blockchain)
      SettleTransactionDiff(blockchain)(settleTx(1L)) should produce("has no open reservation")
    }

    "rejects a cumulative counter exceeding the total reserved" in withSettleDomain { d =>
      val blockchain = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      SettleTransactionDiff(blockchain)(settleTx(101L)) should produce("exceeds total reserved")
    }

    "accepts a settlement within the total reserved, crediting the miner its fee-debited balance plus the node's share" in withSettleDomain { d =>
      val blockchain     = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      val hearthBefore   = blockchain.balance(miner)
      val tx             = settleTx(60L)
      val snapshot       = SettleTransactionDiff(blockchain)(tx).explicitGet()
      val expectedCredit = SettleTransactionDiff.ServingNodeCredPart(60L) // 60 / 10 * 3 = 18

      snapshot.settledAmounts((client, miner, asset)) shouldBe 60L
      // The fee is charged in Hearth, the node's share of the settlement in the settled asset - the miner holds
      // none of it at genesis, so the credit is its whole asset balance.
      snapshot.balances((miner, Hearth)) shouldBe hearthBefore - tx.fee.value
      snapshot.balances((miner, asset)) shouldBe expectedCredit
    }

    "credits the serving node its share truncated (not rounded), matching Fraction's own semantics" in withSettleDomain { d =>
      val blockchain = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      val snapshot   = SettleTransactionDiff(blockchain)(settleTx(61L)).explicitGet() // 61 / 10 * 3 = 6 * 3 = 18, not 18.3

      snapshot.balances((miner, asset)) shouldBe 18L
    }

    "credits the serving node incrementally, so splitting one settlement across two transactions credits the same total" in withSettleDomain { d =>
      val blockchain1  = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      val hearthBefore = blockchain1.balance(miner)

      val tx1         = settleTx(40L) // credits 40 / 10 * 3 = 12
      val snapshot1   = SettleTransactionDiff(blockchain1)(tx1).explicitGet()
      val blockchain2 = SnapshotBlockchain(blockchain1, snapshot1)

      val tx2       = settleTx(100L) // delta 60, credits 60 / 10 * 3 = 18 more
      val snapshot2 = SettleTransactionDiff(blockchain2)(tx2).explicitGet()

      val totalFees = tx1.fee.value + tx2.fee.value
      snapshot2.balances((miner, Hearth)) shouldBe hearthBefore - totalFees
      snapshot2.balances((miner, asset)) shouldBe 12L + 18L
      // Same total the node would have received from a single settlement straight to 100 (100 / 10 * 3 = 30).
      (12L + 18L) shouldBe SettleTransactionDiff.ServingNodeCredPart(100L)
    }

    "rejects a settlement counter that decreases from what's already recorded" in withSettleDomain { d =>
      val blockchain1 = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      val snapshot1   = SettleTransactionDiff(blockchain1)(settleTx(60L)).explicitGet()
      val blockchain2 = SnapshotBlockchain(blockchain1, snapshot1)

      SettleTransactionDiff(blockchain2)(settleTx(59L)) should produce("would decrease")
    }

    "accepts a non-decreasing settlement counter across multiple transactions" in withSettleDomain { d =>
      val blockchain1 = withReservation(withRegisteredEnclave(d.blockchain), 100L)
      val snapshot1   = SettleTransactionDiff(blockchain1)(settleTx(60L)).explicitGet()
      val blockchain2 = SnapshotBlockchain(blockchain1, snapshot1)

      val snapshot2 = SettleTransactionDiff(blockchain2)(settleTx(80L)).explicitGet()
      snapshot2.settledAmounts((client, miner, asset)) shouldBe 80L
    }

    "rejects settling an asset that was never issued" in withSettleDomain { d =>
      val unknownAsset = IssuedAsset(ByteStr.fill(32)(9))
      // Injected directly (bypassing a real Reserve, which would itself reject an unissued asset) so this test
      // exercises SettleTransactionDiff's own defence-in-depth check, not Reserve's.
      val blockchain = withRegisteredEnclave(d.blockchain)
      val snapshot   = StateSnapshot.build(blockchain, reservedAmounts = Map((client, miner, unknownAsset) -> 100L)).explicitGet()
      val tx = TxHelpers.settle(
        sender = sender,
        enclaveKey = enclaveKey,
        settlements = Seq(Settlement(client, unknownAsset, TxNonNegativeAmount.unsafeFrom(1L)))
      )
      SettleTransactionDiff(SnapshotBlockchain(blockchain, snapshot))(tx) should produce("is not issued")
    }

    "sums the serving node's credit across different clients settled in the same batch" in withSettleDomain { d =>
      val otherClient = TxHelpers.signer(11).toAddress
      val snapshot = StateSnapshot
        .build(
          withRegisteredEnclave(d.blockchain),
          reservedAmounts = Map((client, miner, asset) -> 100L, (otherClient, miner, asset) -> 100L)
        )
        .explicitGet()
      val blockchain   = SnapshotBlockchain(withRegisteredEnclave(d.blockchain), snapshot)
      val hearthBefore = blockchain.balance(miner)
      val settlements = Seq(
        Settlement(client, asset, TxNonNegativeAmount.unsafeFrom(40L)),
        Settlement(otherClient, asset, TxNonNegativeAmount.unsafeFrom(60L))
      )
      val tx             = TxHelpers.settle(sender = sender, enclaveKey = enclaveKey, settlements = settlements)
      val resultSnapshot = SettleTransactionDiff(blockchain)(tx).explicitGet()

      resultSnapshot.settledAmounts((client, miner, asset)) shouldBe 40L
      resultSnapshot.settledAmounts((otherClient, miner, asset)) shouldBe 60L
      resultSnapshot.balances((miner, Hearth)) shouldBe hearthBefore - tx.fee.value
      // 40 / 10 * 3 = 12, 60 / 10 * 3 = 18, summed across both clients into the miner's one Portfolio.
      resultSnapshot.balances((miner, asset)) shouldBe 12L + 18L
    }

    "rejects settling when the validator is not a committed generator of the current period" in withSettleDomain { d =>
      val uncommittedValidator = TxHelpers.signer(13).toAddress
      // Deliberately no withCommittedGenerator here: registeredEnclave alone (StartBoost's own check) only ever
      // confirmed committee membership for the period the enclave registered *for*, not necessarily this one -
      // see SettleTransactionDiff's own comment on this check.
      val blockchain = withReservation(withRegisteredEnclave(d.blockchain, validator = uncommittedValidator), 100L)
      SettleTransactionDiff(blockchain)(settleTx(60L)) should produce("is not a committed generator of period")
    }

    "accumulates burned work for the settling enclave's validator, not the miner" in withSettleDomain { d =>
      val validator     = TxHelpers.signer(13).toAddress
      val period        = d.blockchain.currentGenerationPeriod.get
      val withValidator = withCommittedGenerator(withRegisteredEnclave(d.blockchain, validator = validator), period, validator)
      val blockchain    = withReservation(withValidator, 100L)
      val tx            = settleTx(60L) // delta 60, burned work = 60 / 10 * 6 = 36
      val snapshot      = SettleTransactionDiff(blockchain)(tx).explicitGet()

      snapshot.workDone((validator, period)) shouldBe 36L
      snapshot.workDone.contains((miner, period)) shouldBe false
    }

    "accumulates work incrementally across transactions, keyed by validator and period" in withSettleDomain { d =>
      val validator     = TxHelpers.signer(13).toAddress
      val period        = d.blockchain.currentGenerationPeriod.get
      val withValidator = withCommittedGenerator(withRegisteredEnclave(d.blockchain, validator = validator), period, validator)
      val blockchain1   = withReservation(withValidator, 100L)

      val snapshot1 = SettleTransactionDiff(blockchain1)(settleTx(40L)).explicitGet() // work 40 / 10 * 6 = 24
      snapshot1.workDone((validator, period)) shouldBe 24L
      val blockchain2 = SnapshotBlockchain(blockchain1, snapshot1)

      val snapshot2 = SettleTransactionDiff(blockchain2)(settleTx(100L)).explicitGet() // delta 60, work 36 more
      snapshot2.workDone((validator, period)) shouldBe 60L
    }

    "checks the running total within the same batch, not just what's already on chain" in withSettleDomain { d =>
      val blockchain = withReservation(withRegisteredEnclave(d.blockchain), 100L)

      val accepting = Seq(
        Settlement(client, asset, TxNonNegativeAmount.unsafeFrom(40L)),
        Settlement(client, asset, TxNonNegativeAmount.unsafeFrom(60L))
      )
      val acceptedSnapshot = SettleTransactionDiff(blockchain)(
        TxHelpers.settle(sender = sender, enclaveKey = enclaveKey, settlements = accepting)
      ).explicitGet()
      acceptedSnapshot.settledAmounts((client, miner, asset)) shouldBe 60L

      val decreasing = Seq(
        Settlement(client, asset, TxNonNegativeAmount.unsafeFrom(60L)),
        Settlement(client, asset, TxNonNegativeAmount.unsafeFrom(40L))
      )
      SettleTransactionDiff(blockchain)(
        TxHelpers.settle(sender = sender, enclaveKey = enclaveKey, settlements = decreasing)
      ) should produce("would decrease")
    }
  }

  "SettleTransaction.create" - {
    "rejects more settlements than MaxSettlementCount" in {
      val tooMany = List.fill(MaxSettlementCount + 1)(Settlement(client, asset, TxNonNegativeAmount.unsafeFrom(1L)))
      SettleTransaction.create(
        PublicKey(sender.publicKey()),
        enclavePublicKey,
        tooMany,
        ByteStr.fill(64)(1),
        100000,
        TxHelpers.timestamp,
        Proofs.empty
      ) should produce(s"Number of settlements ${tooMany.length} is greater than $MaxSettlementCount")
    }

    "rejects a settlement whose assetId is not exactly 32 bytes" in {
      val malformedAsset = IssuedAsset(ByteStr.fill(10)(1))
      SettleTransaction.create(
        PublicKey(sender.publicKey()),
        enclavePublicKey,
        Seq(Settlement(client, malformedAsset, TxNonNegativeAmount.unsafeFrom(1L))),
        ByteStr.fill(64)(1),
        100000,
        TxHelpers.timestamp,
        Proofs.empty
      ) should produce("Every settlement's assetId must be")
    }

    // Cross-language vector shared with the miner's internal/settle: identical inputs must yield this exact
    // preimage, so a batch the miner signs verifies here byte for byte.
    "mkSettlementMessage matches the cross-language vector" in {
      val enclave  = ByteStr((0 until 32).map(_.toByte).toArray)
      val operator = Address.fromBytes(Array.fill(20)(0xaa.toByte)).explicitGet()
      val c1       = Address.fromBytes(Array.fill(20)(0x11.toByte)).explicitGet()
      val c2       = Address.fromBytes(Array.fill(20)(0x22.toByte)).explicitGet()
      // a1's all-zero id keeps this vector byte-identical to the one pinned before Hearth stopped being
      // settleable: Hearth used to encode as 32 zero bytes, so the miner side's copy still matches unchanged.
      val a1 = IssuedAsset(ByteStr(new Array[Byte](32)))
      val a2 = IssuedAsset(ByteStr(Array.fill(32)(0x33.toByte)))
      val settlements = Seq(
        Settlement(c1, a1, TxNonNegativeAmount.unsafeFrom(600000L)),
        Settlement(c2, a2, TxNonNegativeAmount.unsafeFrom(800000L))
      )
      val message = SettleTransaction.mkSettlementMessage(NetworkId.Mainnet, enclave, operator, 1000001, settlements)
      Base16.encode(message) shouldBe
        "6865617274682d736574746c652d763168727468000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1faaaaaaaaaaaaaaaa" +
        "aaaaaaaaaaaaaaaaaaaaaaaa000f42410002111111111111111111111111111111111111111100000000000000000000000000000000000000000000" +
        "0000000000000000000000000000000927c0222222222222222222222222222222222222222233333333333333333333333333333333333333333333" +
        "3333333333333333333300000000000c3500"
    }
  }
}
