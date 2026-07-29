package com.wavesplatform

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.{Block, MicroBlock}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.*
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.transfer.*
import monix.execution.schedulers.SchedulerService
import monix.execution.{Ack, Scheduler}
import monix.reactive.Observer
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.hearth.crypto.SigningKey

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

trait RxScheduler extends BeforeAndAfterAll { suite: Suite =>
  implicit val implicitScheduler: SchedulerService = Scheduler.singleThread("rx-scheduler")

  def testSchedulerName: String
  lazy val testScheduler: SchedulerService = Scheduler.singleThread(testSchedulerName)

  def test[A](f: => Future[A]): A = Await.result(f, 10.seconds)

  def send[A](p: Observer[A], timeout: Int = 500)(a: A): Future[Ack] =
    p.onNext(a)
      .map(ack => {
        Thread.sleep(timeout)
        ack
      })

  def ref(id: Int): ByteStr = ByteStr(Array.concat(Array.fill(DigestLength - 1)(0), Array(id.toByte)))
  def sig(id: Int): ByteStr = ByteStr(Array.concat(Array.fill(SignatureLength - 1)(0), Array(id.toByte)))

  val signer: SigningKey = TestBlock.defaultSigner

  def block(id: Int): Block = TestBlock.create(Seq.empty).block.copy(signature = sig(id))

  def microBlock(total: Int, prev: Int): MicroBlock = {
    val tx = TransferTransaction.create(PublicKey(signer.publicKey), signer.toAddress, Waves, 1, Waves, 1, ByteStr.empty, 1, Proofs.empty).map(_.signWith(signer)).explicitGet()
    MicroBlock.buildAndSign(signer, Seq(tx), ref(prev), sig(total), None, None).explicitGet()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    implicitScheduler.shutdown()
    testScheduler.shutdown()
  }
}
