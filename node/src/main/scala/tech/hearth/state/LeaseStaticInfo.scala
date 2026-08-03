package tech.hearth.state

import tech.hearth.account.{Address, PublicKey}
import tech.hearth.transaction.TxPositiveAmount

case class LeaseStaticInfo(
    sender: PublicKey,
    recipientAddress: Address,
    amount: TxPositiveAmount,
    sourceId: TransactionId,
    height: Height
)
