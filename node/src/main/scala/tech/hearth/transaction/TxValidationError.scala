package tech.hearth.transaction

import tech.hearth.account.{Address, NetworkId}
import tech.hearth.block.{Block, MicroBlock}
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.state.Height
import tech.hearth.transaction.assets.exchange.Order

object TxValidationError {
  type Validation[T] = Either[ValidationError, T]

  case class InvalidAddress(reason: String)                     extends ValidationError
  case class NegativeAmount(amount: Long, of: String)           extends ValidationError
  case class NonPositiveAmount(amount: Long, of: String)        extends ValidationError
  case class InvalidDecimals(decimals: Byte)                    extends ValidationError
  case class NegativeMinFee(minFee: Long, of: String)           extends ValidationError
  case object InsufficientFee                                   extends ValidationError
  case object TooBigArray                                       extends ValidationError
  case class TooBigInBytes(err: String)                         extends ValidationError
  case object InvalidName                                       extends ValidationError
  case object InvalidAssetId                                    extends ValidationError
  case object OverflowError                                     extends ValidationError
  case object ToSelf                                            extends ValidationError
  case object MissingSenderPrivateKey                           extends ValidationError
  case object UnsupportedTransactionType                        extends ValidationError
  case object InvalidRequestSignature                           extends ValidationError
  case class BlockFromFuture(blockTs: Long, localTs: Long)      extends ValidationError
  case class AlreadyInTheState(txId: ByteStr, txHeight: Height) extends ValidationError
  case class AccountBalanceError(errs: Map[Address, String])    extends ValidationError
  case class OrderValidationError(order: Order, err: String)    extends ValidationError
  case class SenderIsBlacklisted(addr: String)                  extends ValidationError
  case class Mistiming(err: String)                             extends ValidationError
  case class BlockAppendError(err: String, b: Block)            extends ValidationError
  case class ActivationError(err: String)                       extends ValidationError
  case class GenericError(err: String)                          extends ValidationError

  object GenericError {
    def apply(ex: Throwable): GenericError = new GenericError(ex.getMessage)
  }

  case class InvalidSignature(entity: Signed, details: Option[InvalidSignature] = None) extends ValidationError {
    override def toString: String = s"InvalidSignature(${entity.toString + " reason: " + details})"
  }

  case class InvalidStateHash(blockStateHash: Option[ByteStr], computedStateHash: Option[ByteStr]) extends ValidationError

  case class MicroBlockAppendError(err: String, microBlock: MicroBlock) extends ValidationError {
    override def toString: String = s"MicroBlockAppendError($err, ${microBlock.wholeBlockSignature} ~> ${microBlock.reference.trim}])"
  }

  case object EmptyDataKey extends ValidationError {
    override def toString: String = "Empty key found"
  }

  case object DuplicatedDataKeys extends ValidationError {
    override def toString: String = s"Duplicated keys found"
  }

  case class WrongChain(expected: NetworkId, provided: NetworkId) extends ValidationError {
    override def toString: String = s"Wrong chain-id. Expected - $expected, provided - $provided"
  }

  case class UnsupportedTypeAndVersion(typeId: Byte, version: Int) extends ValidationError {
    override def toString: String = s"Bad transaction type ($typeId) and version ($version)"
  }

  case class UsupportedProofVersion(version: Int, supported: List[Int]) extends ValidationError {
    override def toString: String = s"Unsupported proofs version - $version. Expected one of ${supported.mkString("[", ", ", "]")}"
  }

  case class TooManyProofs(max: Int, actual: Int) extends ValidationError {
    override def toString: String = s"Too many proofs ($actual), only $max allowed"
  }

  case class ToBigProof(max: Int, actual: Int) extends ValidationError {
    override def toString: String = s"Too large proof ($actual), must be max $max bytes"
  }
}
