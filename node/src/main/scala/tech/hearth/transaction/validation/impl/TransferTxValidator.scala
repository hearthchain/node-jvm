package tech.hearth.transaction.validation.impl

import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.transfer.TransferTransaction.MaxTransferCount
import tech.hearth.transaction.validation.{TxValidator, ValidatedV}

object TransferTxValidator extends TxValidator[TransferTransaction] {
  override def validate(tx: TransferTransaction): ValidatedV[TransferTransaction] = {
    import tx.*
    V.seq(tx)(
      V.noOverflow((fee.value +: transfers.map(_.amount.value))*),
      V.cond(transfers.length <= MaxTransferCount, GenericError(s"Number of transfers ${transfers.length} is greater than $MaxTransferCount")),
      V.transferAttachment(attachment),
      V.chainIds(chainId)
    )
  }
}
