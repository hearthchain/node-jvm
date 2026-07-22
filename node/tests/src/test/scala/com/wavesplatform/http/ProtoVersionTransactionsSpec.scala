package com.wavesplatform.http

import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.api.http.{RouteTimeout, TransactionsApiRoute}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base64
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions}
import com.wavesplatform.protobuf.utils.PBUtils
import com.wavesplatform.settings.Constants
import com.wavesplatform.test.SharedDomain
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.exchange.{AssetPair, ExchangeTransaction, Order}
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.wavesplatform.utils.SharedSchedulerMixin
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

  private val MinFee: Long            = (0.001 * Constants.UnitsInWave).toLong
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
      val assetPair = AssetPair(asset, Waves)

      val buyOrder =
        TxHelpers
          .buy(Order.V3, buyer, PublicKey(account.publicKey), assetPair, Order.MaxAmount / 2, 100L, now, now + Order.MaxLiveTime / 2, MinFee * 3)
          .explicitGet()
      val sellOrder =
        TxHelpers
          .sell(Order.V3, seller, PublicKey(account.publicKey), assetPair, Order.MaxAmount / 2, 100L, now, now + Order.MaxLiveTime / 2, MinFee * 3)
          .explicitGet()

      val exchangeTx =
        TxHelpers.exchange(buyOrder, sellOrder, account, 100, 100, MinFee * 3, MinFee * 3, MinFee * 3, now, TxVersion.V3)
      val base64Str = Base64.encode(PBUtils.encodeDeterministic(PBTransactions.protobuf(exchangeTx)))

      Post(routePath("/broadcast"), exchangeTx.json()) ~> ApiKeyHeader ~> route ~> check {
        responseAs[JsObject] shouldBe exchangeTx.json()
      }

      decode(base64Str) shouldBe exchangeTx

      (exchangeTx.json() \ "chainId").asOpt[Byte].value shouldBe exchangeTx.chainId
    }

    "LeaseTransaction/LeaseCancelTransaction" in {
      val recipient = TxHelpers.secondAddress
      val leaseTxUnsigned = LeaseTransaction
        .create(1, AddressScheme.current.chainId, PublicKey(account.publicKey), recipient, 100L, MinFee, now, Proofs.empty)
        .explicitGet()

      val (leaseProofs, leaseTxJson) = Post(routePath("/sign"), leaseTxUnsigned.json()) ~> ApiKeyHeader ~> route ~> check {
        checkProofs(response)
      }

      val leaseTx = leaseTxUnsigned.copy(proofs = leaseProofs)

      Post(routePath("/broadcast"), leaseTx.json()) ~> ApiKeyHeader ~> route ~> check {
        responseAs[JsObject] shouldBe leaseTxJson
      }

      val leaseCancelTxUnsigned =
        LeaseCancelTransaction.create(TxVersion.V1, PublicKey(account.publicKey), leaseTx.id(), MinFee, now, Proofs.empty).explicitGet()

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

      (leaseTx.json() \ "chainId").asOpt[Byte].value shouldBe leaseTx.chainId
      (leaseCancelTx.json() \ "chainId").asOpt[Byte].value shouldBe leaseCancelTx.chainId
    }

    "TransferTransaction" in {
      val recipient = TxHelpers.secondAddress
      val transferTxUnsigned =
        TransferTransaction
          .create(TxVersion.V1, PublicKey(account.publicKey), recipient, asset, 100L, Waves, MinFee, ByteStr(attachment), now, Proofs.empty)
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

      (transferTx.json() \ "chainId").asOpt[Byte].value shouldBe transferTx.chainId

    }

    "MassTransferTransaction" in {
      val transfers = (1 to 10).map { i =>
        ParsedTransfer(TxHelpers.signer(i).toAddress, TxNonNegativeAmount.unsafeFrom(100))
      }
      val attachment = Array.fill(TransferTransaction.MaxAttachmentSize)(1: Byte)

      val massTransferTxUnsigned =
        MassTransferTransaction
          .create(TxVersion.V1, PublicKey(account.publicKey), Waves, transfers, MassTransferTxFee, now, ByteStr(attachment), Proofs.empty)
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

      (massTransferTx.json() \ "chainId").asOpt[Byte].value shouldBe massTransferTx.chainId
    }

    def checkProofs(response: HttpResponse): (Proofs, JsObject) = {
      response.status shouldBe StatusCodes.OK

      (responseAs[JsObject] \ "version").as[Byte] shouldBe 1.toByte
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
