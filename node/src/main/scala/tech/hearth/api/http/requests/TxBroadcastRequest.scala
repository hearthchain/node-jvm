package tech.hearth.api.http.requests

import tech.hearth.lang.ValidationError
import tech.hearth.transaction.{ProvenTransaction, Transaction}

trait TxBroadcastRequest[+T <: Transaction & ProvenTransaction] {
  def toTx: Either[ValidationError, T]
}
