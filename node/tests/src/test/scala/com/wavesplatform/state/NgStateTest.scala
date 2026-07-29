package com.wavesplatform.state

import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.explicitGet
import com.wavesplatform.history.*
import com.wavesplatform.test.*
import com.wavesplatform.transaction.transfer.*
import com.wavesplatform.transaction.TxHelpers

class NgStateTest extends PropSpec {
  private def wavesFee(amount: Long): BlockFee = BlockFee(Portfolio.waves(amount)).explicitGet()

  // NgState is not validated against any state here, so the base block just needs some transaction in it
  private def preconditionsAndPayments(amt: Int): (TransferTransaction, Seq[TransferTransaction]) = {
    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    val baseTx   = TxHelpers.transfer(master, recipient.toAddress, 1)
    val payments = (1 to amt).map(idx => TxHelpers.transfer(master, recipient.toAddress, idx))

    (baseTx, payments)
  }

  private def mkNgState(block: Block): NgState = NgState(
    block,
    StateSnapshot.empty,
    baseBlockCarry = BlockFee.empty,
    baseBlockTotalFee = BlockFee.empty,
    baseBlockComputedStateHash = ByteStr.empty,
    approvedFeatures = Set.empty,
    reward = None,
    hitSource = block.header.generationSignature,
    leasesToCancel = Map.empty,
    FinalizationState.notActivated(block)
  )

  property("can forge correctly signed blocks") {
    val (baseTx, payments)   = preconditionsAndPayments(10)
    val (block, microBlocks) = chainBaseAndMicro(randomSig, baseTx, payments.map(t => Seq(t)))

    var ng = mkNgState(block)
    microBlocks.foreach(m => ng = ng.append(m, StateSnapshot.empty, BlockFee.empty, BlockFee.empty, 0L, ByteStr.empty, None, Seq.empty))

    ng.liquidBlockOf(microBlocks.last.wholeBlockSignature)
    microBlocks.foreach { m =>
      val r = ng.liquidBlockOf(m.totalBlockId).get
      r.block.signatureValid() shouldBe true
    }
    Seq(microBlocks(4)).foreach { x =>
      ng.liquidBlockOf(x.totalBlockId) shouldBe defined
    }
  }

  property("can resolve best liquid block") {
    val (baseTx, payments)   = preconditionsAndPayments(5)
    val (block, microBlocks) = chainBaseAndMicro(randomSig, baseTx, payments.map(t => Seq(t)))

    var ng = mkNgState(block)
    microBlocks.foreach(m => ng = ng.append(m, StateSnapshot.empty, BlockFee.empty, BlockFee.empty, 0L, ByteStr.empty, None, Seq.empty))

    ng.bestLiquidBlock.id() shouldBe microBlocks.last.totalBlockId
    mkNgState(block).bestLiquidBlock.id() shouldBe block.id()
  }

  property("can resolve best last block") {
    val (baseTx, payments)   = preconditionsAndPayments(5)
    val (block, microBlocks) = chainBaseAndMicro(randomSig, baseTx, payments.map(t => Seq(t)))

    var ng = mkNgState(block)

    microBlocks.foldLeft(1000) { case (thisTime, m) =>
      ng = ng.append(m, StateSnapshot.empty, BlockFee.empty, BlockFee.empty, thisTime, ByteStr.empty, None, Seq.empty)
      thisTime + 50
    }

    ng.bestLastBlockInfo(0).blockId shouldBe block.id()
    ng.bestLastBlockInfo(1001).blockId shouldBe microBlocks.head.totalBlockId
    ng.bestLastBlockInfo(1051).blockId shouldBe microBlocks.tail.head.totalBlockId
    ng.bestLastBlockInfo(2000).blockId shouldBe microBlocks.last.totalBlockId

    mkNgState(block).bestLiquidBlock.id() shouldBe block.id()
  }

  property("calculates carry fee correctly") {
    val (baseTx, payments)   = preconditionsAndPayments(5)
    val (block, microBlocks) = chainBaseAndMicro(randomSig, baseTx, payments.map(t => Seq(t)))

    var ng = mkNgState(block)
    microBlocks.foreach(m => ng = ng.append(m, StateSnapshot.empty, wavesFee(1), BlockFee.empty, 0L, ByteStr.empty, None, Seq.empty))

    ng.liquidBlockOf(block.id()).map(_.data.carryFee) shouldBe Some(BlockFee.empty)
    microBlocks.zipWithIndex.foreach { case (m, i) =>
      val u = ng.liquidBlockOf(m.totalBlockId).map(_.data.carryFee)
      u shouldBe Some(wavesFee(i + 1L))
    }
    ng.carryFee shouldBe wavesFee(microBlocks.size.toLong)
  }
}
