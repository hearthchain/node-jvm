package tech.hearth.api.http

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.{JsonSerializer, SerializerProvider}
import tech.hearth.account.Address
import tech.hearth.api.common.TransactionMeta
import tech.hearth.api.http.StreamSerializerUtils.*
import tech.hearth.api.http.TransactionJsonSerializer.*
import tech.hearth.api.http.TransactionsApiRoute.{ApplicationStatus, LeaseStatus, TxMetaEnriched}
import tech.hearth.common.state.ByteStr
import tech.hearth.state.{Blockchain, Height, LeaseDetails, TransactionId, TxMeta}
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.MassTransferTransaction
import tech.hearth.transaction.{Asset, Transaction}
import play.api.libs.json.*
import play.api.libs.json.JsonConfiguration.Aux

final case class TransactionJsonSerializer(blockchain: Blockchain) {

  val assetSerializer: JsonSerializer[Asset] =
    (value: Asset, gen: JsonGenerator, _) => {
      value match {
        case Waves           => gen.writeNull()
        case IssuedAsset(id) => gen.writeString(id.toString)
      }
    }

  val leaseStatusSerializer: JsonSerializer[LeaseStatus] =
    (status: LeaseStatus, gen: JsonGenerator, _) => {
      if (status == LeaseStatus.active) gen.writeString("active") else gen.writeString("canceled")
    }

  def leaseRefSerializer(numbersAsString: Boolean): JsonSerializer[LeaseRef] =
    (l: LeaseRef, gen: JsonGenerator, _) => {
      gen.writeStartObject()
      gen.writeStringField("id", l.id.toString)
      l.originTransactionId.fold(gen.writeNullField("originTransactionId"))(txId => gen.writeStringField("originTransactionId", txId.toString))
      l.sender.fold(gen.writeNullField("sender"))(sender => gen.writeStringField("sender", sender.toBech32))
      l.recipient.fold(gen.writeNullField("recipient"))(recipient => gen.writeStringField("recipient", recipient.toBech32))
      l.amount.fold(gen.writeNullField("amount"))(amount => gen.writeNumberField("amount", amount, numbersAsString))
      l.height.fold(gen.writeNullField("height"))(height => gen.writeNumberField("height", height.toInt, numbersAsString))
      gen.writeStringField("status", if (l.status == LeaseStatus.active) "active" else "canceled")
      l.cancelHeight.fold(gen.writeNullField("cancelHeight"))(ch => gen.writeNumberField("cancelHeight", ch.toInt, numbersAsString))
      l.cancelTransactionId.fold(gen.writeNullField("cancelTransactionId"))(cti => gen.writeStringField("cancelTransactionId", cti.toString))
      gen.writeEndObject()
    }

  def txMetaJsonSerializer(address: Address, numbersAsString: Boolean): JsonSerializer[TxMetaEnriched] =
    (txMeta: TxMetaEnriched, gen: JsonGenerator, serializers: SerializerProvider) => {
      txMeta.meta match {
        case meta @ TransactionMeta.Default(_, mtt: MassTransferTransaction, _, _) if mtt.sender.toAddress != address =>
          /** Produces compact representation for large transactions by stripping unnecessary data. Currently implemented for MassTransfer transaction
            * only.
            */
          jsObjectSerializer(numbersAsString).serialize(
            mtt.compactJson(address) ++ transactionMetaJson(meta),
            gen,
            serializers
          )
        case other =>
          jsObjectSerializer(numbersAsString).serialize(other.transaction.json() ++ transactionMetaJson(other), gen, serializers)
      }
    }

  def jsObjectSerializer(numbersAsString: Boolean): JsonSerializer[JsObject] = new JsonSerializer[JsObject] {
    override def serialize(jsObj: JsObject, gen: JsonGenerator, serializers: SerializerProvider): Unit = {
      gen.writeStartObject()
      jsObj.fields.foreach { case (key, value) => encodeField(key, value, gen, serializers) }
      gen.writeEndObject()
    }

    private def encodeField(key: String, jsValue: JsValue, gen: JsonGenerator, serializers: SerializerProvider): Unit = {
      jsValue match {
        case n: JsNumber =>
          gen.writeNumberField(key, n.value, numbersAsString)
        case b: JsBoolean =>
          gen.writeBooleanField(key, b.value)
        case s: JsString =>
          gen.writeStringField(key, s.value)
        case a: JsArray =>
          gen.writeArrayField(key)(out => a.value.foreach(encodeArrayElem(_, out, serializers)))
        case o: JsObject =>
          gen.writeValueField(key)(serialize(o, _, serializers))
        case _ =>
          gen.writeNullField(key)
      }
    }

    private def encodeArrayElem(jsValue: JsValue, gen: JsonGenerator, serializers: SerializerProvider): Unit = {
      jsValue match {
        case n: JsNumber =>
          gen.writeNumber(n.value.bigDecimal)
        case b: JsBoolean =>
          gen.writeBoolean(b.value)
        case s: JsString =>
          gen.writeString(s.value)
        case a: JsArray =>
          gen.writeStartArray()
          a.value.foreach(encodeArrayElem(_, gen, serializers))
          gen.writeEndArray()
        case o: JsObject =>
          serialize(o, gen, serializers)
        case _ =>
          gen.writeNull()
      }
    }
  }

  def transactionMetaJson(meta: TransactionMeta): JsObject = {
    val specificInfo = meta.transaction match {
      case lease: LeaseTransaction =>
        import tech.hearth.api.http.TransactionsApiRoute.LeaseStatus.*
        Json.obj("status" -> (if (blockchain.leaseDetails(lease.id()).exists(_.isActive)) active else canceled))

      case leaseCancel: LeaseCancelTransaction =>
        Json.obj("lease" -> leaseIdToLeaseRef(leaseCancel.leaseId))

      case _ => JsObject.empty
    }

    val stateChanges = JsObject.empty

    Seq(
      TransactionJsonSerializer.height(meta.height),
      metaJson(TxMeta(meta.height, meta.status, meta.spentComplexity)),
      stateChanges,
      specificInfo
    ).reduce(_ ++ _)
  }

  def transactionWithMetaJson(meta: TransactionMeta): JsObject =
    meta.transaction.json() ++ transactionMetaJson(meta)

  def unconfirmedTxExtendedJson(tx: Transaction): JsObject = tx match {
    case leaseCancel: LeaseCancelTransaction =>
      leaseCancel.json() ++ Json.obj("lease" -> leaseIdToLeaseRef(leaseCancel.leaseId))

    case t => t.json()
  }

  def metaJson(m: TxMeta): JsObject =
    TransactionJsonSerializer.applicationStatus(isBlockV5 = true, m.status) ++ Json.obj("spentComplexity" -> m.spentComplexity)

  // Extended lease format. Overrides default
  private def leaseIdToLeaseRef(leaseId: ByteStr): LeaseRef = {
    val detailsOpt   = blockchain.leaseDetails(leaseId)
    val txMetaOpt    = detailsOpt.flatMap(d => blockchain.transactionMeta(d.sourceId.byteStr))
    val recipientOpt = detailsOpt.map(_.recipientAddress)

    val statusOpt = detailsOpt.map(_.status)
    val status    = LeaseStatus(statusOpt.contains(LeaseDetails.Status.Active))
    val statusDataOpt = statusOpt.map {
      case LeaseDetails.Status.Active                  => (None, None)
      case LeaseDetails.Status.Cancelled(height, txId) => (Some(height), txId)
      case LeaseDetails.Status.Expired(height)         => (Some(height), None)
    }

    LeaseRef(
      leaseId,
      detailsOpt.map(_.sourceId),
      detailsOpt.map(_.sender.toAddress),
      recipientOpt,
      detailsOpt.map(_.amount.value),
      txMetaOpt.map(_.height),
      status,
      statusDataOpt.flatMap(_._1),
      statusDataOpt.flatMap(_._2)
    )
  }

}

object TransactionJsonSerializer {
  def applicationStatus(isBlockV5: Boolean, status: TxMeta.Status): JsObject =
    if (isBlockV5)
      Json.obj("applicationStatus" -> applicationStatusFromTxStatus(status))
    else
      JsObject.empty

  def applicationStatusFromTxStatus(status: TxMeta.Status): String =
    status match {
      case TxMeta.Status.Succeeded => ApplicationStatus.Succeeded
      case TxMeta.Status.Failed    => ApplicationStatus.ScriptExecutionFailed
      case TxMeta.Status.Elided    => ApplicationStatus.Elided
    }

  def height(height: Height): JsObject =
    Json.obj("height" -> height.toInt)

  final case class LeaseRef(
      id: ByteStr,
      originTransactionId: Option[TransactionId],
      sender: Option[Address],
      recipient: Option[Address],
      amount: Option[Long],
      height: Option[Height],
      status: LeaseStatus,
      cancelHeight: Option[Height],
      cancelTransactionId: Option[TransactionId]
  )

  object LeaseRef {
    import tech.hearth.account.Address.jsonFormat
    import tech.hearth.utils.byteStrFormat
    implicit val config: Aux[Json.MacroOptions] = JsonConfiguration(optionHandlers = OptionHandlers.WritesNull)
    implicit val jsonWrites: OWrites[LeaseRef]  = Json.writes[LeaseRef]
  }
}
