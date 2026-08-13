package tech.hearth.transaction.validation.impl

import tech.hearth.crypto.dcap.DcapQuote
import tech.hearth.transaction.StartBoostTransaction
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.validation.*

object StartBoostTxValidator extends TxValidator[StartBoostTransaction] {
  override def validate(tx: StartBoostTransaction): ValidatedV[StartBoostTransaction] = {
    import tx.*
    V.seq(tx)(
      // Structural only, no blockchain access yet (see StartBoostTransactionDiff for the rest): a malformed quote
      // or an outright SGX one is rejected as fast as possible, before it can occupy mempool/block space.
      V.cond(
        DcapQuote.parse(tdxQuote.arr).exists(_.header.teeType == DcapQuote.TdxTeeType),
        GenericError("StartBoost requires a well-formed TDX quote; SGX quotes are not accepted")
      )
    )
  }
}
