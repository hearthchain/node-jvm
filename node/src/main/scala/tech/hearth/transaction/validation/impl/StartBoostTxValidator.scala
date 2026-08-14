package tech.hearth.transaction.validation.impl

import tech.hearth.crypto.dcap.DcapQuote
import tech.hearth.crypto.dcap.IntelPki.MaxCollateralFieldSize
import tech.hearth.transaction.StartBoostTransaction
import tech.hearth.transaction.TxValidationError.{GenericError, TooBigInBytes}
import tech.hearth.transaction.validation.*

object StartBoostTxValidator extends TxValidator[StartBoostTransaction] {
  override def validate(tx: StartBoostTransaction): ValidatedV[StartBoostTransaction] = {
    import tx.*
    val withinSizeLimit = tdxQuote.arr.length <= MaxCollateralFieldSize
    V.seq(tx)(
      // Bounds expensive verification work in StartBoostTransactionDiff regardless of submission path (REST/gRPC/
      // P2P) - the REST JSON layer has its own, separate decode limit (api.http.requests.LargeBlobDecodeLimit),
      // sized the same.
      V.cond(
        withinSizeLimit,
        TooBigInBytes(s"tdxQuote length ${tdxQuote.arr.length} bytes exceeds maximum of $MaxCollateralFieldSize bytes.")
      ),
      // Structural only, no blockchain access yet (see StartBoostTransactionDiff for the rest): a malformed quote
      // or an outright SGX one is rejected as fast as possible, before it can occupy mempool/block space. `V.cond`'s
      // arguments are eagerly evaluated (V.seq is an ordinary vararg call, not lazy), so the size check above can't
      // short-circuit this on its own - `!withinSizeLimit ||` here does that explicitly (skipping the parse itself,
      // and skipping this check's own error, for an already-oversized quote the size check above already rejects).
      V.cond(
        !withinSizeLimit || DcapQuote.parse(tdxQuote.arr).exists(_.header.teeType == DcapQuote.TdxTeeType),
        GenericError("StartBoost requires a well-formed TDX quote; SGX quotes are not accepted")
      )
    )
  }
}
