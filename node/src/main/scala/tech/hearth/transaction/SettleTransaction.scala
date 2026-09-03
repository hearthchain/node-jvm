package tech.hearth.transaction

import cats.instances.list.*
import cats.syntax.traverse.*
import com.google.common.primitives.{Ints, Longs, Shorts}
import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.SettleTransaction.Settlement
import tech.hearth.transaction.TxValidationError.*
import tech.hearth.transaction.serialization.impl.SettleTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.SettleTxValidator
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json, OFormat}

import java.nio.charset.StandardCharsets

/** See SettleTransactionDiff for the real semantics: a miner submits an enclave-signed batch of (client, cumulative
  * spent) settlements, retiring the settled portion of what that client reserved with it (see ReserveTransaction).
  */
final case class SettleTransaction(
    sender: PublicKey,
    enclavePublicKey: ByteStr,
    settlements: Seq[Settlement],
    enclaveSignature: ByteStr,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    networkId: NetworkId
) extends Transaction(TransactionType.Settle),
      ProvenTransaction,
      TxWithFee.InHearth,
      FastHashId {
  override type T = SettleTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(SettleTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): SettleTransaction = copy(proofs = this.proofs.add(proof))
}

object SettleTransaction {
  // Bounds mempool/block/storage space per transaction, same role MaxTransferCount plays for TransferTransaction.
  val MaxSettlementCount = 100

  implicit val validator: TxValidator[SettleTransaction] = SettleTxValidator

  /** One client's cumulative spend against what it reserved with this transaction's sender (the miner). Only an
    * issued asset can be settled - Hearth is not a settleable asset.
    */
  final case class Settlement(client: Address, assetId: IssuedAsset, cumulativeSpent: TxNonNegativeAmount)

  /** The REST-facing shape of a Settlement, mirroring TransferTransaction.Transfer/parseTransfersList. */
  final case class SettlementRequest(client: String, assetId: IssuedAsset, cumulativeSpent: Long)

  object SettlementRequest {
    implicit val jsonFormat: OFormat[SettlementRequest] = Json.format[SettlementRequest]
  }

  def parseSettlementsList(settlements: List[SettlementRequest]): Validation[List[Settlement]] =
    settlements.traverse { case SettlementRequest(client, assetId, cumulativeSpent) =>
      for {
        address <- Address.fromString(client)
        amount  <- TxNonNegativeAmount(cumulativeSpent)(NegativeAmount(cumulativeSpent, "asset"))
      } yield Settlement(address, assetId, amount)
    }

  private val SettleDomain: Array[Byte] = "hearth-settle-v1".getBytes(StandardCharsets.UTF_8)

  /** The enclave-signed preimage, bound to context so a batch cannot be replayed on another network, operator or
    * period: domain(16) ++ networkId(1 to NetworkId.MaxLength) ++ enclaveKey(32) ++ operator(20) ++
    * periodStart(4 BE) ++ count(2 BE), then client(20) ++ assetId(32) ++ cumulativeSpent(8 BE) per settlement. The
    * diff rebuilds this from the transaction and chain state, never from the batch alone.
    *
    * Nothing parses this, but the encoding still has to be injective: verification is a byte equality check, so two
    * distinct field tuples encoding alike would let one enclave signature authorise both. Every field but the
    * network id is fixed-width, which is enough only because SettleTxValidator separately rejects an assetId of any
    * length other than 32 (see its own comment): the wire format itself (PBAmounts.toVanillaAssetId) does not bound
    * an IssuedAsset id's length, so without that check a batch could be re-split into different (client, assetId,
    * cumulativeSpent) triples with the same bytes. Every SettleTransaction that reaches here has already been
    * through it, via SettleTransaction.create's `validatedEither` call.
    *
    * The variable-width network id needs no length prefix to stay injective: total length is 74 + k + 60n for a
    * k-character network id and n settlements, so equal encodings force k1 - k2 = 60(n2 - n1), and with
    * k <= NetworkId.MaxLength the only non-trivial solution needs the longer network id to end in a periodStart
    * whose big-endian bytes are all lowercase ASCII - impossible below height 0x61616161.
    */
  def mkSettlementMessage(
      networkId: NetworkId,
      enclaveKey: ByteStr,
      operator: Address,
      periodStart: Int,
      settlements: Seq[Settlement]
  ): Array[Byte] = {
    val prefix = SettleDomain ++ networkId.value.getBytes(StandardCharsets.US_ASCII) ++ enclaveKey.arr ++ operator.toBytes ++
      Ints.toByteArray(periodStart) ++ Shorts.toByteArray(settlements.length.toShort)
    settlements.foldLeft(prefix) { (acc, s) =>
      acc ++ s.client.toBytes ++ s.assetId.id.arr ++ Longs.toByteArray(s.cumulativeSpent.value)
    }
  }

  def create(
      sender: PublicKey,
      enclavePublicKey: ByteStr,
      settlements: Seq[Settlement],
      enclaveSignature: ByteStr,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      networkId: NetworkId = NetworkId.current
  ): Either[ValidationError, SettleTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- SettleTransaction(sender, enclavePublicKey, settlements, enclaveSignature, fee, timestamp, proofs, networkId).validatedEither
    } yield tx
}
