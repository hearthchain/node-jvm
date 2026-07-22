package com.wavesplatform.history

import com.wavesplatform.*
import com.wavesplatform.block.{Block, MicroBlock}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.Domain.BlockchainUpdaterExt
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.diffs.ENOUGH_AMT
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.transaction.{Transaction, TxHelpers}
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterLiquidBlockTest extends PropSpec with DomainScenarioDrivenPropertyCheck with BlocksTransactionsHelpers {
  import UnsafeBlocks.*

  // The rich account is credited by the genesis snapshot, so the key block is built on the domain's genesis block.
  // The blocks themselves can only be built once the domain exists, because they reference that genesis block.
  private def preconditionsAndPayments(minTx: Int, maxTx: Int): Gen[(SigningKey, Seq[Transaction], Int)] =
    for {
      richAccount        <- accountGen
      totalTxNumber      <- Gen.chooseNum(minTx, maxTx)
      txNumberInKeyBlock <- Gen.chooseNum(0, Block.MaxTransactionsPerBlockVer3)
    } yield {
      val allTxs = Seq.fill(totalTxNumber)(TxHelpers.transfer(richAccount, version = 1.toByte))
      (richAccount, allTxs, txNumberInKeyBlock)
    }

  private def mkKeyBlockAndMicros(reference: ByteStr, allTxs: Seq[Transaction], txNumberInKeyBlock: Int): (Block, Seq[MicroBlock]) = {
    val (keyBlockTxs, microTxs) = allTxs.splitAt(txNumberInKeyBlock)
    val txNumberInMicros        = allTxs.size - txNumberInKeyBlock

    unsafeChainBaseAndMicro(
      totalRefTo = reference,
      base = keyBlockTxs,
      micros = microTxs.grouped((txNumberInMicros / 5) min 500 max 1).toSeq,
      signer = TestBlock.defaultSigner,
      version = 3,
      timestamp = TxHelpers.timestamp
    )
  }

  property("liquid block can't be overfilled") {
    import Block.MaxTransactionsPerBlockVer3 as Max
    forAll(preconditionsAndPayments(Max + 1, Max + 100)) { case (richAccount, allTxs, txNumberInKeyBlock) =>
      withDomain(MicroblocksActivatedAt0WavesSettings, Seq(AddrWithBalance(richAccount.toAddress, ENOUGH_AMT))) { d =>
        val (keyBlock, microBlocks) = mkKeyBlockAndMicros(d.lastBlockId, allTxs, txNumberInKeyBlock)

        val blocksApplied = d.blockchainUpdater.processBlock(keyBlock).map(_ => ())

        val r = microBlocks.foldLeft(blocksApplied) {
          case (Right(_), curr) => d.blockchainUpdater.processMicroBlock(curr, None).map(_ => ())
          case (x, _)           => x
        }

        withClue("All microblocks should not be processed") {
          r match {
            case Left(e: GenericError) => e.err should include("Limit of txs was reached")
            case x =>
              val txNumberByMicroBlock = microBlocks.map(_.transactionData.size)
              fail(
                s"Unexpected result: $x. keyblock txs: ${keyBlock.transactionData.length}, " +
                  s"microblock txs: ${txNumberByMicroBlock.mkString(", ")} (total: ${txNumberByMicroBlock.sum}), " +
                  s"total txs: ${keyBlock.transactionData.length + txNumberByMicroBlock.sum}"
              )
          }
        }
      }
    }
  }

  property("miner settings don't interfere with micro block processing") {
    val oneTxPerMicroSettings: WavesSettings = MicroblocksActivatedAt0WavesSettings
      .copy(
        minerSettings = MicroblocksActivatedAt0WavesSettings.minerSettings.copy(
          maxTransactionsInMicroBlock = 1
        )
      )
    forAll(preconditionsAndPayments(10, Block.MaxTransactionsPerBlockVer3)) { case (richAccount, allTxs, txNumberInKeyBlock) =>
      withDomain(oneTxPerMicroSettings, Seq(AddrWithBalance(richAccount.toAddress, ENOUGH_AMT))) { d =>
        val (keyBlock, microBlocks) = mkKeyBlockAndMicros(d.lastBlockId, allTxs, txNumberInKeyBlock)
        d.blockchainUpdater.processBlock(keyBlock)
        microBlocks.foreach { mb =>
          d.blockchainUpdater.processMicroBlock(mb, None) should beRight
        }
      }
    }
  }
}
