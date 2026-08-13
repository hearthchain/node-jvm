package tech.hearth.events.fixtures

import com.google.protobuf.ByteString
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.events.StateUpdate.LeaseUpdate.LeaseStatus
import tech.hearth.events.protobuf.StateUpdate.{BalanceUpdate, LeaseUpdate, LeasingUpdate}
import tech.hearth.protobuf.order.Order
import tech.hearth.protobuf.transaction.*
import tech.hearth.protobuf.transaction.Transaction.Data
import tech.hearth.transaction.assets.exchange
import tech.hearth.transaction.assets.exchange.ExchangeTransaction
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.{Asset, TransactionBase}
import org.scalactic.source.Position
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.matchers.{MatchResult, Matcher}

object HearthTxChecks extends Matchers with OptionValues {
  import PBAmounts.*

  def checkBaseTx(actualId: ByteString, actual: SignedTransaction, expected: TransactionBase)(implicit pos: Position): Unit = {
    ByteStr(actualId.toByteArray) shouldEqual expected.id()
    actual.transaction match {
      case Some(value) =>
        value.timestamp shouldEqual expected.timestamp
        value.fee shouldEqual expected.assetFee._2
      case _ =>
    }
  }

  def checkTransfer(actualId: ByteString, actual: SignedTransaction, expected: TransferTransaction, pkHashes: Seq[Array[Byte]])(implicit
      pos: Position
  ): Unit = {
    checkBaseTx(actualId, actual, expected)
    actual.transaction.value.data match {
      case Data.Transfer(value) =>
        toVanillaAssetId(value.feeAssetId) shouldEqual expected.assetFee._1
        toVanillaAssetId(value.assetId) shouldEqual expected.assetId
        value.transfers.foreach(actualTransfer =>
          expected.transfers.foreach(expectedTransfer => actualTransfer.amount shouldBe expectedTransfer.amount.value)
        )
        value.transfers.zip(pkHashes).foreach(item => item._1.getRecipient.publicKeyHash.toByteArray shouldBe item._2)
      case _ => fail("not a Transfer transaction")
    }
  }

  def checkExchange(actualId: ByteString, actual: SignedTransaction, expected: ExchangeTransaction)(implicit pos: Position): Unit = {
    checkBaseTx(actualId, actual, expected)
    actual.transaction.value.data match {
      case Data.Exchange(value) =>
        value.amount shouldEqual expected.amount.value
        value.price shouldEqual expected.price.value
        value.buyMatcherFee shouldEqual expected.buyMatcherFee
        value.sellMatcherFee shouldEqual expected.sellMatcherFee
        checkOrders(value.orders.head, expected.order1)
        checkOrders(value.orders.last, expected.order2)
      case _ => fail("not a Exchange transaction")
    }
  }

  def checkLease(actualId: ByteString, actual: SignedTransaction, expected: LeaseTransaction, publicKeyHash: Array[Byte])(implicit
      pos: Position
  ): Unit = {
    checkBaseTx(actualId, actual, expected)
    actual.transaction.value.data match {
      case Data.Lease(value) =>
        value.recipient.get.publicKeyHash.toByteArray shouldBe publicKeyHash
        value.amount shouldBe expected.amount.value
      case _ => fail("not a Lease transaction")
    }
  }

  def checkLeaseCancel(actualId: ByteString, actual: SignedTransaction, expected: LeaseCancelTransaction)(implicit
      pos: Position
  ): Unit = {
    checkBaseTx(actualId, actual, expected)
    actual.transaction.value.data match {
      case Data.LeaseCancel(value) =>
        value.leaseId.toByteArray shouldBe expected.leaseId.arr
      case _ => fail("not a LeaseCancel transaction")
    }
  }

  class BalanceUpdateMatcher(expected: Map[(Address, Asset), (Long, Long)]) extends Matcher[Seq[BalanceUpdate]] {
    override def apply(actualBalances: Seq[BalanceUpdate]): MatchResult = {
      val mismatchedBalancesB = Seq.newBuilder[(Address, Asset, (Long, Long), (Long, Long))]
      val unexpectedBalancesB = Seq.newBuilder[BalanceUpdate]
      val unmetExpectations = actualBalances.foldLeft(expected) { case (prevExpected, b) =>
        val addr  = Address.fromBytes(b.address.toByteArray).explicitGet()
        val asset = toVanillaAssetId(b.amountAfter.value.assetId)
        prevExpected.get(addr -> asset) match {
          case Some((expectedBefore, expectedAfter)) =>
            if (expectedBefore != b.amountBefore || expectedAfter != b.amountAfter.value.amount) {
              mismatchedBalancesB += ((addr, asset, (expectedBefore, expectedAfter), (b.amountBefore, b.amountAfter.value.amount)))
            }
            prevExpected.removed(addr -> asset)
          case None =>
            unexpectedBalancesB += b
            prevExpected
        }
      }

      val mismatched = mismatchedBalancesB.result()
      val unexpected = unexpectedBalancesB.result()

      val errorMessage = new StringBuilder("Actual balances did not match expectations.")
      if (unmetExpectations.nonEmpty) {
        errorMessage.append("\nThe following expected balance updates were not found:")
        unmetExpectations.foreach { case ((address, asset), (before, after)) =>
          errorMessage.append(s"\n$address $asset: $before->$after")
        }
      }
      if (unexpected.nonEmpty) {
        errorMessage.append("\nThe following balance updates were not expected:")
        unexpected.foreach { b =>
          errorMessage.append(
            s"\n${Address.fromBytes(b.address.toByteArray).explicitGet()} ${toVanillaAssetId(b.amountAfter.value.assetId)}: ${b.amountBefore} -> ${b.amountAfter.value.amount}"
          )
        }
      }
      if (mismatched.nonEmpty) {
        errorMessage.append("The following balances did not match:")
        mismatched.foreach { case (address, asset, expected, actual) =>
          errorMessage.append(
            s"\n$address $asset: expected $expected != actual $actual"
          )
        }
      }

      MatchResult(
        unmetExpectations.isEmpty && mismatched.isEmpty && unexpected.isEmpty,
        errorMessage.toString(),
        "Actual balances did not differ from the expected balances"
      )
    }
  }

  def matchBalances(expected: Map[(Address, Asset), (Long, Long)]): Matcher[Seq[BalanceUpdate]] = new BalanceUpdateMatcher(expected)

  def checkBalances(actual: Seq[BalanceUpdate], expected: Map[(Address, Asset), (Long, Long)])(implicit pos: Position): Unit =
    actual should matchBalances(expected)

  def checkMassTransferBalances(actual: Seq[BalanceUpdate], expected: Map[(Address, Asset), (Long, Long)]): Unit = {
    val actualBalances = actual.map { bu =>
      (
        (Address.fromBytes(bu.address.toByteArray).explicitGet(), toVanillaAssetId(bu.amountAfter.value.assetId)),
        (bu.amountBefore, bu.amountAfter.value.amount)
      )
    }.toMap
    val matchingKeys = actualBalances.keySet.intersect(expected.keySet)
    matchingKeys.foreach { key =>
      actualBalances(key) shouldBe expected(key)
    }
  }

  def checkLeasingForAddress(actual: Seq[LeasingUpdate], expected: Map[(Address, Long, Long), (Long, Long)]): Unit = {
    actual.map { bu =>
      (
        (Address.fromBytes(bu.address.toByteArray).explicitGet(), bu.inAfter, bu.outAfter),
        (bu.inBefore, bu.outBefore)
      )
    }.toMap shouldBe expected
  }

  def checkIndividualLeases(
      actual: Seq[LeaseUpdate],
      expected: Map[(LeaseStatus, Long), (Array[Byte], Array[Byte], Array[Byte], Array[Byte])]
  ): Unit = {
    actual.size shouldBe expected.size

    actual.zip(expected).foreach { case (lease, ((status, amount), (leaseId, sender, recipient, originTransactionId))) =>
      lease.statusAfter.toString() shouldBe status.toString.toUpperCase
      lease.amount shouldBe amount
      lease.leaseId.toByteArray shouldBe leaseId
      lease.sender.toByteArray shouldBe sender
      lease.recipient.toByteArray shouldBe recipient
      lease.originTransactionId.toByteArray shouldBe originTransactionId
    }
  }

  private def checkOrders(order: Order, expected: exchange.Order): Unit = {
    order.senderPublicKey.toByteArray shouldBe expected.sender.arr
    order.matcherPublicKey.toByteArray shouldBe expected.matcherPublicKey.arr
    order.assetPair.get.amountAssetId.toByteArray shouldBe expected.assetPair.amountAsset.compatId.get.arr
    order.assetPair.get.priceAssetId.toByteArray shouldBe expected.assetPair.priceAsset.compatId.get.arr
    order.orderSide.toString() `equalsIgnoreCase` expected.orderType.toString
    order.amount shouldBe expected.amount.value
    order.price shouldBe expected.price.value
    order.timestamp shouldBe expected.timestamp
    order.expiration shouldBe expected.expiration
    order.matcherFee.get.amount shouldBe expected.matcherFee.value
    toVanillaAssetId(order.matcherFee.get.assetId) shouldBe expected.matcherFeeAssetId
    order.version shouldBe expected.version
  }
}
