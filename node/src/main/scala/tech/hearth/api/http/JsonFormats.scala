package tech.hearth.api.http

import tech.hearth.account.Address
import tech.hearth.transaction.Transaction
import play.api.libs.json.*

trait JsonFormats {
  implicit lazy val wavesAddressWrites: Writes[Address] = Writes(w => JsString(w.toString))

  implicit lazy val TransactionJsonWrites: OWrites[Transaction] = OWrites(_.json())
}
