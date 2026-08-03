package tech.hearth.api.http.leasing

import tech.hearth.api.common.{CommonAccountsApi, LeaseInfo}
import tech.hearth.api.http.*
import tech.hearth.api.http.ApiError.{InvalidIds, TransactionDoesNotExist}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base58
import tech.hearth.network.TransactionPublisher
import tech.hearth.settings.RestAPISettings
import tech.hearth.state.Blockchain
import tech.hearth.utils.Time
import tech.hearth.wallet.Wallet
import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.*
import play.api.libs.json.JsonConfiguration.Aux

case class LeaseApiRoute(
    settings: RestAPISettings,
    wallet: Wallet,
    blockchain: Blockchain,
    transactionPublisher: TransactionPublisher,
    time: Time,
    commonAccountApi: CommonAccountsApi,
    routeTimeout: RouteTimeout
) extends ApiRoute
    with AuthRoute {
  import LeaseApiRoute.*

  override val route: Route = pathPrefix("leasing") {
    active ~ leaseInfo
  }

  private def active: Route = (pathPrefix("active") & get) {
    path(AddrSegment) { address =>
      routeTimeout.executeToFuture(
        commonAccountApi.activeLeases(address).map(Json.toJson(_)).toListL
      )
    }
  }

  private def leaseInfo: Route = pathPrefix("info") {
    (get & path(TransactionId)) { leaseId =>
      val result = commonAccountApi
        .leaseInfo(leaseId())
        .toRight(TransactionDoesNotExist)

      complete(result)
    } ~ anyParam("id", limit = settings.transactionsByAddressLimit) { ids =>
      leasingInfosMap(ids) match {
        case Left(err) => complete(err)
        case Right(leaseInfoByIdMap) =>
          val results = ids.map(leaseInfoByIdMap).toVector
          complete(results)
      }
    }
  }

  private def leasingInfosMap(ids: Iterable[String]): Either[InvalidIds, Map[String, LeaseInfo]] = {
    val infos = ids.map(id =>
      (for {
        id <- Base58.tryDecodeWithLimit(id).toOption
        li <- commonAccountApi.leaseInfo(ByteStr(id))
      } yield li).toRight(id)
    )
    val failed = infos.flatMap(_.left.toOption)

    if (failed.isEmpty) {
      Right(infos.collect { case Right(li) =>
        li.id.toString -> li
      }.toMap)
    } else {
      Left(InvalidIds(failed.toVector))
    }
  }
}

object LeaseApiRoute {
  implicit val leaseStatusWrites: Writes[LeaseInfo.Status] =
    Writes(s => JsString(s.toString.toLowerCase))

  implicit val config: Aux[Json.MacroOptions] = JsonConfiguration(optionHandlers = OptionHandlers.WritesNull)

  implicit val leaseInfoWrites: OWrites[LeaseInfo] = {
    import tech.hearth.account.Address.jsonFormat
    import tech.hearth.utils.byteStrFormat
    Json.writes[LeaseInfo]
  }
}
