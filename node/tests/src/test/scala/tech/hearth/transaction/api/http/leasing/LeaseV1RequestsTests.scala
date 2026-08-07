package tech.hearth.transaction.api.http.leasing

import tech.hearth.api.http.requests.{LeaseCancelRequest, LeaseRequest}
import tech.hearth.test.FunSuite
import play.api.libs.json.Json

class LeaseV1RequestsTests extends FunSuite {

  test("LeaseRequest") {
    val json =
      """
        {
         "senderPublicKey":"a9d59feec551438cc7437e39cd75328bc0c345bfc8fc918843c2548772ba2640",
         "recipient":"3MwKzMxUKaDaS4CXM8KNowCJJUnTSHDFGMb",
         "fee":1000000,
         "timestamp":0,
         "amount":100000,
         "signature":"ae88c496940702036cb4aab78d3c9dcd035de08abc5e1820ad6767c390bcd9f07435d2481cc350a2145aefd2f5ec0f5c77234624b59025170734111cfe3d5f87"
         }
      """

    val req = Json.parse(json).validate[LeaseRequest].get

    req.senderPublicKey shouldBe "a9d59feec551438cc7437e39cd75328bc0c345bfc8fc918843c2548772ba2640"
    req.recipient shouldBe "3MwKzMxUKaDaS4CXM8KNowCJJUnTSHDFGMb"
    req.amount shouldBe 100000L
    req.fee shouldBe 1000000L
  }

  test("LeaseCancelRequest") {
    val json =
      """
        {
         "senderPublicKey":"a9d59feec551438cc7437e39cd75328bc0c345bfc8fc918843c2548772ba2640",
         "txId":"b3a719f716d83848cfc8953a98597aeb65975fba7b83bc85fd33805acc1b871c",
         "timestamp":0,
         "fee": 1000000,
         "signature":"ae88c496940702036cb4aab78d3c9dcd035de08abc5e1820ad6767c390bcd9f07435d2481cc350a2145aefd2f5ec0f5c77234624b59025170734111cfe3d5f87"
         }
      """

    val req = Json.parse(json).validate[LeaseCancelRequest].get

    req.senderPublicKey shouldBe "a9d59feec551438cc7437e39cd75328bc0c345bfc8fc918843c2548772ba2640"
    req.leaseId shouldBe "b3a719f716d83848cfc8953a98597aeb65975fba7b83bc85fd33805acc1b871c"
    req.fee shouldBe 1000000L
  }
}
