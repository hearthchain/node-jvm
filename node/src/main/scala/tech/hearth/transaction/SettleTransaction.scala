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
    chainId: Byte
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
    * period: domain(16) ++ chainId(1) ++ enclaveKey(32) ++ operator(20) ++ periodStart(4 BE) ++ count(2 BE), then
    * client(20) ++ assetId(32) ++ cumulativeSpent(8 BE) per settlement. The diff rebuilds this from the
    * transaction and chain state, never from the batch alone.
    *
    * Fixed-width fields throughout, so the concatenation has no parsing ambiguity between entries - but only
    * because SettleTxValidator separately rejects an assetId of any length other than 32 (see its own comment):
    * the wire format itself (PBAmounts.toVanillaAssetId) does not bound an IssuedAsset id's length, so this
    * function alone cannot guarantee a unique factorization back into (client, assetId, cumulativeSpent) triples.
    * Every SettleTransaction that reaches here has already gone through that check, via SettleTransaction
    * .create's `validatedEither` call.
    */
  def mkSettlementMessage(
      chainId: Byte,
      enclaveKey: ByteStr,
      operator: Address,
      periodStart: Int,
      settlements: Seq[Settlement]
  ): Array[Byte] = {
    val prefix = SettleDomain ++ Array(chainId) ++ enclaveKey.arr ++ operator.toBytes ++
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
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, SettleTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- SettleTransaction(sender, enclavePublicKey, settlements, enclaveSignature, fee, timestamp, proofs, chainId).validatedEither
    } yield tx
}
