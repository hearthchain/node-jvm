package com.wavesplatform

import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.block.{Block, MicroBlock}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.history.DefaultBaseTarget
import com.wavesplatform.protobuf.block.PBBlocks
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.{Transaction, TxHelpers}
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

trait BlocksTransactionsHelpers { self: TransactionGen =>
  object QuickTX {
    val FeeAmount = 400000

    def transfer(
        from: SigningKey,
        to: Address = accountGen.sample.get.toAddress,
        amount: Long = smallFeeGen.sample.get,
        timestamp: Gen[Long] = timestampGen
    ): Gen[Transaction] =
      for {
        timestamp <- timestamp
      } yield TxHelpers.transfer(from, to, amount, Waves, FeeAmount, Waves, ByteStr.empty, timestamp, 1.toByte)

    def transferV2(
        from: SigningKey,
        to: Address = accountGen.sample.get.toAddress,
        amount: Long = smallFeeGen.sample.get,
        timestamp: Gen[Long] = timestampGen
    ): Gen[Transaction] =
      for {
        timestamp <- timestamp
      } yield TxHelpers.transfer(from, to, amount, Waves, FeeAmount, Waves, ByteStr.empty, timestamp, 2.toByte)

    def transferAsset(
        asset: IssuedAsset,
        from: SigningKey,
        to: Address = accountGen.sample.get.toAddress,
        amount: Long = smallFeeGen.sample.get,
        timestamp: Gen[Long] = timestampGen
    ): Gen[Transaction] =
      for {
        timestamp <- timestamp
      } yield TxHelpers.transfer(from, to, amount, asset, FeeAmount, Waves, ByteStr.empty, timestamp, 1.toByte)

    def lease(
        from: SigningKey,
        to: Address = accountGen.sample.get.toAddress,
        amount: Long = smallFeeGen.sample.get,
        timestamp: Gen[Long] = timestampGen
    ): Gen[LeaseTransaction] =
      for {
        timestamp <- timestamp
      } yield TxHelpers.lease(from, to, amount, FeeAmount, timestamp, 1.toByte)

    def leaseCancel(from: SigningKey, leaseId: ByteStr, timestamp: Gen[Long] = timestampGen): Gen[LeaseCancelTransaction] =
      for {
        timestamp <- timestamp
      } yield TxHelpers.leaseCancel(leaseId, from, FeeAmount, timestamp, 1.toByte)
  }

  object UnsafeBlocks {
    def unsafeChainBaseAndMicro(
        totalRefTo: ByteStr,
        base: Seq[Transaction],
        micros: Seq[Seq[Transaction]],
        signer: SigningKey,
        timestamp: Long
    ): (Block, Seq[MicroBlock]) = {
      val block = unsafeBlock(totalRefTo, base, signer, timestamp)
      val microBlocks = micros
        .foldLeft((block, Seq.empty[MicroBlock])) { case ((lastTotal, allMicros), txs) =>
          val (newTotal, micro) = unsafeMicro(totalRefTo, lastTotal, txs, signer, timestamp)
          (newTotal, allMicros :+ micro)
        }
        ._2
      (block, microBlocks)
    }

    def unsafeMicro(
        totalRefTo: ByteStr,
        prevTotal: Block,
        txs: Seq[Transaction],
        signer: SigningKey,
        ts: Long
    ): (Block, MicroBlock) = {
      val newTotalBlock = unsafeBlock(totalRefTo, prevTotal.transactionData ++ txs, signer, ts)
      (newTotalBlock, MicroBlock.buildAndSign(signer, txs, prevTotal.id(), newTotalBlock.signature, None, None).explicitGet())
    }

    def unsafeBlock(
        reference: ByteStr,
        txs: Seq[Transaction],
        signer: SigningKey,
        timestamp: Long,
        bTarget: Long = DefaultBaseTarget
    ): Block = {
      val unsigned: Block = Block.create(
        timestamp = timestamp,
        reference = reference,
        baseTarget = bTarget,
        generationSignature = com.wavesplatform.history.generationSignature,
        generator = PublicKey(signer.publicKey),
        featureVotes = Seq.empty,
        transactionData = txs,
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = None
      )
      val toSign = PBBlocks.protobuf(unsigned).header.get.toByteArray
      unsigned.copy(signature = ByteStr(signer.sign(toSign)))
    }
  }
}
