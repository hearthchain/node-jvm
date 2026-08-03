package tech.hearth.transaction.validation.impl

import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.transfer.MassTransferTransaction
import tech.hearth.transaction.transfer.MassTransferTransaction.MaxTransferCount
import tech.hearth.transaction.validation.{TxValidator, ValidatedV}

object MassTransferTxValidator extends TxValidator[MassTransferTransaction] {
  override def validate(tx: MassTransferTransaction): ValidatedV[MassTransferTransaction] = {
    import tx.*
    V.seq(tx)(
      V.noOverflow((fee.value +: transfers.map(_.amount.value))*),
      V.cond(transfers.length <= MaxTransferCount, GenericError(s"Number of transfers ${transfers.length} is greater than $MaxTransferCount")),
      V.transferAttachment(attachment),
      V.chainIds(chainId)
    )
  }
}
