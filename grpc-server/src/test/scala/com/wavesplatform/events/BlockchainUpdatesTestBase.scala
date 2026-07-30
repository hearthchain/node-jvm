package com.wavesplatform.events

import com.google.protobuf.ByteString
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.events.StateUpdate.LeaseUpdate.LeaseStatus
import com.wavesplatform.events.fixtures.WavesTxChecks.*
import com.wavesplatform.events.protobuf.BlockchainUpdated.Append
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.settings.{GenesisAssetSettings, WavesSettings}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.exchange.*
import com.wavesplatform.transaction.assets.exchange.Order.Version
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.wavesplatform.transaction.{Asset, TxHelpers}
import org.scalactic.source.Position
import org.scalatest.Assertions
import tech.hearth.crypto.SigningKey
import org.scalatest.concurrent.ScalaFutures

class BlockchainUpdatesTestBase extends FreeSpec with WithBUDomain with ScalaFutures {
  import BlockchainUpdatesTestBase.*
  val currentSettings: WavesSettings         = DomainPresets.RideV6
  val amount: Long                           = 9000000
  val additionalAmount: Long                 = 5000000
  val customFee: Long                        = 5234567L
  val firstTxParticipant: SigningKey         = TxHelpers.signer(2)
  val firstTxParticipantAddress: Address     = firstTxParticipant.toAddress
  val firstTxParticipantBalanceBefore: Long  = 20.waves
  val secondTxParticipant: SigningKey        = TxHelpers.signer(3)
  val secondTxParticipantAddress: Address    = secondTxParticipant.toAddress
  val secondTxParticipantPKHash: Array[Byte] = secondTxParticipantAddress.toBytes()
  val secondTxParticipantBalanceBefore: Long = 20.waves
  val recipients: Seq[ParsedTransfer]        = TxHelpers.accountSeqGenerator(100, additionalAmount)

  val firstTokenAsset: IssuedAsset  = IssuedAsset(ByteStr.fill(32)(1))
  val firstTokenQuantity: Long      = 2000000000L
  val secondTokenAsset: IssuedAsset = IssuedAsset(ByteStr.fill(32)(2))
  val secondTokenQuantity: Long     = 6000000000L

  val genesisAssets: Seq[GenesisAssetSettings] = Seq(
    GenesisAssetSettings(firstTokenAsset.id, Base58.encode(firstTxParticipant.publicKey()), "firstToken", 2, firstTokenQuantity),
    GenesisAssetSettings(secondTokenAsset.id, Base58.encode(secondTxParticipant.publicKey()), "secondToken", 6, secondTokenQuantity)
  )

  val tokenBalances: Seq[AddrWithBalance] = Seq(
    AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore, Map(firstTokenAsset -> firstTokenQuantity)),
    AddrWithBalance(secondTxParticipantAddress, secondTxParticipantBalanceBefore, Map(secondTokenAsset -> secondTokenQuantity))
  )

  protected def createOrder(orderType: OrderType, orderSender: SigningKey, orderVersion: Version): Order = {
    TxHelpers.order(
      orderType,
      secondTokenAsset,
      firstTokenAsset,
      Waves,
      amount = 50000L,
      price = 400000000L,
      fee = customFee,
      sender = orderSender,
      matcher = firstTxParticipant,
      version = orderVersion
    )
  }

  protected def checkTransferTx(append: Append, transferTx: TransferTransaction)(implicit pos: Position): Unit = {
    val firstTxParticipantBalanceAfter  = firstTxParticipantBalanceBefore - customFee - amount
    val secondTxParticipantBalanceAfter = secondTxParticipantBalanceBefore + amount
    checkTransfer(append.transactionIds.head, append.transactionAt(0), transferTx, secondTxParticipantAddress.toBytes())
    checkBalances(
      filterOutMinerBalanceUpdates(append),
      Map(
        (firstTxParticipantAddress, Waves)  -> (firstTxParticipantBalanceBefore, firstTxParticipantBalanceAfter),
        (secondTxParticipantAddress, Waves) -> (secondTxParticipantBalanceBefore, secondTxParticipantBalanceAfter)
      )
    )
  }

  protected def checkExchangeTx(append: Append, exchangeTx: ExchangeTransaction, normalizedPrice: Long, orderAmount: Long)(implicit
      pos: Position
  ): Unit = {
    val amountAssetQuantity                      = secondTokenQuantity
    val firstTxParticipantBalanceBeforeExchange  = firstTxParticipantBalanceBefore
    val firstTxParticipantBalanceAfterExchange   = firstTxParticipantBalanceBeforeExchange - exchangeTx.fee.value + customFee
    val secondTxParticipantBalanceBeforeExchange = secondTxParticipantBalanceBefore
    val secondTxParticipantBalanceAfterExchange  = secondTxParticipantBalanceBeforeExchange - customFee

    checkExchange(append.transactionIds.head, append.transactionAt(0), exchangeTx)
    checkBalances(
      filterOutMinerBalanceUpdates(append),
      Map(
        (firstTxParticipantAddress, Waves)             -> (firstTxParticipantBalanceBeforeExchange, firstTxParticipantBalanceAfterExchange),
        (secondTxParticipantAddress, firstTokenAsset)  -> (0, normalizedPrice),
        (firstTxParticipantAddress, secondTokenAsset)  -> (0, orderAmount),
        (secondTxParticipantAddress, Waves)            -> (secondTxParticipantBalanceBeforeExchange, secondTxParticipantBalanceAfterExchange),
        (firstTxParticipantAddress, firstTokenAsset)   -> (firstTokenQuantity, firstTokenQuantity - normalizedPrice),
        (secondTxParticipantAddress, secondTokenAsset) -> (amountAssetQuantity, amountAssetQuantity - orderAmount)
      )
    )
  }

  protected def checkLeaseTx(append: Append, lease: LeaseTransaction)(implicit pos: Position): Unit = {
    val leaseId = lease.id.value().arr
    checkLease(append.transactionIds.head, append.transactionAt(0), lease, secondTxParticipantPKHash)
    checkBalances(
      filterOutMinerBalanceUpdates(append),
      Map(
        (firstTxParticipantAddress, Waves) -> (firstTxParticipantBalanceBefore, firstTxParticipantBalanceBefore - customFee)
      )
    )
    checkLeasingForAddress(
      append.transactionStateUpdates.head.leasingForAddress,
      Map(
        (firstTxParticipantAddress, 0L, amount)  -> (0L, 0L),
        (secondTxParticipantAddress, amount, 0L) -> (0L, 0L)
      )
    )
    checkIndividualLeases(
      append.transactionStateUpdates.head.individualLeases,
      Map(
        (LeaseStatus.Active, amount) -> (leaseId, lease.sender.arr, lease.recipient.toBytes(), leaseId)
      )
    )
  }

  protected def checkLeaseCancelTx(append: Append, leaseCancel: LeaseCancelTransaction, lease: LeaseTransaction)(implicit pos: Position): Unit = {
    val leaseId                           = leaseCancel.leaseId.arr
    val firstTxParticipantBalanceBeforeTx = firstTxParticipantBalanceBefore - lease.fee.value
    val firstTxParticipantBalanceAfterTx  = firstTxParticipantBalanceBeforeTx - leaseCancel.fee.value
    checkLeaseCancel(append.transactionIds.head, append.transactionAt(0), leaseCancel)
    checkBalances(
      filterOutMinerBalanceUpdates(append),
      Map(
        (firstTxParticipantAddress, Waves) -> (firstTxParticipantBalanceBeforeTx, firstTxParticipantBalanceAfterTx)
      )
    )
    checkLeasingForAddress(
      append.transactionStateUpdates.head.leasingForAddress,
      Map(
        (firstTxParticipantAddress, 0L, 0L)  -> (0L, amount),
        (secondTxParticipantAddress, 0L, 0L) -> (amount, 0L)
      )
    )
    checkIndividualLeases(
      append.transactionStateUpdates.head.individualLeases,
      Map(
        (LeaseStatus.Inactive, amount) -> (leaseId, lease.sender.arr, lease.recipient.toBytes(), leaseId)
      )
    )
  }

  protected def checkForMassTransferTx(append: Append, massTransfer: MassTransferTransaction): Unit = {
    val firstTxParticipantBalanceBeforeTx     = firstTxParticipantBalanceBefore
    val firstTxParticipantBalanceAfterTx      = firstTxParticipantBalanceBeforeTx - massTransfer.fee.value
    val firstTxParticipantAssetBalanceAfterTx = firstTokenQuantity - additionalAmount * recipients.size

    val balancesMap = Map(
      (firstTxParticipantAddress, Waves)                -> (firstTxParticipantBalanceBeforeTx, firstTxParticipantBalanceAfterTx),
      (firstTxParticipantAddress, massTransfer.assetId) -> (firstTokenQuantity, firstTxParticipantAssetBalanceAfterTx)
    ) ++ recipients.map(r => (r.address, massTransfer.assetId) -> (0L, additionalAmount)).toMap
    checkMassTransfer(
      append.transactionIds.head,
      append.transactionAt(0),
      massTransfer,
      recipients.map(r => r.address.toBytes())
    )
    checkMassTransferBalances(append.transactionStateUpdates.head.balances, balancesMap)
  }
}

object BlockchainUpdatesTestBase extends Assertions {
  def filterOutMinerBalanceUpdates(append: Append): Seq[protobuf.StateUpdate.BalanceUpdate] = {
    val generatorPK = append.body match {
      case Append.Body.Empty             => fail("Append event is empty")
      case Append.Body.Block(value)      => value.getBlock.getHeader.generator
      case Append.Body.MicroBlock(value) => value.getMicroBlock.getMicroBlock.senderPublicKey
    }
    val generatorAddress = ByteString.copyFrom(PublicKey(generatorPK.toByteArray).toAddress.toBytes())

    append.transactionStateUpdates.head.balances.filterNot(_.address == generatorAddress)
  }
}
