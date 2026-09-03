package tech.hearth.transaction

import tech.hearth.account.NetworkId
import tech.hearth.common.state.ByteStr
import tech.hearth.transaction.serialization.impl.PBTransactionSerializer
import monix.eval.Coeval
import play.api.libs.json.JsObject

trait TransactionBase {
  def assetFee: (Asset, Long)
  def timestamp: Long
  def networkId: NetworkId
  def id: Coeval[ByteStr]
  val tpe: TransactionType
}

object TransactionBase {
  implicit class TBExt(val t: TransactionBase) extends AnyVal {
    def fee: Long         = t.assetFee._2
    def feeAssetId: Asset = t.assetFee._1
  }
}

abstract class Transaction(val tpe: TransactionType) extends TransactionBase {
  type T <: Transaction

  def bytesSize: Int = bytes().length

  val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(PBTransactionSerializer.bodyBytes(this))
  val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(PBTransactionSerializer.bytes(this))

  val json: Coeval[JsObject]

  override def toString: String = json().toString

  override def equals(other: Any): Boolean = other match {
    case tx: Transaction => id() == tx.id()
    case _               => false
  }

  override def hashCode(): Int = id().hashCode()
}
