package tech.hearth.it.util

import com.google.protobuf.ByteString
import tech.hearth.account.AddressScheme
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.*
import tech.hearth.protobuf.Amount
import tech.hearth.protobuf.transaction.{MassTransferTransactionData, PBTransaction}
import tech.hearth.protobuf.utils.PBUtils
import tech.hearth.crypto.SigningKey

object TxHelpers {
  def massTransferBodyBytes(
      sender: SigningKey,
      assetId: Option[String],
      transfers: Seq[MassTransferTransactionData.Transfer],
      attachment: ByteString,
      fee: Long,
      timestamp: Long
  ): ByteStr = {
    val unsigned = PBTransaction(
      AddressScheme.current.chainId,
      ByteString.copyFrom(sender.publicKey()),
      Some(Amount.of(ByteString.EMPTY, fee)),
      timestamp,
      PBTransaction.Data.MassTransfer(
        MassTransferTransactionData.of(
          if (assetId.isDefined) ByteString.copyFrom(Base58.decode(assetId.get)) else ByteString.EMPTY,
          transfers,
          attachment
        )
      )
    )

    val bodyBytes = PBUtils.encodeDeterministic(unsigned)
    ByteStr(sender.sign(bodyBytes))
  }

}
