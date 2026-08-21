package tech.hearth.http

import tech.hearth.api.http.FinalityApiRoute
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.state.RegisteredEnclave
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.TxHelpers
import org.apache.pekko.http.scaladsl.model.StatusCodes.OK
import play.api.libs.json.{JsArray, JsObject, Json}

class FinalityApiRouteSpec extends RouteSpec("/blockchain/finality") with WithDomain {
  private val sender = TxHelpers.defaultSigner

  "RegisteredEnclave JSON" - {
    "carries the key as base16 and the two addresses as bech32, in their own slots" in {
      import FinalityApiRoute.given
      val validator = TxHelpers.signer(1).toAddress
      val operator  = TxHelpers.signer(2).toAddress
      val enclave   = RegisteredEnclave(ByteStr.fill(32)(1), validator, operator)
      Json.toJson(enclave) shouldBe Json.obj(
        "enclavePublicKey" -> Base16.encode(enclave.enclavePublicKey.arr),
        "validator"        -> validator.toBech32,
        "operator"         -> operator.toBech32
      )
    }
  }

  "GET /blockchain/finality" - {
    "reports the next period's committed generators and registered enclaves" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val route = seal(FinalityApiRoute(d.blockchain, d.blocksApi, d.generatorsApi).route)
      val next  = d.blockchain.currentGenerationPeriod.value.next
      d.appendBlock(TxHelpers.commitToGeneration(next.start, sender))

      Get(routePath("")) ~> route ~> check {
        status shouldBe OK
        val json = responseAs[JsObject]
        (json \ "height").as[Int] shouldBe d.blockchain.height
        (json \ "nextGenerationPeriod" \ "start").as[Int] shouldBe next.start.toInt
        (json \ "nextGenerators").as[JsArray].value.map(g => (g \ "address").as[String]) should contain(sender.toAddress.toString)
        // No unit test can register an enclave (it takes a fully verifiable quote, see StartBoostTransactionDiffTest),
        // so both registries are empty here; the keys still have to be present for clients to rely on them.
        (json \ "currentRegisteredEnclaves").as[JsArray].value shouldBe empty
        (json \ "nextRegisteredEnclaves").as[JsArray].value shouldBe empty
      }
    }
  }
}
