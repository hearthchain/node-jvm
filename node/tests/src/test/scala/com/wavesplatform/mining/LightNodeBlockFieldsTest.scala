package com.wavesplatform.mining

import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.DigestLength
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.mining.MultiDimensionalMiningConstraint.Unlimited
import com.wavesplatform.mining.microblocks.MicroBlockMinerImpl
import com.wavesplatform.state.Height
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.test.{PropSpec, produce}
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.TxHelpers.{defaultSigner, secondSigner, transfer}
import com.wavesplatform.transaction.TxValidationError.GenericError
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import tech.hearth.crypto.{SigningKey, VrfKey}

import scala.concurrent.duration.DurationInt

class LightNodeBlockFieldsTest extends PropSpec with WithMiner {
  private val invalidStateHash = Some(Some(ByteStr.fill(DigestLength)(1)))

  property("new block fields appear `lightNodeBlockFieldsAbsenceInterval` blocks after LightNode activation") {
    val settings =
      TransactionStateSnapshot
        .copy(minerSettings = TransactionStateSnapshot.minerSettings.copy(quorum = 0, minMicroBlockAge = 0.seconds))
    withDomainAndMiner(
      settings,
      AddrWithBalance.enoughBalances(defaultSigner, secondSigner),
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
      val challenger = new BlockChallengerImpl(
        d.blockchain,
        new DefaultChannelGroup(GlobalEventExecutor.INSTANCE),
        Seq.empty,
        d.settings,
        d.testTime,
        d.posSelector,
        b => Task.now(append(b)),
        timeDrift = Int.MaxValue
      ) {
        override def pickBestAccount(accounts: Seq[((SigningKey, VrfKey), Long)]): Either[GenericError, ((SigningKey, VrfKey), Long)] =
          Right(((defaultSigner, ???), 0))
      }
      def block(height: Int) = d.blocksApi.blockAtHeight(Height(height)).get._1.header
      def appendBlock()      = append(miner.forgeBlock(defaultSigner, TxHelpers.vrfKeyOf(defaultSigner)).toEither.explicitGet().newBlock).explicitGet()
      def appendMicro() = {
        d.utxPool.putIfNew(transfer()).resultE.explicitGet()
        microBlockMiner.generateOneMicroBlockTask(defaultSigner, d.lastBlock, Unlimited, 0).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
      }
      def challengeBlock() = {
        val invalidBlock = d.createBlock(strictTime = true, stateHash = invalidStateHash)
        challenger.challengeBlock(invalidBlock, null).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
      }

      log.debug("LightNode activation")
      appendBlock()
      d.blockchain.height shouldBe 2
      block(2).stateHash shouldBe None

      appendMicro()
      block(2).stateHash shouldBe None

      appendBlock()
      d.blockchain.height shouldBe 3
      block(3).stateHash shouldBe None
      block(3).challengedHeader shouldBe None

      (4 to 11).foreach(_ => appendBlock())
      d.blockchain.height shouldBe 11
      block(11).stateHash shouldBe None

      appendMicro()
      block(11).stateHash shouldBe None

      appendBlock()
      d.blockchain.height shouldBe 12
      val hash1 = block(12).stateHash
      hash1 shouldBe defined

      log.debug("After lightNodeBlockFieldsAbsenceInterval - 1")
      appendMicro()
      val hash2 = block(12).stateHash
      hash2 shouldBe defined
      hash2 should not be hash1

      log.debug("Rollback before lightNodeBlockFieldsAbsenceInterval")
      d.rollbackTo(10)
      appendBlock()
      block(11).stateHash shouldBe None
      block(11).challengedHeader shouldBe None

      log.debug("After lightNodeBlockFieldsAbsenceInterval - 2")
      challengeBlock()
      block(12).stateHash shouldBe defined
      block(12).challengedHeader shouldBe defined
    }
  }

  property("micro forks should not produce invalid state hash") {
    val settings = TransactionStateSnapshot
      .copy(minerSettings = TransactionStateSnapshot.minerSettings.copy(quorum = 0, minMicroBlockAge = 0.seconds))

    val signer = TxHelpers.signer(2) // Sends transfers, forges blocks
    withDomainAndMiner(
      settings,
      AddrWithBalance.enoughBalances(signer),
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
      def appendBlock(ref: Option[ByteStr]) = append(miner.forgeBlock(signer, ???, ref).toEither.explicitGet().newBlock).explicitGet()
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

  property(
    "blocks with challenged header or state hash should be allowed only `lightNodeBlockFieldsAbsenceInterval` blocks after LightNode activation"
  ) {
    withDomainAndMiner(
      TransactionStateSnapshot,
      AddrWithBalance.enoughBalances(defaultSigner, secondSigner)
    ) { case (d, _, append) =>
      (1 to 9).foreach(_ => d.appendBlock())
      d.blockchain.height shouldBe 10
      val challengedBlock  = d.createBlock(strictTime = true, stateHash = invalidStateHash)
      val challengingBlock = d.createChallengingBlock(secondSigner, challengedBlock, strictTime = true)
      val blockWithOnlyChallengingHeader = {
        val challengedHeader = challengingBlock.header.challengedHeader.map(_.copy(stateHash = None))
        val block            = d.createBlock(strictTime = true, challengedHeader = challengedHeader)
        block.copy(header = block.header.copy(stateHash = None))
      }
      d.testTime.setTime(challengingBlock.header.timestamp)
      append(challengedBlock) should produce("Block state hash is not supported yet")
      append(challengingBlock) should produce("Block state hash is not supported yet")
      append(blockWithOnlyChallengingHeader) should produce("Challenged header is not supported yet")

      d.appendBlock()
      d.blockchain.height shouldBe 11
      val correctBlockWithStateHash = d.createBlock(strictTime = true)
      correctBlockWithStateHash.header.stateHash shouldBe defined
      d.testTime.setTime(correctBlockWithStateHash.header.timestamp)
      append(correctBlockWithStateHash) shouldBe a[Right[?, ?]]

      d.rollbackTo(11)
      val invalidBlock      = d.createBlock(stateHash = invalidStateHash, strictTime = true)
      val challengingBlock2 = d.createChallengingBlock(secondSigner, invalidBlock, strictTime = true)
      d.testTime.setTime(challengingBlock2.header.timestamp)
      append(challengingBlock2) shouldBe a[Right[?, ?]]
    }
  }
}
