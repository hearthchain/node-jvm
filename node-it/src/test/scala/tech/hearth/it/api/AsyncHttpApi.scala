package tech.hearth.it.api

import tech.hearth.account.{Address, AddressScheme, PublicKey}
import tech.hearth.api.http.DebugMessage.*
import tech.hearth.api.http.RewardApiRoute.{RewardStatus, RewardVotes}
import tech.hearth.api.http.requests.TransferRequest
import tech.hearth.api.http.{ConnectReq, DebugMessage, RollbackParams, `X-Api-Key`}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.features.api.{ActivationStatus, FinalityStatus, activationStatusFormat}
import tech.hearth.it.Node
import tech.hearth.it.util.*
import tech.hearth.it.util.GlobalTimer.instance as timer
import tech.hearth.state.{AssetDistribution, AssetDistributionPage, Height, LeaseBalance, Portfolio}
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.assets.exchange.{Order, ExchangeTransaction as ExchangeTx}
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.transfer.MassTransferTransaction.{ParsedTransfer, Transfer}
import tech.hearth.transaction.{
  Asset,
  Proofs,
  TransactionType,
  TransactionValidationOps,
  TxExchangeAmount,
  TxExchangePrice,
  TxMatcherFee,
  TxNonNegativeAmount,
  TxPositiveAmount
}
import monix.execution.atomic.AtomicInt
import tech.hearth.crypto.SigningKey
import org.asynchttpclient.*
import org.asynchttpclient.Dsl.{delete as _delete, get as _get, post as _post, put as _put}
import org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.OK_200
import org.scalactic.source.Position
import org.scalatest.{Assertions, matchers}
import play.api.libs.json.*
import play.api.libs.json.Json.{stringify, toJson}

import java.io.IOException
import java.net.{InetSocketAddress, URLEncoder}
import java.time.Duration as JDuration
import java.util.NoSuchElementException
import java.util.concurrent.TimeoutException
import scala.collection.immutable.VectorMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.traverse
import scala.concurrent.duration.*
import scala.jdk.FutureConverters.*
import scala.util.{Failure, Success}

object AsyncHttpApi extends Assertions {

  given Reads[RewardVotes]  = Json.reads
  given Reads[RewardStatus] = Json.reads

  private val counter = AtomicInt(1)

  // noinspection ScalaStyle
  implicit class NodeAsyncHttpApi(val n: Node) extends Assertions with matchers.should.Matchers {

    def get(
        path: String,
        amountsAsStrings: Boolean = false,
        withApiKey: Boolean = false,
        f: RequestBuilder => RequestBuilder = identity
    ): Future[Response] = {
      val defaultReqBuilder = _get(s"${n.nodeApiEndpoint}$path")

      if (amountsAsStrings || withApiKey) {
        if (amountsAsStrings && withApiKey) {
          retrying(f(defaultReqBuilder.setHeader("Accept", "application/json;large-significand-format=string").withApiKey(n.apiKey)).build())
        } else {
          if (withApiKey) {
            retrying(f(defaultReqBuilder.withApiKey(n.apiKey)).build())
          } else {
            retrying(f(defaultReqBuilder.setHeader("Accept", "application/json;large-significand-format=string")).build())
          }
        }
      } else {
        retrying(f(defaultReqBuilder).build())
      }
    }

    def getWithCustomHeader(
        path: String,
        headerName: String = "Accept",
        headerValue: String,
        withApiKey: Boolean = false,
        f: RequestBuilder => RequestBuilder = identity
    ): Future[Response] = {
      val requestBuilder = if (withApiKey) {
        _get(s"${n.nodeApiEndpoint}$path").setHeader(headerName, headerValue).withApiKey(n.apiKey)
      } else {
        _get(s"${n.nodeApiEndpoint}$path").setHeader(headerName, headerValue)
      }
      retrying(f(requestBuilder).build())
    }

    def delete(path: String, f: RequestBuilder => RequestBuilder = identity): Future[Response] =
      retrying(f(_delete(s"${n.nodeApiEndpoint}$path")).withApiKey(n.apiKey).build())

    def getWithApiKey(path: String): Future[Response] = retrying {
      _get(s"${n.nodeApiEndpoint}$path")
        .withApiKey(n.apiKey)
        .build()
    }

    def postJsonWithApiKey[A: Writes](path: String, body: A): Future[Response] = retrying {
      _post(s"${n.nodeApiEndpoint}$path")
        .withApiKey(n.apiKey)
        .setHeader("Content-type", "application/json;charset=utf-8")
        .setBody(stringify(toJson(body)))
        .build()
    }

    def postJsObjectWithApiKey(path: String, body: JsValue): Future[Response] = retrying {
      _post(s"${n.nodeApiEndpoint}$path")
        .withApiKey(n.apiKey)
        .setHeader("Content-type", "application/json")
        .setBody(stringify(body))
        .build()
    }

    def postJsObjectWithCustomHeader(path: String, body: JsValue, headerName: String = "Accept", headerValue: String): Future[Response] = retrying {
      _post(s"${n.nodeApiEndpoint}$path")
        .withApiKey(n.apiKey)
        .setHeader("Content-type", "application/json")
        .setHeader(headerName, headerValue)
        .setBody(stringify(body))
        .build()
    }

    def post(url: String, f: RequestBuilder => RequestBuilder = identity, waitForStatus: Boolean = false): Future[Response] =
      retrying(f(_post(url).withApiKey(n.apiKey)).build(), waitForStatus = waitForStatus)

    def put(url: String, f: RequestBuilder => RequestBuilder = identity, statusCode: Int = OK_200, waitForStatus: Boolean = false): Future[Response] =
      retrying(f(_put(url).withApiKey(n.apiKey)).build(), waitForStatus = waitForStatus, statusCode = statusCode)

    def postJson[A: Writes](path: String, body: A): Future[Response] =
      post(path, stringify(toJson(body)))

    def post(path: String, body: String): Future[Response] =
      post(s"${n.nodeApiEndpoint}$path", (rb: RequestBuilder) => rb.setHeader("Content-type", "application/json;charset=utf-8").setBody(body))

    def postForm(path: String, params: (String, String)*): Future[Response] =
      post(
        s"${n.nodeApiEndpoint}$path",
        (rb: RequestBuilder) =>
          rb.setHeader("Content-type", "application/x-www-form-urlencoded").setBody(params.map(p => p._1 + "=" + p._2).mkString("&"))
      )

    def blacklist(address: InetSocketAddress): Future[Unit] =
      post("/debug/blacklist", s"${address.getHostString}:${address.getPort}").map(_ => ())

    def clearBlacklist(): Future[Unit] =
      post(s"${n.nodeApiEndpoint}/peers/clearblacklist").map(_ => ())

    def printDebugMessage(db: DebugMessage): Future[Response] = postJsonWithApiKey("/debug/print", db)

    def connectedPeers: Future[Seq[Peer]] = get("/peers/connected").map { r =>
      (Json.parse(r.getResponseBody) \ "peers").as[Seq[Peer]]
    }

    def blacklistedPeers: Future[Seq[BlacklistedPeer]] = get("/peers/blacklisted").map { r =>
      Json.parse(r.getResponseBody).as[Seq[BlacklistedPeer]]
    }

    def allPeers: Future[Seq[KnownPeer]] = get("/peers/all").map { r =>
      (Json.parse(r.getResponseBody) \ "peers").as[Seq[KnownPeer]]
    }

    def connect(address: InetSocketAddress): Future[Unit] =
      postJson("/peers/connect", ConnectReq(address.getHostName, address.getPort)).map(_ => ())

    def waitForStartup(): Future[Option[Response]] = {
      val timeout = 500

      def request =
        _get(s"${n.nodeApiEndpoint}/blocks/height?${System.currentTimeMillis()}")
          .setReadTimeout(JDuration.ofMillis(timeout))
          .setRequestTimeout(JDuration.ofMillis(timeout))
          .build()

      def send(): Future[Option[Response]] =
        n.client
          .executeRequest(request)
          .toCompletableFuture
          .asScala
          .map(Option(_))
          .recoverWith { case _: IOException | _: TimeoutException =>
            Future(None)
          }

      def cond(ropt: Option[Response]) = ropt.exists { r =>
        r.getStatusCode == OK_200 && (Json.parse(r.getResponseBody) \ "height").as[Int] > 0
      }

      waitFor("node is up")(_ => send(), cond, 1.second)
    }

    def waitForPeers(targetPeersCount: Int): Future[Seq[Peer]] =
      waitFor[Seq[Peer]](s"connectedPeers.size >= $targetPeersCount")(_.connectedPeers, _.lengthCompare(targetPeersCount) >= 0, 1.second)

    def waitForBlackList(blackListSize: Int): Future[Seq[BlacklistedPeer]] =
      waitFor[Seq[BlacklistedPeer]](s"blacklistedPeers > $blackListSize")(_.blacklistedPeers, _.lengthCompare(blackListSize) > 0, 500.millis)

    def height: Future[Height] = get("/blocks/height").as[JsValue].map(v => (v \ "height").as[Height])

    def finalizedHeight: Future[Height] = get("/blocks/height/finalized").as[JsValue].map(v => (v \ "height").as[Height])

    def finalizedHeightAt(at: Height): Future[Height] = get(s"/blocks/finalized/at/$at").as[JsValue].map(v => (v \ "height").as[Height])

    def finalityStatus: Future[FinalityStatus] = for {
      _   <- activationStatus
      jsv <- get("/blockchain/finality").as[JsValue]
    } yield jsv.as[FinalityStatus](using
      FinalityStatus.parse(Some(Height(1)))
    )

    def blockAt(height: Height, amountsAsStrings: Boolean = false): Future[Block] =
      get(s"/blocks/at/$height", amountsAsStrings).as[Block](amountsAsStrings)

    def blockById(id: String, amountsAsStrings: Boolean = false): Future[Block] = get(s"/blocks/$id", amountsAsStrings).as[Block](amountsAsStrings)

    def utx(amountsAsStrings: Boolean = false): Future[Seq[Transaction]] = {
      get(s"/transactions/unconfirmed", amountsAsStrings).as[Seq[Transaction]](amountsAsStrings)
    }

    def utxById(txId: String, amountsAsStrings: Boolean = false): Future[Transaction] = {
      get(s"/transactions/unconfirmed/info/$txId", amountsAsStrings).as[Transaction](amountsAsStrings)
    }

    def utxSize: Future[Int] = get(s"/transactions/unconfirmed/size").as[JsObject].map(_.value("size").as[Int])

    def lastBlock(amountsAsStrings: Boolean = false): Future[Block] = get("/blocks/last", amountsAsStrings).as[Block](amountsAsStrings)

    def blockSeq(from: Height, to: Height, amountsAsStrings: Boolean = false): Future[Seq[Block]] =
      get(s"/blocks/seq/$from/$to", amountsAsStrings)
        .as[Seq[Block]](amountsAsStrings)

    def blockSeqByAddress(address: String, from: Height, to: Height, amountsAsStrings: Boolean = false): Future[Seq[Block]] =
      get(s"/blocks/address/$address/$from/$to", amountsAsStrings)
        .as[Seq[Block]](amountsAsStrings)

    def blockHeaderAt(height: Height, amountsAsStrings: Boolean = false): Future[BlockHeader] =
      get(s"/blocks/headers/at/$height", amountsAsStrings)
        .as[BlockHeader](amountsAsStrings)

    def blockHeaderForId(id: String, amountsAsStrings: Boolean = false): Future[BlockHeader] =
      get(s"/blocks/headers/$id", amountsAsStrings)
        .as[BlockHeader](amountsAsStrings)

    def blockHeadersSeq(from: Height, to: Height, amountsAsStrings: Boolean = false): Future[Seq[BlockHeader]] =
      get(s"/blocks/headers/seq/$from/$to", amountsAsStrings)
        .as[Seq[BlockHeader]](amountsAsStrings)

    def generators(atHeight: Height, amountsAsStrings: Boolean = false): Future[Seq[GeneratorsResponse.Entry]] =
      get(s"/generators/at/$atHeight", amountsAsStrings).as(amountsAsStrings)

    def lastBlockHeader(amountsAsStrings: Boolean = false): Future[BlockHeader] =
      get("/blocks/headers/last", amountsAsStrings)
        .as[BlockHeader](amountsAsStrings)

    def finalizedBlockHeader(amountsAsStrings: Boolean = false): Future[BlockHeader] =
      get("/blocks/headers/finalized", amountsAsStrings)
        .as[BlockHeader](amountsAsStrings)

    def status: Future[Status] = get("/node/status").as[Status]

    def activationStatus: Future[ActivationStatus] = get("/activation/status").as[ActivationStatus]

    def rewardStatus(height: Option[Height] = None, amountsAsString: Boolean = false): Future[RewardStatus] = {
      val maybeHeight = height.fold("")(a => s"/$a")
      get(s"/blockchain/rewards$maybeHeight", amountsAsString).as[RewardStatus](amountsAsString)
    }

    def balance(address: String, confirmations: Option[Int] = None, amountsAsStrings: Boolean = false): Future[Balance] = {
      val maybeConfirmations = confirmations.fold("")(a => s"/$a")
      get(s"/addresses/balance/$address$maybeConfirmations", amountsAsStrings).as[Balance](amountsAsStrings)
    }

    def balances(height: Option[Height], addresses: Seq[String], asset: Option[String]): Future[Seq[Balance]] = {
      for {
        json <- postJson(
          "/addresses/balance",
          Json.obj("addresses" -> addresses) ++
            height.fold(Json.obj())(h => Json.obj("height" -> h)) ++
            asset.fold(Json.obj())(a => Json.obj("asset" -> a))
        )
      } yield Json.parse(json.getResponseBody).as[Seq[JsObject]].map(r => Balance((r \ "id").as[String], 0, (r \ "balance").as[Long]))
    }

    def balanceDetails(address: String, amountsAsStrings: Boolean = false): Future[BalanceDetails] =
      get(s"/addresses/balance/details/$address", amountsAsStrings).as[BalanceDetails](amountsAsStrings)

    def getAddresses: Future[Seq[String]] = get(s"/addresses").as[Seq[String]]

    def findTransactionInfo(txId: String): Future[Option[TransactionInfo]] = transactionInfo[TransactionInfo](txId).transform {
      case Success(tx)                                          => Success(Some(tx))
      case Failure(UnexpectedStatusCodeException(_, _, 404, _)) => Success(None)
      case Failure(ex)                                          => Failure(ex)
    }

    def waitForTransaction(txId: String, retryInterval: FiniteDuration = 1.second): Future[TransactionInfo] =
      waitFor[Option[TransactionInfo]](s"transaction $txId")(
        _.transactionInfo[TransactionInfo](txId).transform {
          case Success(tx)                                          => Success(Some(tx))
          case Failure(UnexpectedStatusCodeException(_, _, 404, _)) => Success(None)
          case Failure(ex)                                          => Failure(ex)
        },
        tOpt => tOpt.exists(_.id == txId),
        retryInterval
      ).map(_.get)

    def waitForUtxIncreased(fromSize: Int): Future[Int] = waitFor[Int](s"utxSize > $fromSize")(
      _.utxSize,
      _ > fromSize,
      100.millis
    )

    def waitForHeight(expectedHeight: Height): Future[Height] =
      waitFor[Height](s"height >= $expectedHeight")(_.height, h => h >= expectedHeight, 2.seconds)

    def rawTransactionInfo(txId: String): Future[JsValue] = get(s"/transactions/info/$txId").map(r => Json.parse(r.getResponseBody))

    def transactionInfo[A: Reads](txId: String, amountsAsStrings: Boolean = false): Future[A] = {
      get(s"/transactions/info/$txId", amountsAsStrings).as[A](amountsAsStrings)
    }

    def transactionsStatus(txIds: Seq[String]): Future[Seq[TransactionStatus]] =
      postJson(s"/transactions/status", Json.obj("ids" -> txIds)).as[List[TransactionStatus]]

    def transactionsByAddress(address: String, limit: Int): Future[Seq[TransactionInfo]] =
      get(s"/transactions/address/$address/limit/$limit").as[Seq[Seq[TransactionInfo]]].map(_.flatten)

    def transactionsByAddress(address: String, limit: Int, after: String): Future[Seq[TransactionInfo]] = {
      get(s"/transactions/address/$address/limit/$limit?after=$after").as[Seq[Seq[TransactionInfo]]].map(_.flatten)
    }

    def assetDistributionAtHeight(
        asset: String,
        height: Height,
        limit: Int,
        maybeAfter: Option[String] = None,
        amountsAsStrings: Boolean = false
    ): Future[AssetDistributionPage] = {
      val after = maybeAfter.fold("")(a => s"?after=$a")
      val url   = s"/assets/$asset/distribution/$height/limit/$limit$after"

      get(url, amountsAsStrings).as[AssetDistributionPage](amountsAsStrings)
    }

    def assetDistribution(asset: String, amountsAsStrings: Boolean = false): Future[AssetDistribution] = {
      val req = s"/assets/$asset/distribution"
      get(req, amountsAsStrings).as[AssetDistribution](amountsAsStrings)
    }

    def effectiveBalance(address: String, confirmations: Option[Int] = None, amountsAsStrings: Boolean = false): Future[Balance] = {
      val maybeConfirmations = confirmations.fold("")(a => s"/$a")
      get(s"/addresses/effectiveBalance/$address$maybeConfirmations", amountsAsStrings).as[Balance](amountsAsStrings)
    }

    def transfer(
        sender: SigningKey,
        recipient: String,
        amount: Long,
        fee: Long,
        assetId: Option[String] = None,
        feeAssetId: Option[String] = None,
        attachment: Option[String] = None
    ): Future[Transaction] =
      signedBroadcast(
        TransferTransaction(
          PublicKey(sender.publicKey()),
          Address.fromString(recipient).explicitGet(),
          Asset.fromString(assetId),
          TxPositiveAmount.unsafeFrom(amount),
          Asset.fromString(feeAssetId),
          TxPositiveAmount.unsafeFrom(fee),
          attachment.fold(ByteStr.empty)(s => ByteStr(s.getBytes)),
          System.currentTimeMillis(),
          Proofs.empty,
          AddressScheme.current.chainId
        ).signWith(sender)
          .json()
      )

    def payment(sourceAddress: String, recipient: String, amount: Long, fee: Long): Future[Transaction] =
      postJson("/waves/payment", PaymentRequest(amount, fee, sourceAddress, recipient)).as[Transaction]

    def lease(sender: SigningKey, recipient: String, amount: Long, fee: Long): Future[Transaction] =
      signedBroadcast(
        LeaseTransaction(
          PublicKey(sender.publicKey()),
          Address.fromString(recipient).explicitGet(),
          TxPositiveAmount.unsafeFrom(amount),
          TxPositiveAmount.unsafeFrom(fee),
          System.currentTimeMillis(),
          Proofs.empty,
          AddressScheme.current.chainId
        ).signWith(sender)
          .json()
      )

    def cancelLease(sender: SigningKey, leaseId: String, fee: Long): Future[Transaction] =
      signedBroadcast(
        LeaseCancelTransaction(
          PublicKey(sender.publicKey()),
          ByteStr.decodeBase58(leaseId).get,
          TxPositiveAmount.unsafeFrom(fee),
          System.currentTimeMillis(),
          Proofs.empty,
          AddressScheme.current.chainId
        ).signWith(sender).json()
      )

    def activeLeases(sourceAddress: String): Future[Seq[LeaseInfo]] = get(s"/leasing/active/$sourceAddress").as[Seq[LeaseInfo]]

    def assetBalance(address: String, asset: String, amountsAsStrings: Boolean = false): Future[AssetBalance] =
      get(s"/assets/balance/$address/$asset", amountsAsStrings).as[AssetBalance](amountsAsStrings)

    def assetsBalance(address: String, amountsAsStrings: Boolean = false): Future[FullAssetsInfo] =
      get(s"/assets/balance/$address", amountsAsStrings).as[FullAssetsInfo](amountsAsStrings)

    def nftList(address: String, limit: Int, maybeAfter: Option[String] = None, amountsAsStrings: Boolean = false): Future[Seq[NFTAssetInfo]] = {
      val after = maybeAfter.fold("")(a => s"?after=$a")
      get(s"/assets/nft/$address/limit/$limit$after", amountsAsStrings).as[Seq[NFTAssetInfo]](amountsAsStrings)
    }

    def assetsDetails(assetId: String, fullInfo: Boolean = false, amountsAsStrings: Boolean = false): Future[AssetInfo] = {
      get(s"/assets/details/$assetId?full=$fullInfo", amountsAsStrings).as[AssetInfo](amountsAsStrings)
    }

    def massTransfer(
        sender: SigningKey,
        transfers: Seq[Transfer],
        fee: Long,
        attachment: Option[String] = None,
        assetId: Option[String] = None,
        amountsAsStrings: Boolean = false
    ): Future[Transaction] = {
      signedBroadcast(
        MassTransferTransaction(
          PublicKey(sender.publicKey()),
          Asset.fromString(assetId),
          transfers.map(t => ParsedTransfer(Address.fromString(t.recipient).explicitGet(), TxNonNegativeAmount.unsafeFrom(t.amount))),
          TxPositiveAmount.unsafeFrom(fee),
          System.currentTimeMillis(),
          attachment.fold(ByteStr.empty)(s => ByteStr(s.getBytes())),
          Proofs.empty,
          AddressScheme.current.chainId
        ).signWith(sender).json(),
        amountsAsStrings
      )
    }

    def getMerkleProof(ids: String*): Future[Seq[MerkleProofResponse]] =
      get(s"/transactions/merkleProof?${ids.map("id=" + URLEncoder.encode(_, "UTF-8")).mkString("&")}").as[Seq[MerkleProofResponse]]

    def getMerkleProofPost(ids: String*): Future[Seq[MerkleProofResponse]] =
      postJson(s"/transactions/merkleProof", Json.obj("ids" -> ids)).as[Seq[MerkleProofResponse]]

    def sign(json: JsValue): Future[Transaction] =
      postJson(s"/transactions/sign", json).as[Transaction]

    def broadcastRequest[A: Writes](req: A): Future[Transaction] = postJson("/transactions/broadcast", req).as[Transaction]

    def broadcastTraceRequest[A: Writes](req: A): Future[Transaction] = postJson("/transactions/broadcast?trace=yes", req).as[Transaction]

    def expectSignedBroadcastRejected(json: JsValue): Future[Int] = {
      post("/transactions/broadcast", stringify(json)).transform {
        case Failure(UnexpectedStatusCodeException(_, _, 400, body)) => Success((Json.parse(body) \ "error").as[Int])
        case Failure(cause)                                          => Failure(cause)
        case Success(resp) => Failure(UnexpectedStatusCodeException("POST", "/transactions/broadcast", resp.getStatusCode, resp.getResponseBody))
      }
    }

    def signedBroadcast(json: JsValue, amountsAsStrings: Boolean = false): Future[Transaction] = {
      if (amountsAsStrings) {
        postJsObjectWithCustomHeader("/transactions/broadcast", json, headerValue = "application/json;large-significand-format=string")
          .as[Transaction](amountsAsStrings)
      } else {
        post("/transactions/broadcast", stringify(json)).as[Transaction]
      }
    }

    def signedBroadcast(json: JsValue): Future[Transaction] =
      post("/transactions/broadcast", stringify(json)).as[Transaction]

    def signedTraceBroadcast(json: JsValue): Future[(Transaction, JsValue)] =
      post("/transactions/broadcast?trace=yes", stringify(json)).as[JsValue].map(r => (r.as[Transaction], r))

    def signedValidate(json: JsValue): Future[JsValue] = post("/debug/validate", stringify(json)).as[JsValue]

    def batchSignedTransfer(transfers: Seq[TransferRequest]): Future[Seq[Transaction]] = {
      Future.sequence(transfers.map(v => signedBroadcast(toJson(v).as[JsObject] ++ Json.obj("type" -> TransactionType.Transfer.id))))
    }

    def broadcastExchange(
        matcher: SigningKey,
        order1: Order,
        order2: Order,
        amount: TxExchangeAmount,
        price: TxExchangePrice,
        buyMatcherFee: Long,
        sellMatcherFee: Long,
        fee: Long,
        amountsAsStrings: Boolean = false,
        validate: Boolean = true
    ): Future[Transaction] = {
      val tx = ExchangeTx(
        order1 = order1,
        order2 = order2,
        amount = amount,
        price = price,
        buyMatcherFee = TxMatcherFee.unsafeFrom(buyMatcherFee),
        sellMatcherFee = TxMatcherFee.unsafeFrom(sellMatcherFee),
        fee = TxPositiveAmount.unsafeFrom(fee),
        proofs = Proofs.empty,
        timestamp = System.currentTimeMillis(),
        chainId = AddressScheme.current.chainId
      ).signWith(matcher)

      val json = if (validate) tx.validatedEither.explicitGet().json() else tx.json()
      signedBroadcast(json, amountsAsStrings)
    }

    def rollback(to: Height, returnToUTX: Boolean = true): Future[Unit] =
      postJson("/debug/rollback", RollbackParams(to.toInt, returnToUTX)).map(_ => ())

    def ensureTxDoesntExist(txId: String): Future[Unit] =
      utx()
        .zip(findTransactionInfo(txId))
        .flatMap({
          case (utx, _) if utx.map(_.id).contains(txId) =>
            Future.failed(new IllegalStateException(s"Tx $txId is in UTX"))
          case (_, txOpt) if txOpt.isDefined =>
            Future.failed(new IllegalStateException(s"Tx $txId is in blockchain"))
          case _ =>
            Future.successful(())
        })

    def waitFor[A](desc: String)(f: this.type => Future[A], cond: A => Boolean, retryInterval: FiniteDuration): Future[A] = {
      n.log.debug(s"Awaiting condition '$desc'")
      timer
        .retryUntil(f(this), cond, retryInterval)
        .map(a => {
          n.log.debug(s"Condition '$desc' met")
          a
        })
    }

    def createKeyPair(): Future[SigningKey] = Future.successful(n.generateKeyPair())

    // There is no API to recover a seed or key from an address any more (see CLAUDE.md "Keys"), so this can only
    // return the address, not a SigningKey; use it where the node itself must hold the key (e.g. to sign server-side
    // on the caller's behalf), and createKeyPair() where a purely local key suffices.
    def createAddressServerSide(): Future[String] =
      post(s"${n.nodeApiEndpoint}/addresses").as[JsValue].map(v => (v \ "address").as[String])

    def waitForNextBlock: Future[BlockHeader] =
      for {
        currentBlock <- lastBlockHeader()
        actualBlock  <- findBlockHeaders(_.height > currentBlock.height, currentBlock.height)
      } yield actualBlock

    def waitForHeightArise: Future[Height] =
      for {
        height    <- height
        newHeight <- waitForHeight(height + 1)
      } yield newHeight

    def findBlock(cond: Block => Boolean, from: Height = Height(1), to: Height = Height(Int.MaxValue)): Future[Block] = {
      def load(_from: Height, _to: Height): Future[Block] = blockSeq(_from, _to).flatMap { blocks =>
        blocks
          .find(cond)
          .fold[Future[Block]] {
            val maybeLastBlock = blocks.lastOption
            if (maybeLastBlock.exists(_.height >= to.toInt)) {
              Future.failed(new NoSuchElementException)
            } else {
              val newFrom = maybeLastBlock.fold(_from)(b => Height(b.height + 19).min(to))
              val newTo   = newFrom + 19
              n.log.debug(s"Loaded ${blocks.length} blocks, no match found. Next range: [$newFrom, ${newFrom + 19}]")
              timer.schedule(load(newFrom, newTo), n.settings.blockchainSettings.genesisSettings.averageBlockDelay)
            }
          }(Future.successful)
      }

      load(from, (from + 19).min(to))
    }

    def findBlockHeaders(cond: BlockHeader => Boolean, from: Height = Height(1), to: Height = Height(Int.MaxValue)): Future[BlockHeader] = {
      def load(_from: Height, _to: Height): Future[BlockHeader] = blockHeadersSeq(_from, _to).flatMap { blocks =>
        blocks
          .find(cond)
          .fold[Future[BlockHeader]] {
            val maybeLastBlock = blocks.lastOption
            if (maybeLastBlock.exists(_.height >= to)) {
              Future.failed(new NoSuchElementException)
            } else {
              val newFrom = maybeLastBlock.fold(_from)(b => (b.height + 19).min(to))
              val newTo   = newFrom + 19
              n.log.debug(s"Loaded ${blocks.length} blocks, no match found. Next range: [$newFrom, ${newFrom + 19}]")
              timer.schedule(load(newFrom, newTo), n.settings.blockchainSettings.genesisSettings.averageBlockDelay)
            }
          }(Future.successful)
      }

      load(from, (from + 19).min(to))
    }

    def getGeneratedBlocks(address: String, from: Long, to: Long): Future[Seq[Block]] =
      get(s"/blocks/address/$address/$from/$to").as[Seq[Block]]

    def retrying(r: Request, interval: FiniteDuration = 1.second, statusCode: Int = OK_200, waitForStatus: Boolean = false): Future[Response] = {
      def executeRequest: Future[Response] = {
        val log = !(r.getMethod == "POST" && r.getUri.getPath == "/debug/print")
        val id  = counter.getAndIncrement()
        if (log) {
          val s = new StringBuilder(s"[$id] Executing: ${r.getMethod} ${r.getUri}")
          if (r.getHeaders != null && !r.getHeaders.isEmpty) s.append(s", ${r.getHeaders}")
          if (r.getStringData != null) s.append(s", body:\n${r.getStringData}")
          n.log.debug(s.toString())
        }
        n.client
          .executeRequest(
            r,
            new AsyncCompletionHandler[Response] {
              override def onCompleted(response: Response): Response = {
                if (response.getStatusCode == statusCode) {
                  if (log) n.log.debug(s"[$id] Result: ${response.getResponseBody}")
                  response
                } else {
                  if (log) n.log.debug(s"[$id] Result: Unexpected status code(${response.getStatusCode}): ${response.getResponseBody}")
                  throw UnexpectedStatusCodeException(r.getMethod, r.getUrl, response.getStatusCode, response.getResponseBody)
                }
              }
            }
          )
          .toCompletableFuture
          .asScala
          .recoverWith {
            case e: UnexpectedStatusCodeException if e.statusCode == 503 || waitForStatus =>
              n.log.debug(s"[$id] Failed to execute request '$r' with error: ${e.getMessage}")
              timer.schedule(executeRequest, interval)
            case e @ (_: IOException | _: TimeoutException) =>
              n.log.debug(s"[$id] Failed to execute request '$r' with error: ${e.getMessage}")
              timer.schedule(executeRequest, interval)
          }
      }

      executeRequest
    }

    def debugStateAt(height: Height): Future[Map[String, Long]] = getWithApiKey(s"/debug/stateWaves/$height").as[Map[String, Long]]

    def debugBalanceHistory(address: String, amountsAsStrings: Boolean = false): Future[Seq[BalanceHistory]] = {
      get(s"/debug/balances/history/$address", withApiKey = true, amountsAsStrings = amountsAsStrings)
        .as[Seq[BalanceHistory]](amountsAsStrings)
    }

    implicit val assetMapReads: Reads[VectorMap[IssuedAsset, Long]] = implicitly[Reads[Map[String, Long]]].map(_.map { case (k, v) =>
      IssuedAsset(ByteStr.decodeBase58(k).get) -> v
    }.to(VectorMap))
    implicit val leaseBalanceFormat: Reads[LeaseBalance] = Json.reads[LeaseBalance]
    implicit val portfolioFormat: Reads[Portfolio]       = Json.reads[Portfolio]

    def debugMinerInfo(): Future[Seq[State]] = getWithApiKey(s"/debug/minerInfo").as[Seq[State]]

    def transactionSerializer(body: JsObject): Future[TransactionSerialize] =
      postJsObjectWithApiKey(s"/utils/transactionSerialize", body).as[TransactionSerialize]

    def accountEffectiveBalance(acc: String): Future[Long] = n.effectiveBalance(acc).map(_.balance)

    def accountBalance(acc: String): Future[Long] = n.balance(acc).map(_.balance)

    def balanceAtHeight(address: String, height: Height): Future[Long] =
      accountsBalances(Some(height), Seq(address), None).map(_.collectFirst { case (`address`, balance) => balance }.getOrElse(0L))

    def accountsBalances(height: Option[Height], accounts: Seq[String], asset: Option[String]): Future[Seq[(String, Long)]] =
      n.balances(height, accounts, asset).map(_.map(b => (b.address, b.balance)))

    def accountBalances(acc: String): Future[(Long, Long)] =
      n.balanceDetails(acc).map(bd => bd.regular -> bd.effective)

    def assertBalances(acc: String, balance: Long, effectiveBalance: Long)(implicit pos: Position): Future[Unit] =
      for {
        newBalance <- balanceDetails(acc)
      } yield {
        withClue(s"effective balance of $acc") {
          newBalance.effective shouldBe effectiveBalance
        }
        withClue(s"balance of $acc") {
          newBalance.regular shouldBe balance
        }
      }

    def assertAssetBalance(acc: String, assetIdString: String, balance: Long)(implicit pos: Position): Future[Unit] = {
      for {
        plainBalance <- n.assetBalance(acc, assetIdString)
        pf           <- n.assetsBalance(acc)
        asset        <- n.assetsDetails(assetIdString)
        nftList      <- n.nftList(acc, 100)
      } yield {
        plainBalance.balance shouldBe balance
        if (asset.isNFT) {
          nftList.count(_.assetId == assetIdString) shouldBe balance
        } else if (balance != 0) {
          pf.balances.find(_.assetId == assetIdString).map(_.balance) should contain(balance)
        }
      }
    }

    def calculateFee(json: JsValue, amountsAsStrings: Boolean = false): Future[FeeInfo] = {
      if (amountsAsStrings) {
        postJsObjectWithCustomHeader("/transactions/calculateFee", json, headerValue = "application/json;large-significand-format=string")
          .as[FeeInfo](amountsAsStrings)
      } else {
        postJsObjectWithApiKey("/transactions/calculateFee", json).as[FeeInfo]
      }
    }
  }

  implicit class NodesAsyncHttpApi(nodes: Seq[Node]) extends matchers.should.Matchers {
    def height: Future[Seq[Height]] = traverse(nodes)(_.height)

    def waitForHeightAriseAndTxPresent(transactionId: String): Future[Unit] =
      for {
        allHeights <- traverse(nodes)(_.waitForTransaction(transactionId).map(_.height))
        _          <- traverse(nodes)(_.waitForHeight(Height(allHeights.max + 1)))
        _ <- waitFor("nodes sync")(1 second)(
          _.waitForTransaction(transactionId).map(_.height),
          (finalHeights: Iterable[Int]) => finalHeights.forall(_ == finalHeights.head)
        )
      } yield ()

    def waitForTransaction(transactionId: String): Future[TransactionInfo] =
      traverse(nodes)(_.waitForTransaction(transactionId)).map(_.head)

    def waitForHeightArise(): Future[Height] =
      for {
        height <- height.map(_.max)
        _      <- traverse(nodes)(_.waitForHeight(height + 1))
      } yield height + 1

    def waitForSameBlockHeadersAt(height: Height, retryInterval: FiniteDuration = 5.seconds): Future[Boolean] = {

      def waitHeight = waitFor[Height](s"all heights >= $height")(retryInterval)(_.height, _.forall(_ >= height))

      def waitSameBlockHeaders =
        waitFor[BlockHeader](s"same blocks at height = $height")(retryInterval)(
          _.blockHeaderAt(height),
          { blocks =>
            val id = blocks.map(_.id)
            id.forall(_ == id.head)
          }
        )

      for {
        _ <- waitHeight
        r <- waitSameBlockHeaders
      } yield r
    }

    def waitFor[A](desc: String)(retryInterval: FiniteDuration)(request: Node => Future[A], cond: Iterable[A] => Boolean): Future[Boolean] = {
      def retry = timer.schedule(waitFor(desc)(retryInterval)(request, cond), retryInterval)

      Future
        .traverse(nodes)(request)
        .map(cond)
        .recover { case _ => false }
        .flatMap {
          case true  => Future.successful(true)
          case false => retry
        }
    }

  }

  implicit class RequestBuilderOps(self: RequestBuilder) {
    def withApiKey(x: String): RequestBuilder = self.setHeader(`X-Api-Key`.name, x)
  }
}
