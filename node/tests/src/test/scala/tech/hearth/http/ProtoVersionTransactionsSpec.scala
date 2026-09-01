package tech.hearth.http

import tech.hearth.account.{NetworkId, PublicKey}
import tech.hearth.api.http.{RouteTimeout, TransactionsApiRoute}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base64
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.protobuf.transaction.{PBSignedTransaction, PBTransactions}
import tech.hearth.protobuf.utils.PBUtils
import tech.hearth.settings.Constants
import tech.hearth.test.SharedDomain
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.assets.exchange.{AssetPair, ExchangeTransaction, Order}
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.transfer.TransferTransaction.ParsedTransfer
import tech.hearth.utils.SharedSchedulerMixin
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.OptionValues
import play.api.libs.json.*
import tech.hearth.crypto.SigningKey

import scala.concurrent.duration.*

class ProtoVersionTransactionsSpec
    extends RouteSpec("/transactions")
    with RestAPISettingsHelper
    with SharedDomain
    with OptionValues
    with SharedSchedulerMixin {

  private val MinFee: Long            = (0.001 * Constants.UnitsInHearth).toLong
  private val MassTransferTxFee: Long = 15000000

  private val now: Long           = ntpNow
  private val account: SigningKey = domain.wallet.generateNewAccount().get
  private val asset               = IssuedAsset(ByteStr(new Array[Byte](32)))
  private val attachment          = Array.fill(TransferTransaction.MaxAttachmentSize)(1: Byte)

  private val route: Route =
    Route.seal(
      TransactionsApiRoute(
        restAPISettings,
        domain.transactionsApi,
        domain.wallet,
        domain.generatorKeys,
        domain.blockchain,
        () => domain.blockchain,
        () => domain.utxPool.size,
        DummyTransactionPublisher.accepting,
        domain.testTime,
        new RouteTimeout(60.seconds)(using sharedScheduler)
      ).route
    )

  "Proto transactions should be able to broadcast " - {
    "ExchangeTransaction" in {
      val buyer     = TxHelpers.signer(0)
      val seller    = TxHelpers.signer(1)
      val assetPair = AssetPair(asset, Hearth)

      val buyOrder =
        TxHelpers
          .buy(Order.V3, buyer, PublicKey(account.publicKey), assetPair, Order.MaxAmount / 2, 100L, now, now + Order.MaxLiveTime / 2, MinFee * 3)
          .explicitGet()
      val sellOrder =
        TxHelpers
          .sell(Order.V3, seller, PublicKey(account.publicKey), assetPair, Order.MaxAmount / 2, 100L, now, now + Order.MaxLiveTime / 2, MinFee * 3)
          .explicitGet()

      val exchangeTx =
        TxHelpers.exchange(buyOrder, sellOrder, account, 100, 100, MinFee * 3, MinFee * 3, MinFee * 3, now)
      val base64Str = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(exchangeTx)))

      Post(routePath("/broadcast"), exchangeTx.json()) ~> ApiKeyHeader ~> route ~> check {
        responseAs[JsObject] shouldBe exchangeTx.json()
      }

      decode(base64Str) shouldBe exchangeTx

      (exchangeTx.json() \ "networkId").asOpt[String].value shouldBe exchangeTx.networkId.value
    }

    "LeaseTransaction/LeaseCancelTransaction" in {
      val recipient = TxHelpers.secondAddress
      val leaseTxUnsigned = LeaseTransaction
        .create(NetworkId.current, PublicKey(account.publicKey), recipient, 100L, MinFee, now, Proofs.empty)
        .explicitGet()

      val (leaseProofs, leaseTxJson) = Post(routePath("/sign"), leaseTxUnsigned.json()) ~> ApiKeyHeader ~> route ~> check {
        checkProofs(response)
      }

      val leaseTx = leaseTxUnsigned.copy(proofs = leaseProofs)

      Post(routePath("/broadcast"), leaseTx.json()) ~> ApiKeyHeader ~> route ~> check {
        responseAs[JsObject] shouldBe leaseTxJson
      }

      val leaseCancelTxUnsigned =
        LeaseCancelTransaction.create(PublicKey(account.publicKey), leaseTx.id(), MinFee, now, Proofs.empty).explicitGet()

      val (leaseCancelProofs, leaseCancelTxJson) = Post(routePath("/sign"), leaseCancelTxUnsigned.json()) ~> ApiKeyHeader ~> route ~> check {
        checkProofs(response)
      }

      val leaseCancelTx = leaseCancelTxUnsigned.copy(proofs = leaseCancelProofs)

      Post(routePath("/broadcast"), leaseCancelTx.json()) ~> ApiKeyHeader ~> route ~> check {
        responseAs[JsObject] shouldBe leaseCancelTxJson
      }

      val base64LeaseStr       = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(leaseTx)))
      val base64CancelLeaseStr = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(leaseCancelTx)))

      decode(base64LeaseStr) shouldBe leaseTx
      decode(base64CancelLeaseStr) shouldBe leaseCancelTx

      (leaseTx.json() \ "networkId").asOpt[String].value shouldBe leaseTx.networkId.value
      (leaseCancelTx.json() \ "networkId").asOpt[String].value shouldBe leaseCancelTx.networkId.value
    }

    "TransferTransaction" in {
      val recipient = TxHelpers.secondAddress
      val transferTxUnsigned =
        TransferTransaction
          .create(
            PublicKey(account.publicKey),
            asset,
            Seq(ParsedTransfer(recipient, TxNonNegativeAmount.unsafeFrom(100))),
            MinFee,
            now,
            ByteStr(attachment),
            Proofs.empty
          )
          .explicitGet()

      val (proofs, transferTxJson) = Post(routePath("/sign"), transferTxUnsigned.json()) ~> ApiKeyHeader ~> route ~> check {
        checkProofs(response)
      }

      val transferTx = transferTxUnsigned.copy(proofs = proofs)
      val base64Str  = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(transferTx)))

      Post(routePath("/broadcast"), transferTx.json()) ~> ApiKeyHeader ~> route ~> check {
        responseAs[JsObject] shouldBe transferTxJson
      }

      decode(base64Str) shouldBe transferTx

      (transferTx.json() \ "networkId").asOpt[String].value shouldBe transferTx.networkId.value

    }

    "TransferTransaction (multiple recipients)" in {
      val transfers = (1 to 10).map { i =>
        ParsedTransfer(TxHelpers.signer(i).toAddress, TxNonNegativeAmount.unsafeFrom(100))
      }
      val attachment = Array.fill(TransferTransaction.MaxAttachmentSize)(1: Byte)

      val massTransferTxUnsigned =
        TransferTransaction
          .create(PublicKey(account.publicKey), Hearth, transfers, MassTransferTxFee, now, ByteStr(attachment), Proofs.empty)
          .explicitGet()

      val (proofs, massTransferTxJson) = Post(routePath("/sign"), massTransferTxUnsigned.json()) ~> ApiKeyHeader ~> route ~> check {
        checkProofs(response)
      }

      val massTransferTx = massTransferTxUnsigned.copy(proofs = proofs)
      val base64Str      = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(massTransferTx)))

      Post(routePath("/broadcast"), massTransferTx.json()) ~> ApiKeyHeader ~> route ~> check {
        responseAs[JsObject] shouldBe massTransferTxJson
      }

      decode(base64Str) shouldBe massTransferTx

      (massTransferTx.json() \ "networkId").asOpt[String].value shouldBe massTransferTx.networkId.value
    }

    def checkProofs(response: HttpResponse): (Proofs, JsObject) = {
      response.status shouldBe StatusCodes.OK

      (responseAs[JsObject] \ "senderPublicKey").asOpt[String].value should not be empty

      val json   = responseAs[JsObject]
      val proofs = (json \ "proofs").as[Proofs]
      proofs.size shouldBe 1
      (proofs, json)
    }

    def decode(base64Str: String): Transaction = {
      PBTransactions.vanilla(PBSignedTransaction.parseFrom(Base64.decode(base64Str))).explicitGet()
    }
  }
}
