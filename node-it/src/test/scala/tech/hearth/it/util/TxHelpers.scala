package tech.hearth.it.util

import com.google.protobuf.ByteString
import tech.hearth.account.AddressScheme
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.*
import tech.hearth.protobuf.transaction.{TransferTransactionData, PBTransaction}
import tech.hearth.protobuf.utils.PBUtils
import tech.hearth.crypto.SigningKey

object TxHelpers {
  def massTransferBodyBytes(
      sender: SigningKey,
      assetId: Option[String],
      transfers: Seq[TransferTransactionData.Transfer],
      attachment: ByteString,
      fee: Long,
      timestamp: Long
  ): ByteStr = {
    val unsigned = PBTransaction(
      AddressScheme.current.chainId,
      ByteString.copyFrom(sender.publicKey()),
      fee,
      timestamp,
      PBTransaction.Data.Transfer(
        TransferTransactionData.of(
          if (assetId.isDefined) ByteString.copyFrom(Base16.decode(assetId.get)) else ByteString.EMPTY,
          transfers,
          attachment,
          ByteString.EMPTY
        )
      )
    )

    val bodyBytes = PBUtils.encodeDeterministic(unsigned)
    ByteStr(sender.sign(bodyBytes))
  }

}
