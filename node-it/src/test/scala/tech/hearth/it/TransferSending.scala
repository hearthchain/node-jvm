package tech.hearth.it

import com.google.common.primitives.Ints
import com.typesafe.config.Config
import org.scalatest.Suite
import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.TransferSending.Req
import tech.hearth.it.api.AsyncHttpApi.*
import tech.hearth.it.api.{Transaction, UnexpectedStatusCodeException}
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.transfer.*
import tech.hearth.utils.ScorexLogging

import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.Future
import scala.util.Random

object TransferSending {
  case class Req(senderSeed: String, targetAddress: String, amount: Long, fee: Long)
}

trait TransferSending extends ScorexLogging {
  this: Suite & Nodes =>

  import scala.concurrent.ExecutionContext.Implicits.global

  def generateTransfersFromAccount(n: Int, accountAddress: String): Seq[Req] = {
    val fee      = 100000 + 400000 // + 400000 for scripted accounts
    val seedSize = 32

    val srcSeed = NodeConfigs.Default
      .collectFirst {
        case x if x.getString("address") == accountAddress => x.getString("account-seed")
      }
      .getOrElse(throw new RuntimeException(s"Can't find address '$accountAddress' in nodes.conf"))

    val sourceAndDest = (1 to n).map { _ =>
      val destPk = Array.fill[Byte](seedSize)(Random.nextInt(Byte.MaxValue).toByte)
      Address.fromPublicKey(PublicKey(destPk)).toString
    }

    val requests = sourceAndDest.foldLeft(List.empty[Req]) { case (rs, dstAddr) =>
      rs :+ Req(srcSeed, dstAddr, fee, fee)
    }

    requests
  }

  def generateTransfersBetweenAccounts(n: Int, balances: Map[Config, Long]): Seq[Req] = {
    val fee = 100000
    val srcDest = balances.toSeq
      .map { case (config, _) =>
        val accountSeed = config.getString("account-seed")
        (config, keyPairFromSeed(Base16.decode(accountSeed)))
      }

    val sourceAndDest = (1 to n).map { _ =>
      val Seq((srcConfig, _), (_, destPrivateKey)) = (Random.shuffle(srcDest).take(2): @unchecked)
      (srcConfig, destPrivateKey.toAddress.toString)
    }

    val requests = sourceAndDest.foldLeft(List.empty[Req]) { case (rs, (srcConfig, destAddr)) =>
      val a              = Random.nextDouble()
      val b              = balances(srcConfig)
      val transferAmount = (1e-8 + a * 1e-9 * b).toLong
      if (transferAmount < 0) log.warn(s"Negative amount: (1e-8 + $a * 1e-8 * $b) = $transferAmount")
      rs :+ Req(srcConfig.getString("account-seed"), destAddr, Math.max(transferAmount, 1L), fee)
    }

    requests
  }

  def generateTransfersToRandomAddresses(n: Int, excludeSrcAddresses: Set[String]): Seq[Req] = {
    val fee = 100000

    val seeds = NodeConfigs.Default.collect {
      case config if !excludeSrcAddresses.contains(config.getString("address")) => config.getString("account-seed")
    }

    val prefix = Ints.toByteArray(Random.nextInt())

    val sourceAndDest = (1 to n).map { id =>
      val srcSeed  = Random.shuffle(seeds).head
      val destPk   = prefix ++ Ints.toByteArray(id) ++ new Array[Byte](24)
      val destAddr = Address.fromPublicKey(PublicKey(destPk)).toString

      (srcSeed, destAddr)
    }
    val requests = sourceAndDest.foldLeft(List.empty[Req]) { case (rs, (srcSeed, dstAddr)) =>
      rs :+ Req(srcSeed, dstAddr, fee, fee)
    }

    requests
  }

  def balanceForNode(n: Node): Future[(String, Long)] = n.balance(n.address).map(b => n.address -> b.balance)

  def processRequests(requests: Seq[Req], includeAttachment: Boolean = false): Future[Seq[Transaction]] = {
    val start = System.currentTimeMillis() - requests.size
    val signedTransfers = requests.zipWithIndex
      .map { case (x, i) =>
        TxHelpers.transfer(
          keyPairFromSeed(Base16.decode(x.senderSeed)),
          Address.fromString(x.targetAddress).explicitGet(),
          x.amount,
          Hearth,
          x.fee,
          Hearth,
          if (includeAttachment)
            ByteStr(Array.fill(TransferTransaction.MaxAttachmentSize)(ThreadLocalRandom.current().nextInt().toByte))
          else ByteStr.empty,
          timestamp = start + i
        )
      }

    Future.sequence(signedTransfers.zip(Iterator.continually(nodes).flatten).map { case (tx, node) =>
      node.signedBroadcast(tx.json()).recoverWith {
        case u: UnexpectedStatusCodeException if u.responseBody.contains("already in the state") =>
          Future.successful(tx.json().as[Transaction])
      }
    })
  }
}
