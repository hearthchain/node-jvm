package com.wavesplatform.mining

import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.DigestLength
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.mining.MultiDimensionalMiningConstraint.Unlimited
import com.wavesplatform.mining.microblocks.MicroBlockMinerImpl
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.TxHelpers.{defaultSigner, secondSigner, transfer}
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable

import scala.concurrent.duration.DurationInt

class LightNodeBlockFieldsTest extends PropSpec with WithMiner {
  private val invalidStateHash = Some(Some(ByteStr.fill(DigestLength)(1)))

  property("micro forks should not produce invalid state hash") {
    val settings = TransactionStateSnapshot
      .copy(minerSettings = TransactionStateSnapshot.minerSettings.copy(quorum = 0, minMicroBlockAge = 0.seconds))

    // Sends transfers, forges blocks and micro blocks - so it has to be a committed generator, which is what
    // minerAccounts arranges: the index names the very key the genesis snapshot commits
    val signerIndex = 2
    val signer      = TxHelpers.signer(signerIndex)
    withDomainAndMiner(
      settings,
      AddrWithBalance.enoughBalances(signer),
      minerAccounts = Seq(signerIndex),
      verify = false,
      timeDrift = Int.MaxValue
    ) { case (d, miner, append) =>
      val microBlockMiner = new MicroBlockMinerImpl(
        _ => (),
        null,
        d.blockchainUpdater,
        d.utxPool,
        d.endorsementStorage,
        d.settings.minerSettings,
        miner.minerScheduler,
        miner.appenderScheduler,
        Observable.empty
      )
      def appendBlock(ref: Option[ByteStr]) =
        append(miner.forgeBlock(signer, TxHelpers.vrfKeyOf(signer), ref).toEither.explicitGet().newBlock).explicitGet()
      def appendMicro() = {
        d.utxPool.putIfNew(transfer(from = signer)).resultE.explicitGet()
        microBlockMiner.generateOneMicroBlockTask(signer, d.lastBlock, Unlimited, 0).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
      }

      withClue("Discard the latest micro block and referencing to a key block: ") {
        appendBlock(None)
        val keyBlockId = d.lastBlockId

        appendMicro()
        appendBlock(Some(keyBlockId))

        d.lastBlock.header.reference shouldBe keyBlockId
      }

      withClue("Discard the latest micro block and referencing to a previous: ") {
        appendMicro()
        val previousMicroBlockId = d.lastBlockId

        appendMicro()
        appendBlock(Some(previousMicroBlockId))

        d.lastBlock.header.reference shouldBe previousMicroBlockId
      }
    }
  }

  /* There is no absence interval any more: `Blockchain.supportsLightNodeBlockFields` is unconditionally true and
   * `lightNodeBlockFieldsAbsenceInterval` is gone from the settings, so the "… is not supported yet" rejections this
   * property used to assert can no longer happen at any height. What is left is that both fields are simply accepted.
   */
  property("blocks carrying a state hash or a challenged header are accepted") {
    withDomainAndMiner(
      TransactionStateSnapshot,
      AddrWithBalance.enoughBalances(defaultSigner, secondSigner),
      // defaultSigner forges the blocks and secondSigner challenges one; a challenger has to be a committed generator too
      minerAccounts = Seq(0, 1)
    ) { case (d, _, append) =>
      (1 to 10).foreach(_ => d.appendBlock())
      d.blockchain.height shouldBe 11

      val correctBlockWithStateHash = d.createBlock(strictTime = true)
      correctBlockWithStateHash.header.stateHash shouldBe defined
      d.testTime.setTime(correctBlockWithStateHash.header.timestamp)
      append(correctBlockWithStateHash) shouldBe a[Right[?, ?]]

      d.rollbackTo(11)
      val invalidBlock     = d.createBlock(stateHash = invalidStateHash, strictTime = true)
      val challengingBlock = d.createChallengingBlock(secondSigner, invalidBlock, strictTime = true)
      d.testTime.setTime(challengingBlock.header.timestamp)
      append(challengingBlock) shouldBe a[Right[?, ?]]
    }
  }
}
