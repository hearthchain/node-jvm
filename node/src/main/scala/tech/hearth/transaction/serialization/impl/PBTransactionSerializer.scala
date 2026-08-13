package tech.hearth.transaction.serialization.impl

import cats.syntax.applicativeError.*
import tech.hearth.common.utils.EitherExt2.explicitGet
import tech.hearth.protobuf.transaction.{PBTransactions, SignedTransaction as PBSignedTransaction}
import tech.hearth.protobuf.utils.PBUtils
import tech.hearth.transaction.{PBParsingError, Transaction}

import scala.util.Try

object PBTransactionSerializer {
  def bodyBytes(tx: Transaction): Array[Byte] =
    PBUtils.encodeDeterministic(PBTransactions.protobuf(tx).getTransaction)

  def bytes(tx: Transaction): Array[Byte] =
    PBUtils.encodeDeterministic(PBTransactions.protobuf(tx))

  def parseBytes(bytes: Array[Byte]): Try[Transaction] =
    PBSignedTransaction
      .validate(bytes)
      .adaptErr { case err => PBParsingError(err) }
      .flatMap(x => Try(PBTransactions.vanilla(x).explicitGet()))
}
