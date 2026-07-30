package com.wavesplatform.state

import com.wavesplatform.TestValues
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.api.http.*
import com.wavesplatform.api.http.TransactionsApiRoute.{ApplicationStatus, Status}
import com.wavesplatform.block.{Block, BlockEndorsement, ChallengedHeader, FinalizationVoting, MicroBlock}
import com.wavesplatform.common.merkle.Merkle
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.consensus.GeneratingBalanceProvider
import com.wavesplatform.crypto.DigestLength
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.{Domain, defaultSigner}
import com.wavesplatform.http.DummyTransactionPublisher
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.mining.{BlockChallenger, BlockChallengerImpl, GeneratorKeys, MiningAccount}

import java.util.concurrent.atomic.AtomicInteger
import com.wavesplatform.network.MicroBlockSynchronizer.MicroblockData
import com.wavesplatform.network.{ExtensionBlocks, InvalidBlockStorage, MessageCodec, PBBlockSpec, PeerDatabase, RawBytes}
import com.wavesplatform.protobuf.transaction.PBTransactions
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.BlockRewardCalculator.BlockRewardShares
import com.wavesplatform.state.BlockchainUpdaterImpl.BlockApplyResult
import com.wavesplatform.state.appender.{BlockAppender, ExtensionAppender, MicroblockAppender}
import com.wavesplatform.state.diffs.{BlockDiffer, ENOUGH_AMT}
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.test.DomainPresets.{TransactionStateSnapshot}
import com.wavesplatform.transaction.TxValidationError.{BlockAppendError, GenericError, InvalidStateHash, MicroBlockAppendError}
import com.wavesplatform.transaction.{CommitToGenerationTransaction, Transaction, TxHelpers}
import com.wavesplatform.utils.{JsonMatchers, SharedSchedulerMixin}
import com.wavesplatform.wallet.Wallet
import io.netty.channel.Channel
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.group.{ChannelGroup, DefaultChannelGroup}
import io.netty.util.concurrent.GlobalEventExecutor
import monix.eval.{Coeval, Task}
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import org.apache.pekko.http.scaladsl.model.{ContentTypes, FormData, HttpEntity}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.*
import org.scalatest.{Assertion, BeforeAndAfterAll, ParallelTestExecution}
import play.api.libs.json.*
import tech.hearth.crypto.{SigningKey, VrfKey}

import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}

class BlockChallengeTest
    extends PropSpec
    with WithDomain
    with ScalatestRouteTest
    with ApiMarshallers
    with JsonMatchers
    with SharedSchedulerMixin
    with ParallelTestExecution
    with BeforeAndAfterAll {
  private val GetTimeStampAdjustment = 10 // To surpass Testtime.getTimestamp increment

  implicit val appenderScheduler: SchedulerService = Scheduler.singleThread("appender")
  val settings: WavesSettings =
    TransactionStateSnapshot

  val testTime: TestTime = TestTime()

  val invalidStateHash: ByteStr = ByteStr.fill(DigestLength)(1)

  override def afterAll(): Unit = {
    super.afterAll()
    appenderScheduler.shutdown()
  }

  /** Only a committed generator may produce a block, and a challenger is no exception - it has to be drawn from the
    * committed generator set. A key derived from the clock could never be committed in the genesis snapshot, so these
    * accounts come from a fixed pool that every domain below commits (see `generators = allGenerators`).
    */
  // At most two distinct challengers are used within a single test, and the counter wraps, so the pool stays small:
  // every account here is committed in each domain below, and a larger set would needlessly bloat the generator set
  private val challengerPool: Seq[SigningKey]  = (900 to 903).map(TxHelpers.signer)
  private val challengerCounter: AtomicInteger = AtomicInteger(0)
  // The pool is safe to commit everywhere: these accounts exist only to mine. Accounts a test names itself are
  // declared per test below, since whether such an account mines (and so must be committed and funded) is a
  // per-test fact - funding one that is merely a transaction sender would corrupt the test's own setup.
  /** The account the BlockChallenger mines with. It has to be a committed generator like any other miner, and it must
    * not be the account that generated the challenged block - a node cannot challenge its own block.
    */
  private val challengerNode: MiningAccount = {
    val key = TxHelpers.signer(950)
    MiningAccount(key, TxHelpers.vrfKeyOf(key), TxHelpers.blsKeyOf(key))
  }

  // defaultSigner joins the pool because `d.appendBlock()` mines with it, and a block only appends if its generator is
  // committed. It is funded like the tests that name it themselves do, so committing it here changes nothing for them.
  private val allGenerators: Seq[SigningKey] = challengerPool :+ challengerNode.signingKey :+ TxHelpers.defaultSigner

  /** Enough for a committed generator to still clear the minimum generating balance once its deposit is taken out.
    * Credited in the genesis snapshot, so it counts from height 1 instead of needing ~1000 blocks to mature.
    */
  private val challengerBalance: Long = 1000.waves + CommitToGenerationTransaction.DepositInWavelets

  // Every domain below commits `allGenerators`, so each of those accounts needs a genesis balance covering its
  // generation deposit. Prepended to each test's own balances; duplicates (a challenger a test also funds) are deduped.
  private val poolBalances: Seq[AddrWithBalance] =
    (challengerPool :+ challengerNode.signingKey).map(g => AddrWithBalance(g.toAddress, challengerBalance)) :+
      AddrWithBalance(TxHelpers.defaultAddress)

  private def generateMiningAccount: MiningAccount = {
    val key = challengerPool(challengerCounter.getAndIncrement() % challengerPool.size)
    MiningAccount(key, TxHelpers.vrfKeyOf(key), TxHelpers.blsKeyOf(key))
  }

  /** Finalization voting that endorses the chain head with one committed generator's key. A block names its endorsers
    * by their position in the period's committed set, and the aggregated signature has to be by exactly those keys, so
    * the index cannot be written down in advance - it depends on what the domain committed.
    */
  private def headEndorsedBy(d: Domain, endorser: SigningKey): FinalizationVoting = {
    val period = d.blockchain.currentGenerationPeriod.value
    val idx    = d.blockchain.committedGenerators(period).indexWhere(_.address == endorser.toAddress)
    require(idx >= 0, s"${endorser.toAddress} is not a committed generator of $period")

    val finalizedHeight = d.blockchain.finalizedHeight.value
    val finalizedId     = d.blockchain.blockId(finalizedHeight.toInt).value
    FinalizationVoting(
      valid = Seq(GeneratorIndex(idx)),
      finalizedHeight = finalizedHeight,
      aggregatedEndorsement = Some(
        BlockEndorsement.sign(
          TxHelpers.blsKeyOf(endorser),
          finalizedId = finalizedId,
          finalizedHeight = finalizedHeight,
          endorsedId = d.lastBlockId
        )
      ),
      conflict = Vector.empty
    )
  }

  property("NODE-883. Invalid challenging block should be ignored") {
    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = TxHelpers.signer(3)
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengedMiner, challengingMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender)
    ) { d =>
      d.appendBlock()
      val txs                         = Seq(TxHelpers.transfer(sender, amount = 1), TxHelpers.transfer(sender, amount = 2))
      val invalidChallengedBlock      = d.createBlock(txs, generator = challengedMiner, stateHash = Some(Some(invalidStateHash)))
      val invalidHashChallengingBlock = d.createChallengingBlock(challengingMiner, invalidChallengedBlock, stateHash = Some(Some(invalidStateHash)))
      val missedHashChallengingBlock  = d.createChallengingBlock(challengingMiner, invalidChallengedBlock, stateHash = Some(None))
      val validChallengingBlock       = d.createChallengingBlock(challengingMiner, invalidChallengedBlock)

      d.appendBlockE(invalidHashChallengingBlock) shouldBe Left(InvalidStateHash(Some(invalidStateHash), validChallengingBlock.header.stateHash))
      d.appendBlockE(missedHashChallengingBlock) shouldBe Left(InvalidStateHash(None, validChallengingBlock.header.stateHash))
    }
  }

  property("NODE-884. Challenging miner should have correct balances") {
    val challengedMiner  = TxHelpers.signer(1)
    val challengingMiner = generateMiningAccount

    // Both miners are credited and committed in the genesis snapshot, so they can generate from the very first block.
    // Crediting them in a later block, as this test used to, only meant waiting ~1000 blocks for their generating
    // balance to mature before anything interesting could happen.
    val deposit = CommitToGenerationTransaction.DepositInWavelets
    withDomain(
      settings,
      generators = Seq(challengedMiner, challengingMiner.signingKey),
      balances = poolBalances ++ Seq(
        AddrWithBalance(TxHelpers.defaultAddress),
        AddrWithBalance(challengingMiner.address, 1000.waves + deposit),
        AddrWithBalance(challengedMiner.toAddress, 2000.waves + deposit)
      )
    ) { d =>
      val challengingMinerAddr = challengingMiner.address

      val originalBlock = d.createBlock(strictTime = true, generator = challengedMiner, stateHash = Some(Some(invalidStateHash)))

      val challengingGenBalanceBefore = d.blockchain.generatingBalance(challengingMinerAddr, Some(originalBlock.header.reference))
      val challengingEffBalanceBefore = d.blockchain.effectiveBalance(challengingMinerAddr, 0)
      val challengedGenBalanceBefore  = d.blockchain.generatingBalance(challengedMiner.toAddress, Some(originalBlock.header.reference))

      val challengingBlock =
        d.createChallengingBlock(challengingMiner.signingKey, originalBlock, strictTime = true, timestamp = Some(d.nextBlockTime(challengingMiner)))
      d.appender.appendBlock(challengingBlock)

      d.blockchain.generatingBalance(
        challengingMinerAddr,
        Some(challengingBlock.header.reference)
      ) shouldBe challengingGenBalanceBefore + challengedGenBalanceBefore

      val minerReward = getLastBlockMinerReward(d)
      d.blockchain.effectiveBalance(challengingMinerAddr, 0) shouldBe challengingEffBalanceBefore + minerReward

      d.blockchain.generatingBalance(challengingMinerAddr, Some(challengingBlock.id())) shouldBe challengingGenBalanceBefore

      withClue(s"challenging $challengingMinerAddr: ") {
        d.commonApi.generatorsApi
          .generators(Height(d.blockchain.height))
          .collectFirst {
            case x if x.address == challengingMinerAddr => x.balance
          }
          .value shouldBe Some(challengingEffBalanceBefore)
      }
    }
  }

  property("NODE-885. Consensus data for challenging block should be recalculated") {
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey),
      balances =
        poolBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner) :+ AddrWithBalance(challengingMiner.address, challengerBalance)
    ) { d =>
      appendAndCheck(
        d.createBlock(strictTime = true, stateHash = Some(Some(invalidStateHash)), timestamp = Some(Long.MaxValue)),
        d,
        Seq(challengingMiner)
      ) { block =>
        block.header.challengedHeader shouldBe defined
        val challengedHeader = block.header.challengedHeader.get

        block.header.generator shouldBe challengingMiner.publicKey
        block.header.generator == challengedHeader.generator shouldBe false
        val isConsensusDataEqual =
          block.header.timestamp == challengedHeader.timestamp &&
            block.header.generationSignature == challengedHeader.generationSignature &&
            block.header.baseTarget == challengedHeader.baseTarget

        isConsensusDataEqual shouldBe false
      }
    }
  }

  property("NODE-886. Consensus data for challenging block should be calculated for each mining account from wallet") {
    val challengedMiner   = TxHelpers.signer(1)
    val challengingMiner1 = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner1.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, challengedMiner) :+ AddrWithBalance(
        challengingMiner1.address,
        challengerBalance
      )
    ) { d =>
      val challengingMiner2 = generateMiningAccount

      def check(block: Block): Unit = {
        block.header.challengedHeader shouldBe defined
        // Anyone can challenge
        Set(challengingMiner1, challengingMiner2).map(_.publicKey) should contain(block.header.generator)
      }

      appendAndCheck(
        d.createBlock(strictTime = true, generator = challengedMiner, stateHash = Some(Some(invalidStateHash)), timestamp = Some(Long.MaxValue)),
        d,
        Seq(challengingMiner1, challengingMiner2)
      )(check)

      d.appendBlock(TxHelpers.transfer(challengingMiner1.signingKey, challengingMiner2.address, 1000.waves))
      (1 to 999).foreach(_ => d.appendBlock())

      appendAndCheck(
        d.createBlock(strictTime = true, generator = challengedMiner, stateHash = Some(Some(invalidStateHash)), timestamp = Some(Long.MaxValue)),
        d,
        Seq(challengingMiner1, challengingMiner2)
      )(check)
    }
  }

  property("NODE-887. BlockChallenger should pick account with the best timestamp") {
    val challengedMiner = TxHelpers.signer(1)
    withDomain(settings, generators = allGenerators, balances = poolBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)) { d =>
      val challenger      = createBlockChallenger(d)
      val accNum          = 10
      val challengingAccs = (1 to accNum).flatMap(_ => d.wallet.generateNewAccount())

      challengingAccs.size shouldBe accNum

      val transfers = challengingAccs.zipWithIndex.map { case (acc, idx) =>
        TxHelpers.transfer(TxHelpers.defaultSigner, acc.toAddress, (1000 + idx).waves)
      } :+ TxHelpers.transfer(TxHelpers.defaultSigner, challengedMiner.toAddress, 1000.waves)

      d.appendBlock(transfers*)
      (1 to 999).foreach(_ => d.appendBlock())

      val allChallengingAccounts = challenger.getChallengingAccounts(challengedMiner.toAddress)

      challenger.pickBestAccount(allChallengingAccounts.explicitGet()) shouldBe Right(allChallengingAccounts.explicitGet().minBy(_._2))
    }
  }

  property("NODE-888. ChallengedHeader should contain info from original block header") {
    val challengedMiner  = TxHelpers.signer(0)
    val challengingMiner = Wallet.generateNewAccount(Domain.DefaultWalletSeed, 0)

    val testSettings = settings
      .configure(_.copy(generationPeriodLength = 700))

    withDomain(
      testSettings,
      generators = allGenerators ++ Seq(challengedMiner),
      // The challenger commits a second time below, so it pays a second deposit, and it only produces a better
      // timestamp than the miner it challenges if it out-weighs it - neither fits the pool's standard balance.
      balances =
        AddrWithBalance(challengerNode.address, ENOUGH_AMT) +: (poolBalances ++ AddrWithBalance.enoughBalances(challengedMiner, challengingMiner))
    ) { d =>
      d.wallet.generateNewAccounts(2)

      d.appendBlock(
        TxHelpers.commitToGeneration(Height(701), challengedMiner),
        TxHelpers.commitToGeneration(Height(701), challengingMiner),
        TxHelpers.commitToGeneration(Height(701), challengerNode.signingKey)
      )

      (1 to 999).foreach(_ => d.appendBlock(d.createBlock(generator = challengedMiner)))

      val originalBlock = d.createBlock(
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash)),
        timestamp = Some(Long.MaxValue),
        finalizationVoting = Some(headEndorsedBy(d, challengingMiner))
      )

      appendAndCheck(originalBlock, d) { block =>
        block.header.challengedHeader shouldBe defined
        val challengedHeader = block.header.challengedHeader.get

        challengedHeader.timestamp shouldBe originalBlock.header.timestamp
        challengedHeader.baseTarget shouldBe originalBlock.header.baseTarget
        challengedHeader.generationSignature shouldBe originalBlock.header.generationSignature
        challengedHeader.featureVotes shouldBe originalBlock.header.featureVotes
        challengedHeader.generator shouldBe originalBlock.header.generator
        challengedHeader.stateHash shouldBe originalBlock.header.stateHash
        challengedHeader.headerSignature shouldBe originalBlock.signature
        challengedHeader.finalizationVoting shouldBe originalBlock.header.finalizationVoting
      }
    }
  }

  property("NODE-888. ChallengedHeader should contain info from original block header - micro block") {
    // Not a pool account: it commits a second time below, so it needs a second deposit, and the pool's own balance
    // entry would win the dedup and leave it short.
    val challengedMinerKey = TxHelpers.signer(940)
    val challengedMiner    = MiningAccount(challengedMinerKey, TxHelpers.vrfKeyOf(challengedMinerKey), TxHelpers.blsKeyOf(challengedMinerKey))
    val challengingMiner   = Wallet.generateNewAccount(Domain.DefaultWalletSeed, 0)

    val testSettings = settings
      .configure(_.copy(generationPeriodLength = 700))

    val initBalances = Seq(
      AddrWithBalance(challengingMiner.toAddress, ENOUGH_AMT),
      // The challenging block only replaces the challenged one if its timestamp is better, and that is a PoS delay:
      // the challenger has to out-weigh the miner it challenges, not merely clear the generation minimum.
      AddrWithBalance(challengerNode.address, ENOUGH_AMT),
      AddrWithBalance(
        challengedMiner.address,
        GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2 + 2 * CommitToGenerationTransaction.DepositInWavelets +
          TestValues.commitToGenerationFee + 1.waves
      )
    )

    withDomain(
      testSettings,
      generators = allGenerators :+ challengedMinerKey,
      balances = initBalances ++ poolBalances,
      time = testTime
    ) { d =>
      d.wallet.generateNewAccounts(2)

      d.appendBlock(
        TxHelpers.commitToGeneration(Height(701), challengedMiner.signingKey),
        TxHelpers.commitToGeneration(Height(701), challengingMiner),
        TxHelpers.commitToGeneration(Height(701), challengerNode.signingKey)
      )

      d.accountsApi
        .balanceDetails(challengingMiner.toAddress)
        .explicitGet()
        .generating shouldBe ENOUGH_AMT - CommitToGenerationTransaction.DepositInWavelets - TestValues.commitToGenerationFee

      (1 to 999).foreach(_ => d.appendBlock(d.createBlock(generator = challengedMiner.signingKey)))

      val voting = headEndorsedBy(d, challengingMiner)

      val ts            = d.nextBlockTime(challengedMiner)
      val originalBlock = d.createBlock(strictTime = true, generator = challengedMiner.signingKey, timestamp = Some(ts))

      testTime.setTime(ts)
      d.appendBlock(originalBlock)

      val appender = createMicroBlockAppender(d)
      val channel  = new EmbeddedChannel()

      val microBlock = d.createMicroBlock(
        stateHash = Some(invalidStateHash),
        signer = Some(challengedMiner.signingKey),
        finalizationVoting = Some(voting)
      )(TxHelpers.transfer(challengedMiner.signingKey, challengingMiner.toAddress))

      appender(channel, microBlock).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
      d.blockchain.lastBlockHeader.value.header.challengedHeader shouldBe defined
    }
  }

  property("NODE-890. Challenging block should contain all transactions from original block") {
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey),
      balances =
        poolBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner) :+ AddrWithBalance(challengingMiner.address, challengerBalance)
    ) { d =>
      val originalBlock = d.createBlock(
        strictTime = true,
        stateHash = Some(Some(invalidStateHash)),
        timestamp = Some(Long.MaxValue)
      )
      appendAndCheck(originalBlock, d, Seq(challengingMiner)) { block =>
        block.transactionData shouldBe originalBlock.transactionData
      }
    }
  }

  property("NODE-891. Challenging block should contain only transactions from original block") {
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey),
      balances =
        poolBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner) :+ AddrWithBalance(challengingMiner.address, challengerBalance)
    ) { d =>
      val originalBlock = d.createBlock(strictTime = true, stateHash = Some(Some(invalidStateHash)))
      val invalidChallengingBlock = d.createChallengingBlock(
        challengingMiner.signingKey,
        originalBlock,
        stateHash = None,
        txs = Some(originalBlock.transactionData :+ TxHelpers.transfer(TxHelpers.defaultSigner))
      )

      d.appendBlockE(invalidChallengingBlock) shouldBe Left(
        GenericError(s"Invalid block challenge: ${GenericError(s"Block ${invalidChallengingBlock.toOriginal} has invalid signature")}")
      )

      val correctChallengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock, stateHash = None)
      d.appendBlockE(correctChallengingBlock) should beRight

      val microblock = d.createMicroBlock(None, Some(challengingMiner.signingKey))(TxHelpers.transfer(TxHelpers.defaultSigner))
      d.appendMicroBlockE(microblock) shouldBe Left(MicroBlockAppendError("Base block has challenged header", microblock))
    }
  }

  property("NODE-892. Challenging block should reference the same block as original") {
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey),
      balances =
        poolBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner) :+ AddrWithBalance(challengingMiner.address, challengerBalance)
    ) { d =>
      val originalBlock = d.createBlock(strictTime = true, stateHash = Some(Some(invalidStateHash)), timestamp = Some(Long.MaxValue))
      appendAndCheck(originalBlock, d, Seq(challengingMiner)) { block =>
        block.header.reference shouldBe originalBlock.header.reference
      }
    }
  }

  property("NODE-893. Challenging block can't reference blocks before previous") {
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey),
      balances =
        poolBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner) :+ AddrWithBalance(challengingMiner.address, challengerBalance)
    ) { d =>
      d.appendBlock()
      d.appendBlock()

      val grandParent      = d.blockchain.blockHeader(d.blockchain.height - 2).map(_.id()).get
      val originalBlock    = d.createBlock(strictTime = true, stateHash = Some(Some(invalidStateHash)))
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock, stateHash = None)

      // A block cannot be built with a bad reference in the first place - `d.createBlock` computes its state hash
      // against the head - so a well formed one is retargeted onto the grandparent instead. It goes straight to the
      // updater too: `appendBlockE` resolves the reference itself, to verify the VRF proof against the parent's hit
      // source, and would fail before the updater is ever asked about the reference.
      val beforePrevious = Block
        .buildAndSign(
          challengingBlock.header.timestamp,
          grandParent,
          challengingBlock.header.baseTarget,
          challengingBlock.header.generationSignature,
          challengingBlock.transactionData,
          challengingMiner.signingKey,
          challengingBlock.header.featureVotes,
          challengingBlock.header.stateHash,
          challengingBlock.header.challengedHeader,
          challengingBlock.header.finalizationVoting
        )
        .explicitGet()

      d.blockchainUpdater.processBlock(
        beforePrevious,
        beforePrevious.header.generationSignature,
        snapshot = None,
        generatorSet = Seq.empty
      ) shouldBe Left(BlockAppendError("References incorrect or non-existing block", beforePrevious))
    }
  }

  property("NODE-894. Node should stop accepting of subsequent microblocks after receiving microblock with invalid state hash") {
    withDomain(settings, generators = allGenerators, balances = poolBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)) { d =>
      d.appendBlock()
      val lastBlockIdBefore = d.lastBlockId
      d.appendBlock()

      val txs = () => Seq(TxHelpers.transfer(amount = 1.waves), TxHelpers.transfer(amount = 2.waves))

      val appender = createMicroBlockAppender(d)
      val channel  = new EmbeddedChannel()

      val lastValidMicroblock = d.createMicroBlock()(txs()*)
      appender(channel, lastValidMicroblock).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))

      val lastValidBlockId  = d.lastBlockId
      val invalidMicroblock = d.createMicroBlock(Some(invalidStateHash))(txs()*)
      val invalidBlockId = Block
        .create(
          d.lastBlock,
          d.lastBlock.transactionData ++ invalidMicroblock.transactionData,
          invalidMicroblock.wholeBlockSignature,
          invalidMicroblock.stateHash,
          finalizationVoting = None
        )
        .id()

      channel.isOpen shouldBe true

      appender(channel, invalidMicroblock).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))

      channel.isOpen shouldBe false
      d.lastBlockId shouldBe lastBlockIdBefore

      val lastBlockId = d.lastBlockId
      appender(null, d.createMicroBlock(ref = Some(invalidBlockId))(txs()*)).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
      d.lastBlockId shouldBe lastBlockId
      appender(null, d.createMicroBlock(ref = Some(lastValidBlockId))(txs()*)).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
      d.lastBlockId shouldBe lastBlockId
    }
  }

  property("NODE-895. Challenged miner should have correct balances") {
    val challengedMiner  = TxHelpers.signer(1)
    val challengingMiner = generateMiningAccount

    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengedMiner, challengingMiner.signingKey),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner) ++ Seq(
        AddrWithBalance(challengingMiner.address, 2000.waves + CommitToGenerationTransaction.DepositInWavelets),
        AddrWithBalance(challengedMiner.toAddress, 3000.waves + CommitToGenerationTransaction.DepositInWavelets)
      )
    ) { d =>
      val challengedMinerAddr = challengedMiner.toAddress

      val originalBlock = d.createBlock(
        Seq(TxHelpers.transfer(challengedMiner, TxHelpers.defaultAddress, amount = 1.waves)),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )

      val challengingBlock =
        d.createChallengingBlock(challengingMiner.signingKey, originalBlock, strictTime = true, timestamp = Some(d.nextBlockTime(challengingMiner)))

      val effBalanceBefore = d.blockchain.effectiveBalance(challengedMinerAddr, 0)

      d.appender.appendBlock(challengingBlock)
      d.blockchain.effectiveBalance(challengedMinerAddr, 0) shouldBe 0L

      withClue(s"challenged $challengedMinerAddr: ") {
        d.commonApi.generatorsApi
          .generators(Height(d.blockchain.height))
          .collectFirst {
            case x if x.address == challengedMinerAddr => x.balance
          }
          .value shouldBe Some(effBalanceBefore)
      }

      val newBlock = d.createBlock(strictTime = true, generator = challengingMiner.signingKey, timestamp = Some(d.nextBlockTime(challengingMiner)))
      d.testTime.setTime(newBlock.header.timestamp + GetTimeStampAdjustment)
      d.blockAppender(newBlock).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s")) should beRight
      d.blockchain.height shouldBe 3

      val expectedEffectiveBalance = effBalanceBefore - 1.waves - TestValues.fee
      d.blockchain.effectiveBalance(challengedMinerAddr, 0) shouldBe expectedEffectiveBalance

      withClue(s"challenged $challengedMinerAddr: ") {
        d.commonApi.generatorsApi
          .generators(Height(d.blockchain.height))
          .collectFirst {
            case x if x.address == challengedMinerAddr => x.balance
          }
          .value shouldBe Some(0L)
      }
    }
  }

  property("NODE-898. Block reward and fees should be distributed to challenging miner") {
    val sender           = TxHelpers.signer(1)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender) :+ AddrWithBalance(challengingMiner.address, challengerBalance)
    ) { d =>

      val prevBlockTx = TxHelpers.transfer(sender)

      d.appendBlock(prevBlockTx)

      val challengedBlockTxs      = Seq(TxHelpers.transfer(sender), TxHelpers.transfer(sender))
      val originalBlock           = d.createBlock(challengedBlockTxs, strictTime = true, stateHash = Some(Some(invalidStateHash)))
      val originalMinerBalance    = d.balance(originalBlock.header.generator.toAddress)
      val challengingMinerBalance = d.balance(challengingMiner.address)
      val challengingBlock        = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)

      d.appendBlockE(challengingBlock) should beRight

      d.balance(originalBlock.header.generator.toAddress) shouldBe originalMinerBalance
      d.balance(
        challengingMiner.address
      ) shouldBe challengingMinerBalance + getLastBlockMinerReward(d) +
        (prevBlockTx.fee.value - BlockDiffer.CurrentBlockFeePart(prevBlockTx.fee.value)) +
        challengedBlockTxs.map(tx => BlockDiffer.CurrentBlockFeePart(tx.fee.value)).sum
    }
  }

  property("NODE-899. Transactions that become invalid in challenging block should have elided status") {
    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val recipient        = TxHelpers.signer(3)
    val recipient2       = TxHelpers.signer(4)
    val challengingMiner = generateMiningAccount
    withDomain(
      TransactionStateSnapshot,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>

      val lease             = TxHelpers.lease(recipient)
      val challengedBlockTx = TxHelpers.transfer(challengedMiner, recipient.toAddress, 1001.waves)
      val recipientTxs = Seq(
        lease,
        TxHelpers.leaseCancel(lease.id(), recipient),
        TxHelpers
          .massTransfer(recipient, Seq(recipient2.toAddress -> 1.waves), fee = TestValues.fee),
        TxHelpers.transfer(recipient, recipient2.toAddress, 100.waves)
      )
      val validOriginalBlock = d.createBlock(
        challengedBlockTx +: recipientTxs,
        strictTime = true,
        generator = challengedMiner
      )
      val invalidOriginalBlock = d.createBlock(
        challengedBlockTx +: recipientTxs,
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, invalidOriginalBlock)

      d.appendBlockE(validOriginalBlock) should beRight
      recipientTxs.foreach { tx =>
        d.transactionsApi.transactionById(tx.id()).map(_.status).contains(TxMeta.Status.Succeeded) shouldBe true
      }

      d.rollbackTo(validOriginalBlock.header.reference)

      d.appendBlockE(challengingBlock) should beRight

      val blockRewards = getLastBlockRewards(d)
      // block snapshot contains only txs and block reward
      val blockSnapshot = d.blockchain.bestLiquidSnapshot.get
      val expectedSnapshot = StateSnapshot
        .build(
          d.rocksDBWriter,
          Map(challengingMiner.address -> Portfolio.waves(blockRewards.miner)) ++
            d.blockchain.settings.functionalitySettings.daoAddressParsed.toOption.flatten.map(_ -> Portfolio.waves(blockRewards.daoAddress)),
          transactions = blockSnapshot.transactions
        )
        .explicitGet()

      blockSnapshot shouldBe expectedSnapshot
      blockSnapshot.transactions.foreach(_._2.status shouldBe TxMeta.Status.Elided)
      recipientTxs.foreach { tx =>
        d.transactionsApi.transactionById(tx.id()).map(_.status).contains(TxMeta.Status.Elided) shouldBe true
      }
    }
  }

  property("NODE-901. Elided transaction sender should not pay fee") {
    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>

      val challengedBlockTx = TxHelpers.transfer(challengedMiner, amount = 1001.waves)
      val originalBlock = d.createBlock(
        Seq(challengedBlockTx),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)

      val elidedTxSenderBalance = d.balance(challengedMiner.toAddress)

      d.appendBlockE(challengingBlock) should beRight

      d.transactionsApi.transactionById(challengedBlockTx.id()).map(_.status).contains(TxMeta.Status.Elided) shouldBe true
      d.balance(challengedMiner.toAddress) shouldBe elidedTxSenderBalance
    }
  }

  property("NODE-902. Elided transaction should have unique ID") {
    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>
      // Affordable while the block is built, and invalid once the challenge takes the sender's balance - the same
      // amount its siblings above use, and what makes the transaction elided rather than simply unappendable.
      val challengedBlockTx = TxHelpers.transfer(challengedMiner, amount = 1001.waves)
      val originalBlock = d.createBlock(
        Seq(challengedBlockTx),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash)),
        timestamp = Some(Long.MaxValue)
      )

      appendAndCheck(originalBlock, d, Seq(challengingMiner)) { block =>
        d.transactionsApi.transactionById(challengedBlockTx.id()).map(_.status).contains(TxMeta.Status.Elided) shouldBe true
        block.transactionData.head.id() shouldBe challengedBlockTx.id()

        d.appendBlock(TxHelpers.transfer(sender, challengedMiner.toAddress, 10.waves))
        d.transactionDiffer(challengedBlockTx).resultE should produce("AlreadyInTheState")
      }
    }
  }

  property("NODE-904. /transactions/merkleProof should return proofs for elided transactions") {
    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>

      val challengedBlockTx = TxHelpers.transfer(challengedMiner, amount = 1001.waves)
      val originalBlock = d.createBlock(
        Seq(challengedBlockTx),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)

      d.appendBlockE(challengingBlock) should beRight
      d.transactionsApi.transactionById(challengedBlockTx.id()).map(_.status).contains(TxMeta.Status.Elided) shouldBe true

      val route = new TransactionsApiRoute(
        d.settings.restAPISettings,
        d.commonApi.transactions,
        d.wallet,
        d.generatorKeys,
        d.blockchain,
        () => d.blockchain,
        () => 0,
        (t, _) => d.commonApi.transactions.broadcastTransaction(t),
        testTime,
        new RouteTimeout(60.seconds)(using sharedScheduler)
      ).route

      d.liquidAndSolidAssert { () =>
        Get(s"/transactions/merkleProof?id=${challengedBlockTx.id()}") ~> route ~> check {
          val proof = responseAs[JsArray].value.head.as[JsObject]
          (proof \ "id").as[String] shouldBe challengedBlockTx.id().toString
          (proof \ "transactionIndex").as[Int] shouldBe 0
          (proof \ "merkleProof").as[Seq[String]].head shouldBe ByteStr(
            Merkle
              .mkProofs(
                0,
                Merkle.mkLevels(Seq(challengedBlockTx).map(PBTransactions.toByteArrayMerkle))
              )
              .head
          ).toString
        }
      }
    }
  }

  property("NODE-909. Empty key block can be challenged") {
    val sender           = TxHelpers.signer(1)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey),
      balances =
        poolBalances ++ AddrWithBalance.enoughBalances(sender, defaultSigner) ++ Seq(AddrWithBalance(challengingMiner.address, challengerBalance))
    ) { d =>

      val originalBlock = d.createBlock(strictTime = true, stateHash = Some(Some(invalidStateHash)), timestamp = Some(Long.MaxValue))

      appendAndCheck(originalBlock, d, Seq(challengingMiner)) { block =>
        block.header.challengedHeader shouldBe defined
        val challengedHeader = block.header.challengedHeader.get

        challengedHeader.timestamp shouldBe originalBlock.header.timestamp
        challengedHeader.baseTarget shouldBe originalBlock.header.baseTarget
        challengedHeader.generationSignature shouldBe originalBlock.header.generationSignature
        challengedHeader.featureVotes shouldBe originalBlock.header.featureVotes
        challengedHeader.generator shouldBe originalBlock.header.generator
        challengedHeader.stateHash shouldBe originalBlock.header.stateHash
        challengedHeader.headerSignature shouldBe originalBlock.signature
      }
    }
  }

  property(s"NODE-910. Block at LightNode activation height can be challenged") {
    withDomain(
      DomainPresets.BlockRewardDistribution,
      generators = allGenerators,
      balances = poolBalances ++ AddrWithBalance.enoughBalances(defaultSigner)
    ) { d =>
      val challengingMiner = generateMiningAccount

      d.appendBlock(TxHelpers.transfer(defaultSigner, challengingMiner.address, 1000.waves))
      (1 to 1000).foreach(_ => d.appendBlock())

//      d.blockchain.isFeatureActivated(BlockchainFeatures.LightNode) shouldBe false

      val originalBlock = d.createBlock(strictTime = true, stateHash = Some(Some(invalidStateHash)), timestamp = Some(Long.MaxValue))

      appendAndCheck(originalBlock, d, Seq(challengingMiner)) { block =>
        block.header.challengedHeader shouldBe defined
        val challengedHeader = block.header.challengedHeader.get

        challengedHeader.timestamp shouldBe originalBlock.header.timestamp
        challengedHeader.baseTarget shouldBe originalBlock.header.baseTarget
        challengedHeader.generationSignature shouldBe originalBlock.header.generationSignature
        challengedHeader.featureVotes shouldBe originalBlock.header.featureVotes
        challengedHeader.generator shouldBe originalBlock.header.generator
        challengedHeader.stateHash shouldBe originalBlock.header.stateHash
        challengedHeader.headerSignature shouldBe originalBlock.signature
      }
    }
  }

  property(s"NODE-911. Rollback should work correctly with challenging blocks") {
    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender, challengedMiner) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance)
      )
    ) { d =>

      val rollbackTarget = d.blockchain.lastBlockId.get

      val txs = Seq(TxHelpers.transfer(challengedMiner, amount = 10001.waves))
      rollbackMiddleScenario(d, challengedMiner, txs)
      val middleScenarioStateHash = d.lastBlock.header.stateHash
      middleScenarioStateHash shouldBe defined
      d.rollbackTo(rollbackTarget)
      rollbackMiddleScenario(d, challengedMiner, txs)

      d.lastBlock.header.stateHash shouldBe middleScenarioStateHash

      d.rollbackTo(rollbackTarget)

      rollbackLastScenario(d, challengedMiner, txs)
      val lastScenarioStateHash = d.lastBlock.header.stateHash
      lastScenarioStateHash shouldBe defined
      d.rollbackTo(rollbackTarget)

      rollbackLastScenario(d, challengedMiner, txs)

      d.lastBlock.header.stateHash shouldBe lastScenarioStateHash
    }

    withDomain(
      DomainPresets.BlockRewardDistribution,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender, challengedMiner) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance)
      )
    ) { d =>

      val rollbackTarget = d.blockchain.lastBlockId.get

      val txs = Seq(TxHelpers.transfer(challengedMiner, amount = 10001.waves))
      rollbackActivationHeightScenario(d, challengedMiner, txs)
      val stateHash = d.lastBlock.header.stateHash
      stateHash shouldBe defined
      d.rollbackTo(rollbackTarget)
      rollbackActivationHeightScenario(d, challengedMiner, txs)

      d.lastBlock.header.stateHash shouldBe stateHash
    }
  }

  property("NODE-912. ExtensionAppender should append challenging block correctly") {
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(defaultSigner) ++ Seq(AddrWithBalance(challengingMiner.address, challengerBalance))
    ) { d =>

      val originalBlock    = d.createBlock(strictTime = true, stateHash = Some(Some(invalidStateHash)))
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock, strictTime = true)

      val extensionAppender =
        ExtensionAppender(d.blockchain, d.utxPool, d.posSelector, testTime, InvalidBlockStorage.NoOp, PeerDatabase.NoOp, appenderScheduler)(null, _)

      testTime.setTime(challengingBlock.header.timestamp)
      extensionAppender(ExtensionBlocks(d.blockchain.score + challengingBlock.blockScore(), Seq(challengingBlock), Map.empty, new EmbeddedChannel()))
        .runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
        .explicitGet()

      d.blockchain.height shouldBe 2

      d.lastBlock.header.challengedHeader shouldBe defined
      val challengedHeader = d.lastBlock.header.challengedHeader.get

      challengedHeader.timestamp shouldBe originalBlock.header.timestamp
      challengedHeader.baseTarget shouldBe originalBlock.header.baseTarget
      challengedHeader.generationSignature shouldBe originalBlock.header.generationSignature
      challengedHeader.featureVotes shouldBe originalBlock.header.featureVotes
      challengedHeader.generator shouldBe originalBlock.header.generator
      challengedHeader.stateHash shouldBe originalBlock.header.stateHash
      challengedHeader.headerSignature shouldBe originalBlock.signature
    }
  }

  property("NODE-913. Challenging of block with correct state hash is impossible") {
    val sender           = TxHelpers.signer(1)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey),
      // A block is only judged as a challenge once it is the better competitor, and that is a matter of PoS delay:
      // the challenger has to out-weigh the miner it challenges to get as far as the check under test.
      balances = AddrWithBalance(challengingMiner.address, ENOUGH_AMT) +: (poolBalances ++ AddrWithBalance.enoughBalances(sender, defaultSigner))
    ) { d =>

      val originalBlock = d.createBlock(
        Seq(TxHelpers.transfer(sender, challengingMiner.address, 1.waves)),
        strictTime = true
      )

      val challengingBlock = d.createChallengingBlock(
        challengingMiner.signingKey,
        originalBlock,
        ref = Some(d.lastBlockId),
        // An explicit timestamp only takes effect under strictTime, and it has to be better than the block it
        // challenges for the appender to judge the challenge itself rather than dismiss a worse competitor
        strictTime = true,
        timestamp = Some(originalBlock.header.timestamp - 1)
      )

      d.appendBlockE(challengingBlock) shouldBe Left(GenericError("Invalid block challenge"))
      d.appendBlockE(originalBlock) should beRight
      d.appendBlockE(challengingBlock) shouldBe Left(GenericError("Invalid block challenge"))
    }
  }

  property("NODE-914. Blocks API should return correct data for challenging block") {
    val sender = TxHelpers.signer(1)
    // Not defaultSigner: a challenged miner's balance is banned, and `liquidAndSolidAssert` below needs someone who
    // can still generate the block that makes the state solid.
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender, defaultSigner) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>
      val originalBlock    = d.createBlock(strictTime = true, generator = challengedMiner, stateHash = Some(Some(invalidStateHash)))
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)
      val blockHeight      = 2

      d.appendBlockE(challengingBlock) should beRight

      val route = new BlocksApiRoute(
        d.settings.restAPISettings,
        d.blocksApi,
        testTime,
        new RouteTimeout(60.seconds)(using sharedScheduler)
      ).route

      Get("/blocks/last") ~> route ~> check {
        checkBlockJson(responseAs[JsObject], challengingBlock)
      }
      Get("/blocks/headers/last") ~> route ~> check {
        checkBlockJson(responseAs[JsObject], challengingBlock)
      }

      d.liquidAndSolidAssert { () =>
        Get(s"/blocks/at/$blockHeight") ~> route ~> check {
          checkBlockJson(responseAs[JsObject], challengingBlock)
        }
        Get(s"/blocks/seq/$blockHeight/$blockHeight") ~> route ~> check {
          checkBlockJson(responseAs[JsArray].value.head.as[JsObject], challengingBlock)
        }

        Get(s"/blocks/height/${challengingBlock.id()}") ~> route ~> check {
          (responseAs[JsObject] \ "height").as[Int] shouldBe blockHeight
        }
        Get(s"/blocks/address/${challengingMiner.address}/$blockHeight/$blockHeight") ~> route ~> check {
          checkBlockJson(responseAs[JsArray].value.head.as[JsObject], challengingBlock)
        }
        Get(s"/blocks/headers/at/$blockHeight") ~> route ~> check {
          checkBlockJson(responseAs[JsObject], challengingBlock)
        }
        Get(s"/blocks/headers/seq/$blockHeight/$blockHeight") ~> route ~> check {
          checkBlockJson(responseAs[JsArray].value.head.as[JsObject], challengingBlock)
        }

        Get(s"/blocks/${challengingBlock.id()}") ~> route ~> check {
          checkBlockJson(responseAs[JsObject], challengingBlock)
        }
      }
    }
  }

  property("NODE-915. /transactions/address should return elided transactions") {
    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>

      val challengedBlockTx = TxHelpers.transfer(challengedMiner, amount = 1001.waves)
      val originalBlock = d.createBlock(
        Seq(challengedBlockTx),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)

      d.appendBlockE(challengingBlock) should beRight
      d.transactionsApi.transactionById(challengedBlockTx.id()).map(_.status).contains(TxMeta.Status.Elided) shouldBe true

      val route = new TransactionsApiRoute(
        d.settings.restAPISettings,
        d.commonApi.transactions,
        d.wallet,
        d.generatorKeys,
        d.blockchain,
        () => d.blockchain,
        () => 0,
        (t, _) => d.commonApi.transactions.broadcastTransaction(t),
        testTime,
        new RouteTimeout(60.seconds)(using sharedScheduler)
      ).route

      d.liquidAndSolidAssert { () =>
        Get(s"/transactions/address/${challengedMiner.toAddress}/limit/10") ~> route ~> check {
          val txResponse = responseAs[JsArray].value.head.as[JsArray].value.head.as[JsObject]
          txResponse shouldBe challengedBlockTx
            .json() ++ Json.obj("height" -> 2, "spentComplexity" -> 0, "applicationStatus" -> ApplicationStatus.Elided)
        }
      }
    }
  }

  property("NODE-916. /transactions/info should return correct data for elided transactions") {
    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>

      val challengedBlockTx = TxHelpers.transfer(challengedMiner, amount = 1001.waves)
      val originalBlock = d.createBlock(
        Seq(challengedBlockTx),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)

      d.appendBlockE(challengingBlock) should beRight
      d.transactionsApi.transactionById(challengedBlockTx.id()).map(_.status).contains(TxMeta.Status.Elided) shouldBe true

      val route = new TransactionsApiRoute(
        d.settings.restAPISettings,
        d.commonApi.transactions,
        d.wallet,
        d.generatorKeys,
        d.blockchain,
        () => d.blockchain,
        () => 0,
        (t, _) => d.commonApi.transactions.broadcastTransaction(t),
        testTime,
        new RouteTimeout(60.seconds)(using sharedScheduler)
      ).route

      val extraFields =
        Json.obj("height" -> 2, "spentComplexity" -> 0, "applicationStatus" -> ApplicationStatus.Elided)
      val expectedResponse = challengedBlockTx.json() ++ extraFields

      d.liquidAndSolidAssert { () =>
        Get(s"/transactions/info/${challengedBlockTx.id()}") ~> route ~> check {
          responseAs[JsObject] shouldBe expectedResponse
        }

        Post("/transactions/info", FormData("id" -> challengedBlockTx.id().toString)) ~> route ~> check {
          responseAs[JsArray].value.head.as[JsObject] shouldBe expectedResponse
        }

        Post(
          "/transactions/info",
          HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(challengedBlockTx.id().toString)).toString())
        ) ~> route ~> check {
          responseAs[JsArray].value.head.as[JsObject] shouldBe expectedResponse
        }
      }
    }
  }

  property("NODE-917. /transactions/status should return correct data for elided transactions") {
    def checkTxStatus(tx: Transaction, confirmations: Int, route: Route): Assertion = {
      val expectedResponse = Json.obj(
        "status"            -> Status.Confirmed,
        "height"            -> 2,
        "confirmations"     -> confirmations,
        "applicationStatus" -> ApplicationStatus.Elided,
        "spentComplexity"   -> 0,
        "id"                -> tx.id().toString
      )

      Get(s"/transactions/status/${tx.id()}") ~> route ~> check {
        responseAs[JsObject] shouldBe expectedResponse
      }

      Post("/transactions/status", FormData("id" -> tx.id().toString)) ~> route ~> check {
        responseAs[JsArray].value.head.as[JsObject] should matchJson(expectedResponse)
      }

      Post(
        "/transactions/status",
        HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(tx.id().toString)).toString())
      ) ~> route ~> check {
        responseAs[JsArray].value.head.as[JsObject] should matchJson(expectedResponse)
      }
    }

    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>

      val challengedBlockTx = TxHelpers.transfer(challengedMiner, amount = 1001.waves)
      val originalBlock = d.createBlock(
        Seq(challengedBlockTx),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)

      d.appendBlockE(challengingBlock) should beRight
      d.transactionsApi.transactionById(challengedBlockTx.id()).map(_.status).contains(TxMeta.Status.Elided) shouldBe true

      val route = new TransactionsApiRoute(
        d.settings.restAPISettings,
        d.commonApi.transactions,
        d.wallet,
        d.generatorKeys,
        d.blockchain,
        () => d.blockchain,
        () => 0,
        (t, _) => d.commonApi.transactions.broadcastTransaction(t),
        testTime,
        new RouteTimeout(60.seconds)(using sharedScheduler)
      ).route

      checkTxStatus(challengedBlockTx, 0, route)
      d.appendBlock()
      checkTxStatus(challengedBlockTx, 1, route)
    }
  }

  property("NODE-918. Addresses API should return correct balances for challenged and challenging miners") {
    // A committed generator's deposit is part of its regular balance and out of everything else, so `available` is not
    // `regular` here as it is for an ordinary account.
    def checkBalances(
        address: Address,
        expectedRegular: Long,
        expectedEffective: Long,
        expectedGenerating: Long,
        height: Int,
        route: Route,
        expectedAvailable: Long
    ): Assertion = {
      Get(s"/addresses/balance/details/$address") ~> route ~> check {
        val balance = responseAs[JsObject]
        (balance \ "regular").as[Long] shouldBe expectedRegular
        (balance \ "generating").as[Long] shouldBe expectedGenerating
        (balance \ "available").as[Long] shouldBe expectedAvailable
        (balance \ "effective").as[Long] shouldBe expectedEffective
      }
      Get(s"/addresses/balance/$address") ~> route ~> check {
        val balance = responseAs[JsObject]
        (balance \ "balance").as[Long] shouldBe expectedRegular
      }
      Get(s"/addresses/balance?address=$address&height=$height") ~> route ~> check {
        val balance = responseAs[JsArray].value.head.as[JsObject]
        (balance \ "id").as[String] shouldBe address.toString
        (balance \ "balance").as[Long] shouldBe expectedRegular
      }
      Post(
        "/addresses/balance",
        HttpEntity(ContentTypes.`application/json`, Json.obj("height" -> height, "addresses" -> Seq(address)).toString())
      ) ~> route ~> check {
        val balance = responseAs[JsArray].value.head.as[JsObject]
        (balance \ "id").as[String] shouldBe address.toString
        (balance \ "balance").as[Long] shouldBe expectedRegular
      }
      Get(s"/addresses/effectiveBalance/$address") ~> route ~> check {
        val balance = responseAs[JsObject]
        (balance \ "balance").as[Long] shouldBe expectedEffective
      }
      Get(s"/addresses/effectiveBalance/$address/1000") ~> route ~> check {
        val balance = responseAs[JsObject]
        (balance \ "balance").as[Long] shouldBe expectedGenerating
      }
    }

    val sender          = TxHelpers.signer(1)
    val challengedMiner = TxHelpers.signer(2)
    // Neither miner comes from the pool, and both are credited in the genesis snapshot rather than by transfer: the
    // balances below are what this test asserts on, so nothing else may contribute to them.
    val challengingKey         = TxHelpers.signer(941)
    val challengingMiner       = MiningAccount(challengingKey, TxHelpers.vrfKeyOf(challengingKey), TxHelpers.blsKeyOf(challengingKey))
    val deposit                = CommitToGenerationTransaction.DepositInWavelets
    val initChallengingBalance = 1000.waves
    val initChallengedBalance  = 2000.waves

    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengedMiner, challengingKey),
      balances = Seq(
        AddrWithBalance(challengingMiner.address, initChallengingBalance + deposit),
        AddrWithBalance(challengedMiner.toAddress, initChallengedBalance + deposit)
      ) ++ poolBalances ++ AddrWithBalance.enoughBalances(sender)
    ) { d =>
      (1 to 1000).foreach(_ => d.appendBlock())

      val originalBlock    = d.createBlock(strictTime = true, generator = challengedMiner, stateHash = Some(Some(invalidStateHash)))
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)

      val route = new AddressApiRoute(
        d.settings.restAPISettings,
        d.wallet,
        d.generatorKeys,
        d.blockchain,
        DummyTransactionPublisher.accepting,
        testTime,
        Scheduler.global,
        new RouteTimeout(60.seconds)(using sharedScheduler),
        d.accountsApi,
        1000
      ).route

      checkBalances(
        challengingMiner.address,
        initChallengingBalance + deposit,
        initChallengingBalance,
        initChallengingBalance,
        1001,
        route,
        expectedAvailable = initChallengingBalance
      )
      checkBalances(
        challengedMiner.toAddress,
        initChallengedBalance + deposit,
        initChallengedBalance,
        initChallengedBalance,
        1001,
        route,
        expectedAvailable = initChallengedBalance
      )

      d.appendBlockE(challengingBlock) should beRight

      checkBalances(
        challengingMiner.address,
        initChallengingBalance + deposit + getLastBlockMinerReward(d),
        initChallengingBalance + getLastBlockMinerReward(d),
        initChallengingBalance,
        1002,
        route,
        expectedAvailable = initChallengingBalance + getLastBlockMinerReward(d)
      )
      checkBalances(challengedMiner.toAddress, initChallengedBalance + deposit, 0, 0, 1002, route, expectedAvailable = initChallengedBalance)

      d.appendBlock()

      checkBalances(
        challengingMiner.address,
        initChallengingBalance + deposit + getLastBlockMinerReward(d),
        initChallengingBalance + getLastBlockMinerReward(d),
        initChallengingBalance,
        1003,
        route,
        expectedAvailable = initChallengingBalance + getLastBlockMinerReward(d)
      )
      checkBalances(
        challengedMiner.toAddress,
        initChallengedBalance + deposit,
        initChallengedBalance,
        0,
        1003,
        route,
        expectedAvailable = initChallengedBalance
      )
    }
  }

  property("NODE-919. Transactions from challenging block should have unconfirmed status when creating of block is in progress") {
    def checkTxsStatus(txs: Seq[Transaction], expectedStatus: String, route: Route): Unit = {
      txs.foreach { tx =>
        Get(s"/transactions/status/${tx.id()}") ~> route ~> check {
          (responseAs[JsObject] \ "status").as[String] shouldBe expectedStatus
        }

        Post("/transactions/status", FormData("id" -> tx.id().toString)) ~> route ~> check {
          (responseAs[JsArray].value.head.as[JsObject] \ "status").as[String] shouldBe expectedStatus
        }

        Post(
          "/transactions/status",
          HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(tx.id().toString)).toString())
        ) ~> route ~> check {
          (responseAs[JsArray].value.head.as[JsObject] \ "status").as[String] shouldBe expectedStatus
        }
      }
    }

    val challengedMiner  = TxHelpers.signer(1)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(defaultSigner) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>
      val channels      = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
      val promise       = Promise[Unit]()
      val lockChallenge = new ReentrantLock()
      lockChallenge.lock()

      val txs = Seq(TxHelpers.transfer(amount = 1.waves), TxHelpers.transfer(amount = 2.waves))
      val invalidBlock =
        d.createBlock(
          txs,
          strictTime = true,
          generator = challengedMiner,
          stateHash = Some(Some(invalidStateHash)),
          timestamp = Some(Long.MaxValue)
        )

      val blockChallenger: Option[BlockChallenger] =
        Some(
          new BlockChallengerImpl(
            d.blockchain,
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE),
            GeneratorKeys(Seq(challengerNode)),
            d.settings,
            testTime,
            d.posSelector,
            createBlockAppender(d)
          ) {
            override def pickBestAccount(accounts: Seq[((SigningKey, VrfKey), Long)]): Either[GenericError, ((SigningKey, VrfKey), Long)] = {
              promise.success(())
              lockChallenge.lock()
              val best = super.pickBestAccount(accounts)
              testTime.setTime(invalidBlock.header.timestamp.max(best.explicitGet()._2 + d.lastBlock.header.timestamp))
              best
            }
          }
        )

      val appender = BlockAppender(
        d.blockchain,
        testTime,
        d.utxPool,
        d.posSelector,
        channels,
        PeerDatabase.NoOp,
        blockChallenger,
        d.createBlockEndorser(channels),
        appenderScheduler
      )

      val route = new TransactionsApiRoute(
        d.settings.restAPISettings,
        d.commonApi.commonTransactionsApi(blockChallenger),
        d.wallet,
        d.generatorKeys,
        d.blockchain,
        () => d.blockchain,
        () => 0,
        (t, _) => d.commonApi.transactions.broadcastTransaction(t),
        testTime,
        new RouteTimeout(60.seconds)(using sharedScheduler)
      ).route

      testTime.setTime(invalidBlock.header.timestamp)
      val challengeResult = appender(new EmbeddedChannel(), invalidBlock, None).runToFuture

      Await.ready(
        promise.future.map(_ => checkTxsStatus(txs, TransactionsApiRoute.Status.Confirmed, route))(using monix.execution.Scheduler.Implicits.global),
        1.minute
      )

      lockChallenge.unlock()

      Await.ready(challengeResult, 1.minute)

      checkTxsStatus(txs, TransactionsApiRoute.Status.Confirmed, route)
    }
  }

  property("NODE-920. Challenging block signature check should fail when challenged header is replaced") {
    def createInvalidChallengingBlock(validChallengingBlock: Block, f: ChallengedHeader => ChallengedHeader): Block = {
      val validChallengedHeader = validChallengingBlock.header.challengedHeader.get

      validChallengingBlock.copy(header = validChallengingBlock.header.copy(challengedHeader = Some(f(validChallengedHeader))))
    }

    withDomain(settings, generators = allGenerators, balances = poolBalances ++ AddrWithBalance.enoughBalances(defaultSigner)) { d =>
      val challengingMiner      = generateMiningAccount
      val originalBlock         = d.createBlock(strictTime = true, stateHash = Some(Some(invalidStateHash)))
      val validChallengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)

      validChallengingBlock.signatureValid() shouldBe true

      val validChallengedHeader = validChallengingBlock.header.challengedHeader.get

      createInvalidChallengingBlock(validChallengingBlock, _.copy(timestamp = validChallengedHeader.timestamp + 1)).signatureValid() shouldBe false
      createInvalidChallengingBlock(validChallengingBlock, _.copy(baseTarget = validChallengedHeader.baseTarget + 1)).signatureValid() shouldBe false
      createInvalidChallengingBlock(
        validChallengingBlock,
        _.copy(generationSignature = ByteStr.fill(validChallengedHeader.generationSignature.size)(1))
      ).signatureValid() shouldBe false
      createInvalidChallengingBlock(validChallengingBlock, _.copy(featureVotes = Seq(1))).signatureValid() shouldBe false
      createInvalidChallengingBlock(validChallengingBlock, _.copy(generator = PublicKey(TxHelpers.signer(100).publicKey())))
        .signatureValid() shouldBe false
      createInvalidChallengingBlock(validChallengingBlock, _.copy(stateHash = Some(ByteStr.fill(DigestLength)(2)))).signatureValid() shouldBe false
      createInvalidChallengingBlock(validChallengingBlock, _.copy(headerSignature = ByteStr.fill(validChallengedHeader.headerSignature.size)(1)))
        .signatureValid() shouldBe false
    }
  }

  property("NODE-934. Block with better timestamp should replace current liquid block regardless it is challenging or not") {
    val challengedMiner        = TxHelpers.signer(1)
    val sender                 = TxHelpers.signer(2)
    val currentBlockSender     = TxHelpers.signer(3)
    val bestBlockSender        = TxHelpers.signer(4)
    val challengingMiner       = generateMiningAccount
    val betterChallengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators =
        allGenerators ++ Seq(challengingMiner.signingKey, betterChallengingMiner.signingKey, challengedMiner, currentBlockSender, bestBlockSender),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender, currentBlockSender, bestBlockSender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(betterChallengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>

      val txs       = Seq(TxHelpers.transfer(sender, TxHelpers.defaultAddress, amount = 1.waves))
      val bestBlock = d.createBlock(txs, generator = bestBlockSender)
      val originalBlock =
        d.createBlock(
          txs,
          generator = challengedMiner,
          stateHash = Some(Some(invalidStateHash))
        )

      val betterChallengingBlock = d.createChallengingBlock(betterChallengingMiner.signingKey, originalBlock)
      val worseChallengingBlock  = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)
      val currentBlock           = d.createBlock(txs, generator = currentBlockSender)

      bestBlock.header.timestamp < betterChallengingBlock.header.timestamp shouldBe true
      betterChallengingBlock.header.timestamp < worseChallengingBlock.header.timestamp shouldBe true
      worseChallengingBlock.header.timestamp < currentBlock.header.timestamp shouldBe true

      d.appendBlockE(currentBlock) should beRight
      val expectedHeight = d.blockchain.height

      // replace block without challenge with challenging block
      d.appendBlockE(worseChallengingBlock) should beRight
      d.lastBlock shouldBe worseChallengingBlock
      d.blockchain.height shouldBe expectedHeight

      d.appendBlockE(currentBlock) should produce(
        s"Competitors liquid block $currentBlock(timestamp=${currentBlock.header.timestamp}) is not better than existing"
      )
      d.lastBlock shouldBe worseChallengingBlock
      d.blockchain.height shouldBe expectedHeight

      // replace challenging block with better challenging block
      d.appendBlockE(betterChallengingBlock) should beRight
      d.lastBlock shouldBe betterChallengingBlock
      d.blockchain.height shouldBe expectedHeight

      d.appendBlockE(worseChallengingBlock) should produce(
        s"Competitors liquid block $worseChallengingBlock(timestamp=${worseChallengingBlock.header.timestamp}) is not better than existing"
      )
      d.lastBlock shouldBe betterChallengingBlock
      d.blockchain.height shouldBe expectedHeight

      // replace challenging block with block without challenge
      d.appendBlockE(bestBlock) should beRight
      d.lastBlock shouldBe bestBlock
      d.blockchain.height shouldBe expectedHeight

      d.appendBlockE(betterChallengingBlock) should produce(
        s"Competitors liquid block $betterChallengingBlock(timestamp=${betterChallengingBlock.header.timestamp}) is not better than existing"
      )
      d.lastBlock shouldBe bestBlock
      d.blockchain.height shouldBe expectedHeight
    }
  }

  property("NODE-1173. Txs from applied challenging block should be removed from UTX") {
    val challengedMiner  = TxHelpers.signer(1)
    val sender           = TxHelpers.signer(2)
    val challengingMiner = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>
      val txs = Seq(TxHelpers.transfer(sender, amount = 1), TxHelpers.transfer(sender, amount = 2))
      val originalBlock =
        d.createBlock(
          txs,
          strictTime = true,
          generator = challengedMiner,
          stateHash = Some(Some(invalidStateHash)),
          timestamp = Some(Long.MaxValue)
        )

      txs.foreach(d.utxPool.putIfNew(_))
      d.utxPool.size shouldBe txs.size

      appendAndCheck(originalBlock, d, Seq(challengingMiner)) { _ =>
        d.utxPool.size shouldBe 0
      }
    }
  }

  property("NODE-1176. Generating balance of challenged miner should be restored after 1000 blocks") {
    def tryToAppendBlock(
        d: Domain,
        generator: SigningKey,
        appender: Block => Task[Either[ValidationError, BlockApplyResult]]
    ): Either[ValidationError, BlockApplyResult] = {
      val block = d.createBlock(strictTime = true, generator = generator)
      testTime.setTime(block.header.timestamp)
      appender(block).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
    }

    val challengedMiner        = TxHelpers.signer(1)
    val sender                 = TxHelpers.signer(2)
    val challengedMinerBalance = 2000.waves
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengedMiner),
      // Credited in genesis: the assertions below are about this exact balance being banned and restored
      balances = AddrWithBalance(challengedMiner.toAddress, challengedMinerBalance + CommitToGenerationTransaction.DepositInWavelets) +:
        (poolBalances ++ AddrWithBalance.enoughBalances(sender))
    ) { d =>
      val challengingMiner = generateMiningAccount
      d.appendBlock(TxHelpers.transfer(sender, challengingMiner.address, 1000.waves))
      (1 to 999).foreach(_ => d.appendBlock())
      val transferAmount   = 1.waves
      val txs              = Seq(TxHelpers.transfer(sender, challengedMiner.toAddress, transferAmount))
      val originalBlock    = d.createBlock(txs, strictTime = true, generator = challengedMiner, stateHash = Some(Some(invalidStateHash)))
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock)

      val appender = createBlockAppender(d)

      val genBalanceError = s"${challengedMiner.toAddress} is not allowed to generate a block"

      d.appendBlockE(challengingBlock) should beRight
      d.accountsApi.balanceDetails(challengedMiner.toAddress).explicitGet().generating shouldBe 0L
      tryToAppendBlock(d, challengedMiner, appender) should produce(genBalanceError)
      (1 to 999).foreach { _ =>
        d.appendBlock()
        d.accountsApi.balanceDetails(challengedMiner.toAddress).explicitGet().generating shouldBe 0L
        tryToAppendBlock(d, challengedMiner, appender) should produce(genBalanceError)
      }
      d.appendBlock()
      d.accountsApi.balanceDetails(challengedMiner.toAddress).explicitGet().generating shouldBe challengedMinerBalance + transferAmount
      tryToAppendBlock(d, challengedMiner, appender) should beRight
    }
  }

  property("NODE-1177. Transactions should return to UTX after replacing current liquid block by better block") {
    val challengedMiner    = TxHelpers.signer(1)
    val sender             = TxHelpers.signer(2)
    val currentBlockSender = TxHelpers.signer(3)
    val betterBlockSender  = TxHelpers.signer(4)
    val challengingMiner   = generateMiningAccount
    withDomain(
      settings,
      generators = allGenerators ++ Seq(challengingMiner.signingKey, challengedMiner, betterBlockSender),
      balances = poolBalances ++ AddrWithBalance.enoughBalances(sender, currentBlockSender, betterBlockSender) ++ Seq(
        AddrWithBalance(challengingMiner.address, challengerBalance),
        AddrWithBalance(challengedMiner.toAddress, challengerBalance)
      )
    ) { d =>

      val txs = Seq(
        TxHelpers.transfer(sender, TxHelpers.defaultAddress, amount = 1.waves),
        TxHelpers.transfer(sender, TxHelpers.defaultAddress, amount = 2.waves)
      )
      val betterBlock = d.createBlock(strictTime = true, generator = betterBlockSender)
      val originalBlock = d.createBlock(
        txs,
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner.signingKey, originalBlock, strictTime = true, timestamp = Some(Long.MaxValue))

      d.appendBlockE(challengingBlock) should beRight
      d.lastBlock shouldBe challengingBlock
      d.utxPool.size shouldBe 0

      val appender = createBlockAppender(d)
      testTime.setTime(betterBlock.header.timestamp)
      appender(betterBlock).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s")) should beRight
      d.lastBlock shouldBe betterBlock
      d.utxPool.size shouldBe txs.size
      d.utxPool.all.toSet shouldBe txs.toSet
    }
  }

  /** @param challengers
    *   The accounts the challenging node mines with. A test that asserts on the challenging block's generator has to
    *   name the very accounts it expects, so this defaults to the node's own only for the tests that do not care.
    */
  private def appendAndCheck(block: Block, d: Domain, challengers: Seq[MiningAccount] = Seq(challengerNode))(check: Block => Unit): Unit = {
    val channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    val channel1 = new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp))
    val channel2 = new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp))
    channels.add(channel1)
    channels.add(channel2)
    val appenderWithChallenger: Block => Task[Unit] =
      BlockAppender(
        d.blockchain,
        testTime,
        d.utxPool,
        d.posSelector,
        channels,
        PeerDatabase.NoOp,
        Some(createBlockChallenger(d, channels, challengers)),
        d.createBlockEndorser(channels),
        appenderScheduler
      )(channel2, _, None)

    testTime.setTime(Long.MaxValue)
    appenderWithChallenger(block).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
    if (!channel1.outboundMessages().isEmpty)
      check(PBBlockSpec.deserializeData(channel1.readOutbound[RawBytes]().data).get)
    else fail("block should be defined")
  }

  private def createBlockAppender(d: Domain): Block => Task[Either[ValidationError, BlockApplyResult]] =
    BlockAppender(d.blockchain, testTime, d.utxPool, d.posSelector, BlockEndorser.Disabled, appenderScheduler)(_, None)

  private def createMicroBlockAppender(d: Domain): (Channel, MicroBlock) => Task[Unit] = { (ch, mb) =>
    val channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

    MicroblockAppender(d.blockchain, d.utxPool, channels, PeerDatabase.NoOp, Some(createBlockChallenger(d, channels)), appenderScheduler)(
      ch,
      MicroblockData(None, mb, Coeval.now(Set.empty)),
      None
    )
  }

  private def createBlockChallenger(
      d: Domain,
      allChannels: ChannelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE),
      challengers: Seq[MiningAccount] = Seq(challengerNode)
  ): BlockChallenger =
    new BlockChallengerImpl(
      d.blockchain,
      allChannels,
      GeneratorKeys(challengers),
      d.settings,
      testTime,
      d.posSelector,
      createBlockAppender(d)
    )

  private def checkBlockJson(blockJson: JsObject, sourceBlock: Block) = {
    val chHeader = sourceBlock.header.challengedHeader.get

    (blockJson \ "timestamp").as[Long] shouldBe sourceBlock.header.timestamp
    (blockJson \ "reference").as[String] shouldBe sourceBlock.header.reference.toString
    (blockJson \ "baseTarget").as[Long] shouldBe sourceBlock.header.baseTarget
    (blockJson \ "generationSignature").as[String] shouldBe sourceBlock.header.generationSignature.toString
    (blockJson \ "transactionsRoot").as[String] shouldBe sourceBlock.header.transactionsRoot.toString
    (blockJson \ "id").as[String] shouldBe sourceBlock.id().toString
    (blockJson \ "features").as[Seq[Short]] shouldBe sourceBlock.header.featureVotes
    (blockJson \ "generator").as[String] shouldBe sourceBlock.header.generator.toAddress.toString
    (blockJson \ "generatorPublicKey").as[String] shouldBe sourceBlock.header.generator.toString
    (blockJson \ "stateHash").as[String] shouldBe sourceBlock.header.stateHash.get.toString
    (blockJson \ "challengedHeader" \ "headerSignature").as[String] shouldBe chHeader.headerSignature.toString
    (blockJson \ "challengedHeader" \ "features").as[Seq[Short]] shouldBe chHeader.featureVotes
    (blockJson \ "challengedHeader" \ "generator").as[String] shouldBe chHeader.generator.toAddress.toString
    (blockJson \ "challengedHeader" \ "generatorPublicKey").as[String] shouldBe chHeader.generator.toString
    (blockJson \ "challengedHeader" \ "stateHash").as[String] shouldBe chHeader.stateHash.get.toString
  }

  private def rollbackMiddleScenario(d: Domain, challengedMiner: SigningKey, txs: Seq[Transaction]): Assertion = {
    (1 to 5).foreach(_ => d.appendBlock())

    val originalBlock = d.createBlock(
      txs,
      strictTime = true,
      generator = challengedMiner,
      stateHash = Some(Some(invalidStateHash)),
      timestamp = Some(Long.MaxValue)
    )

    appendAndCheck(originalBlock, d)(_ => (1 to 10).foreach(_ => d.appendBlock()))

    d.blockchain.height shouldBe 17
  }

  private def rollbackLastScenario(d: Domain, challengedMiner: SigningKey, txs: Seq[Transaction]): Assertion = {
    (1 to 5).foreach(_ => d.appendBlock())

    val originalBlock = d.createBlock(
      txs,
      strictTime = true,
      generator = challengedMiner,
      stateHash = Some(Some(invalidStateHash)),
      timestamp = Some(Long.MaxValue)
    )

    appendAndCheck(originalBlock, d)(_ => ())

    d.blockchain.height shouldBe 7
  }

  private def rollbackActivationHeightScenario(d: Domain, challengedMiner: SigningKey, txs: Seq[Transaction]): Assertion = {
    (1 to 6).foreach(_ => d.appendBlock())

//    d.blockchain.isFeatureActivated(BlockchainFeatures.LightNode) shouldBe false

    val originalBlock = d.createBlock(
      txs,
      strictTime = true,
      generator = challengedMiner,
      stateHash = Some(Some(invalidStateHash)),
      timestamp = Some(Long.MaxValue)
    )

    appendAndCheck(originalBlock, d)(_ => ())

    d.blockchain.height shouldBe 8
  }

  private def getLastBlockMinerReward(d: Domain): Long =
    getLastBlockRewards(d).miner

  private def getLastBlockRewards(d: Domain): BlockRewardShares =
    BlockRewardCalculator
      .rewardSharesAt(
        Height(d.blockchain.height),
        d.blockchain.settings.rewardsSettings.initial,
        d.blockchain.settings.functionalitySettings.daoAddressParsed.toOption.flatten
      )
}
