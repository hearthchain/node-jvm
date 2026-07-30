package com.wavesplatform.it.util

import com.google.protobuf.ByteString
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.*
import com.wavesplatform.protobuf.Amount
import com.wavesplatform.protobuf.transaction.{MassTransferTransactionData, PBTransaction}
import com.wavesplatform.protobuf.utils.PBUtils
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
