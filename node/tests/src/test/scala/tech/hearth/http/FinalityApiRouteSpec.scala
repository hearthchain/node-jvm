package tech.hearth.http

import tech.hearth.api.http.FinalityApiRoute
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.BlockchainSettings
import tech.hearth.state.{GenerationPeriod, RegisteredEnclave}
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.utils.EmptyBlockchain
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
        // Registering through a transaction needs a verifiable quote (StartBoostTransactionDiffTest); the route
        // mapping is covered below against a stub, here only the keys are pinned.
        (json \ "currentRegisteredEnclaves").as[JsArray].value shouldBe empty
        (json \ "nextRegisteredEnclaves").as[JsArray].value shouldBe empty
      }
    }

    "reads the current period's registry and the next period's registry from their own periods" in withDomain(
      DeterministicFinality
    ) { d =>
      val period  = d.blockchain.currentGenerationPeriod.value
      val current = RegisteredEnclave(ByteStr.fill(32)(1), TxHelpers.signer(1).toAddress, TxHelpers.signer(2).toAddress)
      val next    = RegisteredEnclave(ByteStr.fill(32)(2), TxHelpers.signer(3).toAddress, TxHelpers.signer(4).toAddress)
      val stub = new EmptyBlockchain {
        override lazy val settings: BlockchainSettings = d.blockchain.settings
        override def height: Int                       = d.blockchain.height
        override def registeredEnclaves(at: GenerationPeriod): IndexedSeq[RegisteredEnclave] =
          if (at == period) IndexedSeq(current) else if (at == period.next) IndexedSeq(next) else IndexedSeq.empty
      }
      val route = seal(FinalityApiRoute(stub, d.blocksApi, d.generatorsApi).route)

      Get(routePath("")) ~> route ~> check {
        status shouldBe OK
        val json = responseAs[JsObject]
        (json \ "currentRegisteredEnclaves").as[JsArray].value.map(e => (e \ "enclavePublicKey").as[String]) shouldBe
          Seq(Base16.encode(current.enclavePublicKey.arr))
        (json \ "nextRegisteredEnclaves").as[JsArray].value.map(e => (e \ "enclavePublicKey").as[String]) shouldBe
          Seq(Base16.encode(next.enclavePublicKey.arr))
      }
    }
  }
}
