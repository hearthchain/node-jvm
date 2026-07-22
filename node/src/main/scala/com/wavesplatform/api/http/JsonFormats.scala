package com.wavesplatform.api.http

import com.wavesplatform.account.Address
import com.wavesplatform.transaction.Transaction
import play.api.libs.json.*

trait JsonFormats {
  implicit lazy val wavesAddressWrites: Writes[Address] = Writes(w => JsString(w.toString))

  implicit lazy val TransactionJsonWrites: OWrites[Transaction] = OWrites(_.json())
}
