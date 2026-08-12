package tech.hearth.generator.utils

import tech.hearth.account.{Address, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.KeyLength
import tech.hearth.generator.utils.Implicits.*
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.{Proofs, Transaction, TxNonNegativeAmount}
import tech.hearth.crypto.SigningKey

import java.util.concurrent.ThreadLocalRandom

object Gen {
  private def random = ThreadLocalRandom.current

  def txs(minFee: Long, maxFee: Long, senderAccounts: Seq[SigningKey], recipientGen: Iterator[Address]): Iterator[Transaction] = {
    val senderGen = Iterator.randomContinually(senderAccounts)
    val feeGen    = Iterator.continually(minFee + random.nextLong(maxFee - minFee))
    transfers(senderGen, recipientGen, feeGen)
  }

  def transfers(senderGen: Iterator[SigningKey], recipientGen: Iterator[Address], feeGen: Iterator[Long]): Iterator[Transaction] = {
    val now = System.currentTimeMillis()

    senderGen
      .zip(recipientGen)
      .zip(feeGen)
      .zipWithIndex
      .map { case (((src, dst), fee), i) =>
        TransferTransaction
          .create(
            PublicKey(src.publicKey()),
            Hearth,
            Seq(TransferTransaction.ParsedTransfer(dst, TxNonNegativeAmount.unsafeFrom(fee))),
            fee,
            now + i,
            ByteStr.empty,
            Proofs.empty
          )
          .map(_.signWith(src))
      }
      .collect { case Right(x) => x }
  }

  val address: Iterator[Address] = Iterator.continually {
    val pk = Array.fill[Byte](KeyLength)(random.nextInt(Byte.MaxValue).toByte)
    Address.fromPublicKey(PublicKey(pk))
  }

  def address(uniqNumber: Int): Iterator[Address] = Iterator.randomContinually(address.take(uniqNumber).toSeq)

  def address(limitUniqNumber: Option[Int]): Iterator[Address] = limitUniqNumber.map(address(_)).getOrElse(address)

}
