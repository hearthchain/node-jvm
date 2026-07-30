package com.wavesplatform.it

import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.test.NumericExt
import com.wavesplatform.transaction.transfer.*
import org.scalatest.*
import tech.hearth.crypto.SigningKey

trait IntegrationSuiteWithThreeAddresses extends BaseSuite {
  this: TestSuite & Nodes =>

  protected lazy val firstKeyPair: SigningKey = keyPairFromSeed("firstKeyPair".getBytes())
  protected lazy val firstAddress: String     = firstKeyPair.toAddress.toString

  protected lazy val secondKeyPair: SigningKey = keyPairFromSeed("secondKeyPair".getBytes())
  protected lazy val secondAddress: String     = secondKeyPair.toAddress.toString

  protected lazy val thirdKeyPair: SigningKey = keyPairFromSeed("thirdKeyPair".getBytes())
  protected lazy val thirdAddress: String     = thirdKeyPair.toAddress.toString

  abstract protected override def beforeAll(): Unit = {
    super.beforeAll()

    withClue("beforeAll") {
      nodes.waitForHeightAriseAndTxPresent(
        sender
          .massTransfer(
            sender.keyPair,
            List(firstAddress, secondAddress, thirdAddress).map(MassTransferTransaction.Transfer(_, 100.waves)),
            0.003.waves
          )
          .id
      )
    }
  }
}
