package com.wavesplatform.consensus.nxt

import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.consensus.TransactionsOrdering
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.{Asset, TxHelpers}
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.transfer.*
import tech.hearth.crypto.SigningKey

import scala.util.Random

class TransactionsOrderingSpecification extends PropSpec {

  private val kp: SigningKey = TxHelpers.defaultSigner
  property("TransactionsOrdering.InBlock should sort correctly") {
    val correctSeq = Seq(
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          100000,
          Waves,
          125L,
          Waves,
          ByteStr.empty,
          1
        ),
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          100000,
          Waves,
          124L,
          Waves,
          ByteStr.empty,
          2
        ),
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          100000,
          Waves,
          124L,
          Waves,
          ByteStr.empty,
          1
        ),
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          100000,
          Waves,
          124L,
          Asset.fromCompatId(Some(ByteStr.empty)),
          ByteStr.empty,
          2
        ),
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
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
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          100000,
          Waves,
          124L,
          Waves,
          ByteStr.empty,
          1
        ),
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          100000,
          Waves,
          123L,
          Waves,
          ByteStr.empty,
          1
        ),
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          100000,
          Waves,
          123L,
          Waves,
          ByteStr.empty,
          2
        ),
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          100000,
          Waves,
          124L,
          Asset.fromCompatId(Some(ByteStr.empty)),
          ByteStr.empty,
          1
        ),
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
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
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          100000,
          Waves,
          1,
          Waves,
          ByteStr.empty,
          124L
        ),
      TxHelpers.transfer(
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
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
