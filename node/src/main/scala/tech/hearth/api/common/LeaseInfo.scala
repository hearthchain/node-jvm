package tech.hearth.api.common

import tech.hearth.account.Address
import tech.hearth.api.common.LeaseInfo.Status
import tech.hearth.common.state.ByteStr
import tech.hearth.state.{Height, LeaseDetails, TransactionId}

object LeaseInfo {
  type Status = Status.Value
  // noinspection TypeAnnotation
  object Status extends Enumeration {
    val Active   = Value(1)
    val Canceled = Value(0)
    val Expired  = Value(2)
  }

  def fromLeaseDetails(id: ByteStr, details: LeaseDetails): LeaseInfo =
    LeaseInfo(
      id,
      details.sourceId,
      details.sender.toAddress,
      details.recipientAddress,
      details.amount.value,
      details.height,
      details.status match {
        case LeaseDetails.Status.Active          => LeaseInfo.Status.Active
        case LeaseDetails.Status.Cancelled(_, _) => LeaseInfo.Status.Canceled
        case LeaseDetails.Status.Expired(_)      => LeaseInfo.Status.Expired
      },
      details.status.cancelHeight,
      details.status.cancelTransactionId
    )
}

case class LeaseInfo(
    id: ByteStr,
    originTransactionId: TransactionId,
    sender: Address,
    recipient: Address,
    amount: Long,
    height: Height,
    status: Status,
    cancelHeight: Option[Height] = None,
    cancelTransactionId: Option[TransactionId] = None
)
