package tech.hearth.api.http.utils

import tech.hearth.account.{Address, PublicKey}
import tech.hearth.api.http.*
import tech.hearth.api.http.ApiError.TooBigArrayAllocation
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base58
import tech.hearth.crypto
import tech.hearth.settings.RestAPISettings
import tech.hearth.state.Blockchain
import tech.hearth.utils.Time
import monix.execution.Scheduler
import org.apache.pekko.http.scaladsl.server.Route
import play.api.libs.json.*

import java.security.SecureRandom

case class UtilsApiRoute(
    timeService: Time,
    settings: RestAPISettings,
    maxTxErrorLogSize: Int,
    limitedScheduler: Scheduler,
    blockchain: Blockchain
) extends ApiRoute
    with AuthRoute
    with TimeLimitedRoute {

  import UtilsApiRoute.*

  private def seed(length: Int): JsObject = {
    val seed = new Array[Byte](length)
    new SecureRandom().nextBytes(seed) // seed mutated here!
    Json.obj("seed" -> Base58.encode(seed))
  }

  override val route: Route = pathPrefix("utils") {
    time ~ seedRoute ~ length ~ hashFast ~ hashSecure ~ transactionSerialize
  }

  def time: Route = (path("time") & get) {
    complete(Json.obj("system" -> System.currentTimeMillis(), "NTP" -> timeService.correctedTime()))
  }

  def seedRoute: Route = (path("seed") & get) {
    complete(seed(DefaultSeedSize))
  }

  def length: Route = (path("seed" / IntNumber) & get) { length =>
    if (length <= MaxSeedSize) complete(seed(length))
    else complete(TooBigArrayAllocation)
  }

  def hashSecure: Route = (path("hash" / "secure") & post) {
    entity(as[String]) { message =>
      complete(Json.obj("message" -> message, "hash" -> Base58.encode(crypto.secureHash(message))))
    }
  }

  def hashFast: Route = (path("hash" / "fast") & post) {
    entity(as[String]) { message =>
      complete(Json.obj("message" -> message, "hash" -> Base58.encode(crypto.fastHash(message))))
    }
  }

  def transactionSerialize: Route =
    path("transactionSerialize")(jsonPost[JsObject] { jsv =>
      parseOrCreateTransaction(jsv)(tx => Json.obj("bytes" -> tx.bodyBytes().map(_.toInt & 0xff)))
    })
}

object UtilsApiRoute {
  val MaxSeedSize                 = 1024
  val DefaultSeedSize             = 32
  val DefaultPublicKey: PublicKey = PublicKey(ByteStr(new Array[Byte](32)))
  val DefaultAddress: Address     = DefaultPublicKey.toAddress
}
