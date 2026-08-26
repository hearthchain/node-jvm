package tech.hearth.events

import cats.Monoid
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.events.StateUpdate.{ReserveUpdate, SettleUpdate}
import tech.hearth.state.diffs.{ReserveTransactionDiff, SettleTransactionDiff}
import tech.hearth.state.{Blockchain, RegisteredEnclave, SnapshotBlockchain, StateSnapshot}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.{Hearth, IssuedAsset}
import tech.hearth.transaction.SettleTransaction.Settlement
import tech.hearth.transaction.{SettleTransaction, TxHelpers, TxNonNegativeAmount}

/** Reserve and Settle updates can only be built from a snapshot produced against a registered enclave, and no
  * fixture in this repo can drive a StartBoostTransaction to its accept path to register one for real (see
  * ReserveTransactionDiffTest's own doc comment). So these tests call the two diffs directly against a Blockchain
  * wrapper injecting a RegisteredEnclave and feed the resulting snapshot to StateUpdate.atomic, instead of
  * appending Reserve/Settle transactions to a real subscription the way BlockchainUpdatesSubscribeSpec does.
  *
  * `operator` is the account that settles for the enclave, which ReserveTransaction names in its `miner` field. It
  * is a different role from the enclave's `validator`, so the two are never interchangeable here.
  */
class ReserveSettleStateUpdateSpec extends FreeSpec with WithDomain {
  private val operatorSigner   = TxHelpers.defaultSigner
  private val operator         = operatorSigner.toAddress
  private val clientSigner     = TxHelpers.secondSigner
  private val client           = clientSigner.toAddress
  private val enclaveKey       = TxHelpers.signer(9)
  private val enclavePublicKey = ByteStr(enclaveKey.publicKey())

  private val balances = AddrWithBalance.enoughBalances(operatorSigner, clientSigner)

  private def withRegisteredEnclave(blockchain: Blockchain): Blockchain =
    blockchainWithRegisteredEnclave(blockchain, RegisteredEnclave(enclavePublicKey, operator, operator))

  private def withReservation(blockchain: Blockchain, amount: Long): Blockchain = {
    val snapshot = StateSnapshot.build(blockchain, reservedAmounts = Map((client, operator, Hearth) -> amount)).explicitGet()
    SnapshotBlockchain(blockchain, snapshot)
  }

  private def settleTx(cumulativeSpent: Long): SettleTransaction =
    TxHelpers.settle(
      sender = operatorSigner,
      enclaveKey = enclaveKey,
      settlements = Seq(Settlement(client, Hearth, TxNonNegativeAmount.unsafeFrom(cumulativeSpent)))
    )

  "StateUpdate.atomic" - {
    "records a reserve as a (client, operator, asset) update" in withDomain(DeterministicFinality, balances) { d =>
      val blockchain = withRegisteredEnclave(d.blockchain)
      val snapshot   = ReserveTransactionDiff(blockchain)(TxHelpers.reserve(clientSigner, amount = 100.hearth, miner = operator)).explicitGet()

      val update = StateUpdate.atomic(blockchain, snapshot)

      update.reserves shouldBe Seq(ReserveUpdate(client, operator, Hearth, before = 0L, after = 100.hearth))
      update.settlements shouldBe empty
    }

    "records a settlement as a (client, operator, asset) update" in withDomain(DeterministicFinality, balances) { d =>
      val blockchain = withReservation(withRegisteredEnclave(d.blockchain), 100.hearth)
      val snapshot   = SettleTransactionDiff(blockchain)(settleTx(40L)).explicitGet()

      val update = StateUpdate.atomic(blockchain, snapshot)

      update.settlements shouldBe Seq(SettleUpdate(client, operator, Hearth, before = 0L, after = 40L))
      // Settle never writes reservedAmounts, it only reads them as a ceiling.
      update.reserves shouldBe empty
    }
  }

  "StateUpdate.monoid" - {
    "keeps the earliest before and the latest after across several reserves in one block" in withDomain(DeterministicFinality, balances) { d =>
      val (first, second) = twoReserveUpdates(d.blockchain)

      Monoid.combine(first, second).reserves shouldBe Seq(ReserveUpdate(client, operator, Hearth, before = 0L, after = 150.hearth))
    }

    "keeps the earliest before and the latest after across several settlements in one block" in withDomain(DeterministicFinality, balances) { d =>
      val (first, second) = twoSettleUpdates(d.blockchain)

      Monoid.combine(first, second).settlements shouldBe Seq(SettleUpdate(client, operator, Hearth, before = 0L, after = 100L))
    }

    "keeps reserves of different assets to the same operator apart" in withDomain(DeterministicFinality, balances) { d =>
      val otherAsset = IssuedAsset(ByteStr.fill(32)(7))
      val first      = StateUpdate.atomic(d.blockchain, StateSnapshot(reservedAmounts = Map((client, operator, Hearth) -> 10L)))
      val second     = StateUpdate.atomic(d.blockchain, StateSnapshot(reservedAmounts = Map((client, operator, otherAsset) -> 5L)))

      Monoid.combine(first, second).reserves shouldBe Seq(
        ReserveUpdate(client, operator, Hearth, before = 0L, after = 10L),
        ReserveUpdate(client, operator, otherAsset, before = 0L, after = 5L)
      )
    }
  }

  "StateUpdate.reverse" - {
    "restores the block's starting reserve on a rollback" in withDomain(DeterministicFinality, balances) { d =>
      val (first, second) = twoReserveUpdates(d.blockchain)

      reverseOf(first, second).reserves shouldBe Seq(ReserveUpdate(client, operator, Hearth, before = 150.hearth, after = 0L))
    }

    "restores the block's starting settled total on a rollback" in withDomain(DeterministicFinality, balances) { d =>
      val (first, second) = twoSettleUpdates(d.blockchain)

      reverseOf(first, second).settlements shouldBe Seq(SettleUpdate(client, operator, Hearth, before = 100L, after = 0L))
    }
  }

  "StateUpdate protobuf conversion" - {
    "round-trips reserves and settlements" in {
      val update = StateUpdate(
        balances = Seq.empty,
        leasingForAddress = Seq.empty,
        assets = Seq.empty,
        leases = Seq.empty,
        reserves = Seq(ReserveUpdate(client, operator, Hearth, before = 1L, after = 2L)),
        settlements = Seq(SettleUpdate(client, operator, IssuedAsset(ByteStr.fill(32)(7)), before = 3L, after = 4L))
      )

      StateUpdate.fromPB(StateUpdate.toPB(update)) shouldBe update
    }
  }

  // The same "keep earliest before, latest after" merge a block performs over its transactions, in reverse order,
  // which is exactly what BlockAppended.reverseStateUpdate emits as the rollback event.
  private def reverseOf(updates: StateUpdate*): StateUpdate = Monoid.combineAll(updates.map(_.reverse).reverse)

  private def twoReserveUpdates(base: Blockchain): (StateUpdate, StateUpdate) = {
    val blockchain   = withRegisteredEnclave(base)
    val snapshot     = ReserveTransactionDiff(blockchain)(TxHelpers.reserve(clientSigner, amount = 100.hearth, miner = operator)).explicitGet()
    val next         = SnapshotBlockchain(blockchain, snapshot)
    val nextSnapshot = ReserveTransactionDiff(next)(TxHelpers.reserve(clientSigner, amount = 50.hearth, miner = operator)).explicitGet()
    (StateUpdate.atomic(blockchain, snapshot), StateUpdate.atomic(next, nextSnapshot))
  }

  private def twoSettleUpdates(base: Blockchain): (StateUpdate, StateUpdate) = {
    val blockchain   = withReservation(withRegisteredEnclave(base), 100.hearth)
    val snapshot     = SettleTransactionDiff(blockchain)(settleTx(40L)).explicitGet()
    val next         = SnapshotBlockchain(blockchain, snapshot)
    val nextSnapshot = SettleTransactionDiff(next)(settleTx(100L)).explicitGet()
    (StateUpdate.atomic(blockchain, snapshot), StateUpdate.atomic(next, nextSnapshot))
  }
}
