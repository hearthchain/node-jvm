package tech.hearth.http

import tech.hearth.test.FreeSpec
import tech.hearth.transaction.TransactionType

import scala.io.Source
import scala.util.Using

/** The served OpenAPI document (`node/src/main/resources/api-docs/openapi.yaml`, see `docs/notes/api-docs.md`) is
  * hand-maintained, and nothing else in the build reads it, so a bound that falls out of step with the code is
  * invisible until a generated client rejects a valid transaction.
  *
  * `TransactionBase.type`'s `maximum` was stale exactly that way when TransactionType.Stake was added, which is why
  * this exists rather than a one-off correction: the bound is now derived from the enum on every test run.
  */
class OpenApiSpecSpec extends FreeSpec {
  private val TypeBound = """        type:
          type: integer
          minimum: 1
          maximum: (\d+)
          description: Transaction type id""".r

  private val spec: String =
    Using.resource(Source.fromResource("api-docs/openapi.yaml"))(_.mkString)

  "openapi.yaml" - {
    "bounds TransactionBase.type by the highest transaction type id there is" in {
      val declared = TypeBound
        .findFirstMatchIn(spec)
        .getOrElse(fail("TransactionBase.type's integer bounds are not where this test expects them in openapi.yaml"))
        .group(1)
        .toInt

      declared shouldBe TransactionType.values.map(_.id).max
    }

    "documents a schema for every transaction type" in {
      TransactionType.values.foreach { tpe =>
        withClue(s"${tpe.transactionName} is missing from openapi.yaml: ") {
          spec should include(s"    ${tpe.transactionName}:")
        }
      }
    }
  }
}
