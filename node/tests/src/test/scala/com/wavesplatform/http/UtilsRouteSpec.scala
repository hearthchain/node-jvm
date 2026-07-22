package com.wavesplatform.http

import com.wavesplatform.api.http.ApiError.TooBigArrayAllocation
import com.wavesplatform.api.http.utils.UtilsApiRoute
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.crypto
import com.wavesplatform.utils.{EmptyBlockchain, Schedulers, Time}
import io.netty.util.HashedWheelTimer
import monix.execution.schedulers.SchedulerService
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout
import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks as PropertyChecks
import play.api.libs.json.*

import scala.concurrent.duration.*

class UtilsRouteSpec extends RouteSpec("/utils"), RestAPISettingsHelper, PropertyChecks {
  protected override implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(20.seconds)

  private val timeBounded: SchedulerService = Schedulers.timeBoundedFixedPool(
    new HashedWheelTimer(),
    5.seconds,
    1,
    "rest-time-limited"
  )
  private val utilsApi: UtilsApiRoute = UtilsApiRoute(
    Time.SystemTime,
    restAPISettings,
    Int.MaxValue,
    timeBounded,
    EmptyBlockchain
  )

  override def afterAll(): Unit = {
    timeBounded.shutdown()
    super.afterAll()
  }

  private val route = seal(utilsApi.route)

  routePath("/seed") in {
    Get(routePath("/seed")) ~> route ~> check {
      val seed = Base58.tryDecodeWithLimit((responseAs[JsValue] \ "seed").as[String])
      seed.get.length shouldEqual UtilsApiRoute.DefaultSeedSize
    }
  }

  routePath("/seed/{length}") in forAll(Gen.posNum[Int]) { l =>
    if (l > UtilsApiRoute.MaxSeedSize) {
      Get(routePath(s"/seed/$l")) ~> route should produce(TooBigArrayAllocation)
    } else {
      Get(routePath(s"/seed/$l")) ~> route ~> check {
        val seed = Base58.tryDecodeWithLimit((responseAs[JsValue] \ "seed").as[String])
        seed.get.length shouldEqual l
      }
    }
  }

  for (
    (hash, f) <- Seq[(String, String => Array[Byte])](
      "secure" -> crypto.secureHash,
      "fast"   -> crypto.fastHash
    )
  ) {
    val uri = routePath(s"/hash/$hash")
    uri in {
      forAll(Gen.alphaNumStr) { s =>
        Post(uri, s) ~> route ~> check {
          val r = responseAs[JsObject]
          (r \ "message").as[String] shouldEqual s
          (r \ "hash").as[String] shouldEqual Base58.encode(f(s))
        }
      }
    }
  }
}
