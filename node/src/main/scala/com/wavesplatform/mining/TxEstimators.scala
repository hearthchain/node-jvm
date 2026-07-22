package com.wavesplatform.mining

import com.wavesplatform.state.{Blockchain, StateSnapshot}
import com.wavesplatform.transaction.Transaction

//noinspection ScalaStyle
object TxEstimators {
  trait Fn {
    def apply(blockchain: Blockchain, transaction: Transaction, snapshot: StateSnapshot): Long
    def minEstimate: Long
  }

  case object sizeInBytes extends Fn {
    override def apply(blockchain: Blockchain, tx: Transaction, snapshot: StateSnapshot): Long =
      tx.bytesSize
    override val minEstimate = 109L
  }

  case object one extends Fn {
    override def apply(blockchain: Blockchain, tx: Transaction, snapshot: StateSnapshot): Long = 1
    override val minEstimate                                                                   = 1L
  }
}
