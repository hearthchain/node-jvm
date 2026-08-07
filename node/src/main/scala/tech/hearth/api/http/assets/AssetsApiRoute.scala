package tech.hearth.api.http.assets

import cats.data.Validated
import cats.instances.either.*
import cats.instances.list.*
import cats.syntax.alternative.*
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.{JsonSerializer, SerializerProvider}
import tech.hearth.account.Address
import tech.hearth.api.common.{CommonAccountsApi, CommonAssetsApi}
import tech.hearth.api.http.*
import tech.hearth.api.http.ApiError.*
import tech.hearth.api.http.StreamSerializerUtils.*
import tech.hearth.api.http.assets.AssetsApiRoute.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.settings.RestAPISettings
import tech.hearth.state.{AssetDescription, Blockchain, Height}
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.utils.Time
import tech.hearth.wallet.Wallet
import io.netty.util.concurrent.DefaultThreadFactory
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.marshalling.{ToResponseMarshallable, ToResponseMarshaller}
import org.apache.pekko.http.scaladsl.model.headers.Accept
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.Source
import play.api.libs.json.*

import java.util.concurrent.*
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

case class AssetsApiRoute(
    settings: RestAPISettings,
    serverRequestTimeout: FiniteDuration,
    wallet: Wallet,
    blockchain: Blockchain,
    time: Time,
    commonAccountApi: CommonAccountsApi,
    commonAssetsApi: CommonAssetsApi,
    maxDistributionDepth: Int,
    routeTimeout: RouteTimeout
) extends ApiRoute
    with AuthRoute {

  private val distributionTaskScheduler = Scheduler(
    new ThreadPoolExecutor(
      1,
      1,
      0L,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue[Runnable](AssetsApiRoute.MAX_DISTRIBUTION_TASKS),
      new DefaultThreadFactory("balance-distribution", true)
    )
  )

  private val assetDistRouteTimeout = new RouteTimeout(serverRequestTimeout)(using distributionTaskScheduler)

  override lazy val route: Route =
    pathPrefix("assets") {
      pathPrefix("balance" / AddrSegment) { address =>
        anyParam("id", limit = settings.assetDetailsLimit) { assetIds =>
          val assetIdsValidated = assetIds.toList
            .map(assetId => ByteStr.decodeBase16(assetId).fold(_ => Left(assetId), bs => Right(IssuedAsset(bs))).toValidatedNel)
            .sequence

          assetIdsValidated match {
            case Validated.Valid(assets) =>
              balances(address, Some(assets).filter(_.nonEmpty))

            case Validated.Invalid(invalidAssets) =>
              complete(InvalidIds(invalidAssets.toList))
          }
        } ~ (get & path(AssetId)) { assetId =>
          balance(address, assetId)
        }
      } ~ pathPrefix("details") {
        (anyParam("id", limit = settings.assetDetailsLimit)) { ids =>
          if (ids.isEmpty) complete(AssetIdNotSpecified)
          else {
            routeTimeout.executeToFuture(Task(multipleDetails(ids.toList)))
          }
        } ~ (get & path(AssetId)) { assetId =>
          singleDetails(assetId)
        }
      } ~ get {
        pathPrefix(AssetId / "distribution") { assetId =>
          pathEndOrSingleSlash(balanceDistribution(assetId)) ~
            (path(IntNumber / "limit" / IntNumber) & parameter("after".?)) { (height, limit, maybeAfter) =>
              balanceDistributionAtHeight(assetId, height, limit, maybeAfter)
            }
        }
      }
    }

  private def multipleDetails(ids: List[String]): ToResponseMarshallable =
    ids.map(id => ByteStr.decodeBase16(id).toEither.leftMap(_ => id)).separate match {
      case (Nil, assetIds) =>
        assetIds.map(id => assetDetails(IssuedAsset(id))).separate match {
          case (Nil, details) => details
          case (errors, _) =>
            val notFoundErrors = errors.collect { case AssetDoesNotExist(assetId) => assetId }
            if (notFoundErrors.isEmpty) {
              errors.head
            } else {
              AssetsDoesNotExist(notFoundErrors)
            }
        }
      case (errors, _) => InvalidIds(errors)
    }

  def getFullAssetInfo(balances: Seq[(IssuedAsset, Long)]): Seq[AssetInfo] =
    balances.view
      .zip(commonAssetsApi.fullInfos(balances.map(_._1)))
      .map { case ((asset, balance), infoOpt) =>
        infoOpt match {
          case Some(CommonAssetsApi.AssetInfo(assetInfo)) =>
            AssetInfo.FullAssetInfo(
              assetId = asset.id.toString,
              quantity = BigDecimal(assetInfo.totalVolume),
              balance = balance,
              sequenceInBlock = assetInfo.sequenceInBlock
            )
          case None => AssetInfo.AssetId(asset.id.toString)
        }
      }
      .toSeq

  /** @param assets
    *   Some(assets) for specific asset balances, None for a full portfolio
    */
  def balances(address: Address, assets: Option[Seq[IssuedAsset]] = None): Route = {
    implicit val jsonStreamingSupport: ToResponseMarshaller[Source[AssetInfo, NotUsed]] =
      jacksonStreamMarshaller(s"""{"address":"$address","balances":[""", ",", "]}")(using AssetsApiRoute.assetInfoSerializer)

    routeTimeout.executeFromObservable(
      (assets match {
        case Some(assets) =>
          Observable.eval(assets.map(asset => asset -> blockchain.balance(address, asset)))
        case None =>
          commonAccountApi
            .portfolio(address)
      }).concatMapIterable(getFullAssetInfo)
    )
  }

  def balance(address: Address, assetId: IssuedAsset): Route = complete(balanceJson(address, assetId))

  private def balanceDistribution(assetId: IssuedAsset, height: Int, limit: Int, after: Option[Address])(f: List[(Address, Long)] => JsValue) =
    complete {
      try {
        commonAssetsApi
          .assetDistribution(assetId, height, after)
          .take(limit)
          .toListL
          .map(f)
          .runAsyncLogErr(using distributionTaskScheduler)
      } catch {
        case _: RejectedExecutionException =>
          val errMsg = CustomValidationError("Asset distribution currently unavailable, try again later")
          Future.successful(errMsg.json: ToResponseMarshallable)
      }
    }

  def balanceDistribution(assetId: IssuedAsset): Route = {
    implicit val jsonStreamingSupport: ToResponseMarshaller[Source[(Address, Long), NotUsed]] =
      jacksonStreamMarshaller(prefix = "{", suffix = "}")(using assetDistributionSerializer)

    assetDistRouteTimeout.executeFromObservable(
      commonAssetsApi
        .assetDistribution(assetId, blockchain.height, None)
    )
  }

  def balanceDistributionAtHeight(assetId: IssuedAsset, heightParam: Int, limitParam: Int, afterParam: Option[String]): Route =
    optionalHeaderValueByType(Accept) { accept =>
      val paramsEi: Either[ValidationError, DistributionParams] =
        AssetsApiRoute
          .validateDistributionParams(blockchain, heightParam, limitParam, settings.distributionAddressLimit, afterParam, maxDistributionDepth)

      paramsEi match {
        case Right((height, limit, after)) =>
          balanceDistribution(assetId, height, limit, after) { l =>
            Json.obj(
              "hasNext"  -> (l.length == limit),
              "lastItem" -> l.lastOption.map(_._1),
              "items" -> Json.toJson(l.map { case (a, b) =>
                a.toString -> accept.fold[JsValue](JsNumber(b)) {
                  case a if a.mediaRanges.exists(CustomJson.acceptsNumbersAsStrings) => JsString(b.toString)
                  case _                                                             => JsNumber(b)
                }
              }.toMap)
            )
          }
        case Left(error) => complete(error)
      }
    }

  def singleDetails(assetId: IssuedAsset): Route = complete(assetDetails(assetId))

  private def balanceJson(address: Address, assetId: IssuedAsset): JsObject =
    Json.obj(
      "address" -> address,
      "assetId" -> assetId.id.toString,
      "balance" -> JsNumber(BigDecimal(blockchain.balance(address, assetId)))
    )

  private def assetDetails(assetId: IssuedAsset): Either[ApiError, JsObject] = {
    for {
      description <- blockchain.assetDescription(assetId).toRight(AssetDoesNotExist(assetId))
      result      <- AssetsApiRoute.jsonDetails(assetId, description).leftMap(CustomValidationError(_))
    } yield result
  }
}

object AssetsApiRoute {
  val MAX_DISTRIBUTION_TASKS = 5

  type DistributionParams = (Int, Int, Option[Address])

  def validateDistributionParams(
      blockchain: Blockchain,
      heightParam: Int,
      limitParam: Int,
      maxLimit: Int,
      afterParam: Option[String],
      maxDistributionDepth: Int
  ): Either[ValidationError, DistributionParams] = {
    for {
      limit  <- validateLimit(limitParam, maxLimit)
      height <- validateHeight(blockchain, heightParam, maxDistributionDepth)
      after <- afterParam
        .fold[Either[ValidationError, Option[Address]]](Right(None))(addrString => Address.fromString(addrString).map(Some(_)))
    } yield (height, limit, after)
  }

  def validateHeight(blockchain: Blockchain, height: Int, maxDistributionDepth: Int): Either[ValidationError, Int] = {
    for {
      _ <- Either.cond(height > 0, (), GenericError(s"Height should be greater than zero"))
      _ <- Either.cond(
        height != blockchain.height,
        (),
        GenericError(s"Using 'assetDistributionAtHeight' on current height can lead to inconsistent result")
      )
      _ <- Either.cond(
        height < blockchain.height,
        (),
        GenericError(s"Asset distribution available only at height not greater than ${blockchain.height - 1}")
      )
      _ <- Either
        .cond(
          height >= blockchain.height - maxDistributionDepth,
          (),
          GenericError(s"Unable to get distribution past height ${blockchain.height - maxDistributionDepth}")
        )
    } yield height

  }

  def validateLimit(limit: Int, maxLimit: Int): Either[ValidationError, Int] = {
    for {
      _ <- Either.cond(limit > 0, (), GenericError("Limit should be greater than 0"))
      _ <- Either.cond(limit <= maxLimit, (), GenericError(s"Limit should be less than or equal to $maxLimit"))
    } yield limit
  }

  def jsonDetails(id: IssuedAsset, description: AssetDescription): Either[String, JsObject] =
    Right(
      JsObject(
        Seq(
          "assetId"     -> JsString(id.id.toString),
          "issueHeight" -> JsNumber(description.issueHeight.toInt),
          // Nothing issues an asset via a transaction any more (see CLAUDE.md's Transaction JSON notes), so there's
          // no real issue timestamp to report; kept as a field for API compatibility, always 0.
          "issueTimestamp"  -> JsNumber(0),
          "name"            -> JsString(description.name.toStringUtf8),
          "description"     -> JsString(description.description.toStringUtf8),
          "decimals"        -> JsNumber(description.decimals),
          "quantity"        -> JsNumber(BigDecimal(description.totalVolume)),
          "sequenceInBlock" -> JsNumber(description.sequenceInBlock),
          "minAssetFee"     -> JsNumber(description.minAssetFee.value)
        )
      )
    )

  sealed trait AssetInfo
  object AssetInfo {
    case class FullAssetInfo(
        assetId: String,
        quantity: BigDecimal,
        balance: Long,
        sequenceInBlock: Int
    ) extends AssetInfo

    case class AssetId(assetId: String) extends AssetInfo
  }

  def assetInfoSerializer(numbersAsString: Boolean): JsonSerializer[AssetInfo] =
    (value: AssetInfo, gen: JsonGenerator, _: SerializerProvider) => {
      value match {
        case info: AssetInfo.FullAssetInfo =>
          gen.writeStartObject()
          gen.writeStringField("assetId", info.assetId)
          gen.writeNumberField("quantity", info.quantity, numbersAsString)
          gen.writeNumberField("balance", info.balance, numbersAsString)
          gen.writeNumberField("sequenceInBlock", info.sequenceInBlock, numbersAsString)
          gen.writeEndObject()
        case assetId: AssetInfo.AssetId =>
          gen.writeStartObject()
          gen.writeStringField("assetId", assetId.assetId)
          gen.writeEndObject()
      }
    }

  def assetDistributionSerializer(numbersAsString: Boolean): JsonSerializer[(Address, Long)] =
    (value: (Address, Long), gen: JsonGenerator, _: SerializerProvider) => {
      val (address, balance) = value
      if (numbersAsString) {
        gen.writeRaw(s"\"${address.toString}\":")
        gen.writeString(balance.toString)
      } else {
        gen.writeRaw(s"\"${address.toString}\":")
        gen.writeNumber(balance)
      }
    }
}
