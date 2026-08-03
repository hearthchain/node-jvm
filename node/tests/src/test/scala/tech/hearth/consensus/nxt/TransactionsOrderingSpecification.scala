package tech.hearth.consensus.nxt

import tech.hearth.common.state.ByteStr
import tech.hearth.consensus.TransactionsOrdering
import tech.hearth.test.PropSpec
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.{Asset, TxHelpers}
import tech.hearth.crypto.SigningKey

import scala.util.Random

class TransactionsOrderingSpecification extends PropSpec {

  private val kp: SigningKey = TxHelpers.defaultSigner
  property("TransactionsOrdering.InBlock should sort correctly") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        125L,
        Waves,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Waves,
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Waves,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        1
      )
    )

    val sorted = Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InBlock)

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool should sort correctly") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Waves,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        123L,
        Waves,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        123L,
        Waves,
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        2
      )
    )

    val sorted = Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InUTXPool(Set.empty))

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InBlock should sort txs by decreasing block timestamp") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        1,
        Waves,
        ByteStr.empty,
        124L
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        1,
        Waves,
        ByteStr.empty,
        123L
      )
    )

    Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InBlock) shouldBe correctSeq
  }

}
