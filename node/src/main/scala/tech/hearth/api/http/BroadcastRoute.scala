package tech.hearth.api.http

import org.apache.pekko.http.scaladsl.marshalling.{ToResponseMarshallable, ToResponseMarshaller}
import org.apache.pekko.http.scaladsl.model.MediaType
import org.apache.pekko.http.scaladsl.server.{Directive1, Route}
import org.apache.pekko.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import tech.hearth.lang.ValidationError
import tech.hearth.network.TransactionPublisher
import tech.hearth.transaction.Transaction
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.serialization.impl.PBTransactionSerializer
import tech.hearth.transaction.smart.script.trace.TracedResult
import play.api.libs.json.*

import scala.concurrent.Future

object BroadcastRoute {

  /** Protobuf has no IANA-registered media type; `application/x-protobuf` is the de facto spelling, `application/protobuf` the common variant. */
  val `application/x-protobuf`: MediaType.Binary = MediaType.applicationBinary("x-protobuf", MediaType.NotCompressible)
  val `application/protobuf`: MediaType.Binary   = MediaType.applicationBinary("protobuf", MediaType.NotCompressible)
}

trait BroadcastRoute { apiRoute: ApiRoute =>
  import BroadcastRoute.*

  def transactionPublisher: TransactionPublisher

  private def broadcastTransaction(tx: Transaction): Future[ToResponseMarshallable] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val trw: ToResponseMarshaller[TracedResult[ApiError, Transaction]] = tracedResultMarshaller
    transactionPublisher.validateAndBroadcast(tx, None).map(_.leftMap(ApiError.fromValidationError).map(_ => tx))
  }

  private def completeBroadcast[A](entity: Directive1[A])(f: A => Either[ValidationError, Transaction]): Route = {
    val directive = entity.flatMap { a =>
      f(a).fold(
        e => provide[ToResponseMarshallable](ApiError.fromValidationError(e)),
        tx => provide[ToResponseMarshallable](broadcastTransaction(tx))
      )
    }
    directive(complete(_))
  }

  def broadcast[A: Reads](f: A => Either[ValidationError, Transaction]): Route =
    completeBroadcast(jsonPostD[A])(f)

  /** Broadcasts a protobuf-encoded `SignedTransaction`; rejects requests carrying any other content type, so it composes with [[broadcast]]. */
  def broadcastProtobuf: Route = {
    implicit val protobufTransaction: FromEntityUnmarshaller[Either[ValidationError, Transaction]] =
      Unmarshaller.byteStringUnmarshaller
        .forContentTypes(`application/x-protobuf`, `application/protobuf`)
        .map(bytes => PBTransactionSerializer.parseBytes(bytes.toArray).toEither.left.map(e => GenericError(e.getMessage)))

    completeBroadcast(post & entity(as[Either[ValidationError, Transaction]]))(identity)
  }
}
