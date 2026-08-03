package tech.hearth.transaction.api.http.leasing

import tech.hearth.api.http.requests.{LeaseCancelRequest, LeaseRequest}
import tech.hearth.test.FunSuite
import play.api.libs.json.Json

class LeaseV1RequestsTests extends FunSuite {

  test("LeaseRequest") {
    val json =
      """
        {
         "senderPublicKey":"CRxqEuxhdZBEHX42MU4FfyJxuHmbDBTaHMhM3Uki7pLw",
         "recipient":"3MwKzMxUKaDaS4CXM8KNowCJJUnTSHDFGMb",
         "fee":1000000,
         "timestamp":0,
         "amount":100000,
         "signature":"4VPg4piLZGQz3vBqCPbjTfAR4cDErMi57rDvyith5XrQJDLryU2w2JsL3p4ejEqTPpctZ5YekpQwZPTtYiGo5yPC"
         }
      """

    val req = Json.parse(json).validate[LeaseRequest].get

    req.senderPublicKey shouldBe "CRxqEuxhdZBEHX42MU4FfyJxuHmbDBTaHMhM3Uki7pLw"
    req.recipient shouldBe "3MwKzMxUKaDaS4CXM8KNowCJJUnTSHDFGMb"
    req.amount shouldBe 100000L
    req.fee shouldBe 1000000L
  }

  test("LeaseCancelRequest") {
    val json =
      """
        {
         "senderPublicKey":"CRxqEuxhdZBEHX42MU4FfyJxuHmbDBTaHMhM3Uki7pLw",
         "txId":"D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5",
         "timestamp":0,
         "fee": 1000000,
         "signature":"4VPg4piLZGQz3vBqCPbjTfAR4cDErMi57rDvyith5XrQJDLryU2w2JsL3p4ejEqTPpctZ5YekpQwZPTtYiGo5yPC"
         }
      """

    val req = Json.parse(json).validate[LeaseCancelRequest].get

    req.senderPublicKey shouldBe "CRxqEuxhdZBEHX42MU4FfyJxuHmbDBTaHMhM3Uki7pLw"
    req.leaseId shouldBe "D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5"
    req.fee shouldBe 1000000L
  }
}
