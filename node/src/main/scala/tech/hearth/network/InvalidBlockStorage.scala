package tech.hearth.network

import com.google.common.cache.CacheBuilder
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.network.InvalidBlockStorageImpl.*
import tech.hearth.transaction.TxValidationError.BlockFromFuture

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

trait InvalidBlockStorage {
  def add(blockId: ByteStr, validationError: ValidationError): Unit

  def find(blockId: ByteStr): Option[ValidationError]
}

object InvalidBlockStorage {
  object NoOp extends InvalidBlockStorage {
    override def add(blockId: ByteStr, validationError: ValidationError): Unit = {}
    override def find(blockId: ByteStr): Option[ValidationError]               = None
  }
}

class InvalidBlockStorageImpl(settings: InvalidBlockStorageSettings) extends InvalidBlockStorage {
  private val cache = CacheBuilder
    .newBuilder()
    .expireAfterWrite(settings.timeout.toJava)
    .build[ByteStr, ValidationError]()

  override def add(blockId: ByteStr, validationError: ValidationError): Unit =
    validationError match {
      case _: BlockFromFuture => // ignore because it's a temporary error
      case _                  => cache.put(blockId, validationError)
    }

  override def find(blockId: ByteStr): Option[ValidationError] = Option(cache.getIfPresent(blockId))
}

object InvalidBlockStorageImpl {

  case class InvalidBlockStorageSettings(maxSize: Int, timeout: FiniteDuration)

}
