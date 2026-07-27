package com.wavesplatform.history

import cats.implicits.catsSyntaxOption
import cats.syntax.traverse.*
import com.wavesplatform.account.Address
import com.wavesplatform.api.BlockMeta
import com.wavesplatform.api.common.*
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.block.{Block, BlockSnapshot, ChallengedHeader, FinalizationVoting, MicroBlock}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.consensus.nxt.NxtLikeConsensusBlockData
import com.wavesplatform.consensus.PoSSelector
import com.wavesplatform.database.{DBExt, Keys, RDB, RocksDBWriter}
import com.wavesplatform.events.BlockchainUpdateTriggers
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.mining.{BlockChallenger, BlockChallengerImpl, MiningAccount}
import com.wavesplatform.network.{MessageCodec, PeerDatabase}
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.*
import com.wavesplatform.state.BlockchainUpdaterImpl.BlockApplyResult
import com.wavesplatform.state.BlockchainUpdaterImpl.BlockApplyResult.{Applied, Ignored}
import com.wavesplatform.state.appender.{BlockAppender, findBlockAndGetGenerators}
import com.wavesplatform.state.diffs.{BlockDiffer, TransactionDiffer}
import com.wavesplatform.test.TestTime
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.smart.script.trace.TracedResult
import com.wavesplatform.utils.{EthEncoding, Schedulers, SystemTime}
import com.wavesplatform.utx.UtxPoolImpl
import com.wavesplatform.wallet.Wallet
import com.wavesplatform.{Application, TestValues, crypto}
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.group.{ChannelGroup, DefaultChannelGroup}
import io.netty.util.concurrent.GlobalEventExecutor
import monix.eval.Task
import monix.execution.ExecutionModel.SynchronousExecution
import monix.execution.schedulers.SchedulerService
import org.rocksdb.RocksDB
import org.scalatest.matchers.should.Matchers.*
import tech.hearth.crypto.{SigningKey, VrfKey}

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try
import scala.util.control.NonFatal

case class Domain(
    rdb: RDB,
    blockchainUpdater: CompleteBlockchainUpdater,
    rocksDBWriter: RocksDBWriter,
    settings: WavesSettings,
    testTime: TestTime = TestTime()
) {
  import Domain.*
  private given scheduler: SchedulerService = Schedulers.singleThread("domain", executionModel = SynchronousExecution)

  val blockchain: CompleteBlockchainUpdater = blockchainUpdater

  @volatile
  var triggers: Seq[BlockchainUpdateTriggers] = Nil

  val posSelector: PoSSelector                          = PoSSelector(blockchainUpdater, None)
  def nextBlockTime(miningAccount: MiningAccount): Long = nextBlockTime(miningAccount.signingKey, miningAccount.vrfKey)
  def nextBlockTime(generator: SigningKey, vrfKey: VrfKey): Long = {
    val parentHeight = blockchain.height
    val parent       = blockchain.blockHeader(parentHeight).map(_.header).getOrElse(lastBlock.header)

    posSelector
      .getValidBlockDelay(parentHeight, vrfKey, parent.baseTarget, blockchain.generatingBalance(generator.toAddress))
      .map(_ + parent.timestamp)
      .explicitGet()
  }

  val transactionDiffer: Transaction => TracedResult[ValidationError, StateSnapshot] =
    TransactionDiffer(blockchain.lastBlockTimestamp, System.currentTimeMillis())(blockchain, _)

  val transactionDifferWithLog: Transaction => TracedResult[ValidationError, StateSnapshot] =
    TransactionDiffer(blockchain.lastBlockTimestamp, System.currentTimeMillis())(blockchain, _)

  def createDiffE(tx: Transaction): Either[ValidationError, StateSnapshot] = transactionDiffer(tx).resultE
  def createDiff(tx: Transaction): StateSnapshot                           = createDiffE(tx).explicitGet()

  lazy val utxPool: UtxPoolImpl =
    new UtxPoolImpl(SystemTime, blockchain, settings.utxSettings, settings.maxTxErrorLogSize, settings.minerSettings.enable)

  lazy val endorsementStorage: EndorsementStorage = EndorsementStorage.Disabled
  def createBlockEndorser(allChannels: ChannelGroup, storage: EndorsementStorage = endorsementStorage): BlockEndorser =
    new BlockEndorser.InMemory(settings.synchronizationSettings.maxRollback, blockchain, wallet, storage, allChannels)

  lazy val wallet: Wallet = Wallet(settings.walletSettings.copy(file = None, seed = Some(ByteStr(DefaultWalletSeed))))

  lazy val blockAppender: Block => Task[Either[ValidationError, BlockApplyResult]] =
    BlockAppender(blockchain, testTime, utxPool, posSelector, BlockEndorser.Disabled, scheduler)(_, None)
  lazy val blockChallenger: Option[BlockChallenger] =
    if (!settings.enableLightMode)
      Some(
        new BlockChallengerImpl(
          blockchain,
          new DefaultChannelGroup(GlobalEventExecutor.INSTANCE),
          Seq.empty,
          settings,
          testTime,
          posSelector,
          blockAppender
        )
      )
    else None

  object commonApi {

    /** @return
      *   Tuple of (asset, feeInAsset, feeInWaves)
      * @see
      *   [[com.wavesplatform.state.diffs.FeeValidation#getMinFee(com.wavesplatform.state.Blockchain, com.wavesplatform.transaction.Transaction)]]
      */
    def calculateFee(tx: Transaction): (Asset, Long, Long) =
      transactions.calculateFee(tx).explicitGet()

    def calculateWavesFee(tx: Transaction): Long = {
      val (Waves, _, feeInWaves) = calculateFee(tx): @unchecked
      feeInWaves
    }

    def transactionMeta(transactionId: ByteStr): TransactionMeta =
      transactions
        .transactionById(transactionId)
        .getOrElse(throw new NoSuchElementException(s"No meta for $transactionId"))

    def addressTransactions(address: Address): Seq[TransactionMeta] =
      transactions.transactionsByAddress(address, None, Set.empty, None).toListL.runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))

    def commonTransactionsApi(challenger: Option[BlockChallenger]): CommonTransactionsApi =
      CommonTransactionsApi(
        blockchainUpdater.bestLiquidSnapshot.map(Height(blockchainUpdater.height) -> _),
        rdb,
        blockchain,
        utxPool,
        challenger,
        tx => Future.successful(utxPool.putIfNew(tx)),
        Application.loadBlockAt(rdb, blockchain)
      )

    lazy val transactions: CommonTransactionsApi = commonTransactionsApi(blockChallenger)

    lazy val generatorsApi: CommonGeneratorsApi = CommonGeneratorsApi(rdb, blockchain)
  }

  def liquidState: Option[NgState] = {
    val cls   = classOf[BlockchainUpdaterImpl]
    val field = cls.getDeclaredFields.find(_.getName.endsWith("ngState")).get
    field.setAccessible(true)
    field.get(blockchain).asInstanceOf[Option[NgState]]
  }

  def liquidAndSolidAssert(doCheck: () => Unit): Unit = {
    require(liquidState.isDefined, "No liquid state is present")
    try doCheck()
    catch { case NonFatal(err) => throw new RuntimeException("Liquid check failed", err) }
    makeStateSolid()
    try doCheck()
    catch { case NonFatal(err) => throw new RuntimeException("Solid check failed", err) }
  }

  def makeStateSolid(): (Int, SortedMap[String, String]) = {
    if (liquidState.isDefined) appendBlock() // Just append empty block
    (solidStateHeight, solidStateSnapshot())
  }

  def solidStateHeight: Int = {
    rdb.db.get(Keys.height).toInt
  }

  def solidStateSnapshot(): SortedMap[String, String] = {
    val builder = SortedMap.newBuilder[String, String]
    rdb.db.iterateOver(Array.emptyByteArray, None)(e =>
      builder.addOne(EthEncoding.toHexString(e.getKey).drop(2) -> EthEncoding.toHexString(e.getValue).drop(2))
    )
    builder.result()
  }

  def lastBlock: Block = {
    blockchainUpdater.lastBlockId
      .flatMap(blockchainUpdater.liquidBlock)
      .orElse(rocksDBWriter.lastBlock)
      .getOrElse(TestBlock.create(Nil).block)
  }

  def liquidSnapshot: StateSnapshot =
    blockchainUpdater.bestLiquidSnapshot.orEmpty

  def microBlocks: Vector[MicroBlock] = blockchain.microblockIds.reverseIterator.flatMap(blockchain.microBlock).to(Vector)

  def effBalance(a: Address): Long = blockchainUpdater.effectiveBalance(a, 1000)

  def appendBlock(b: Block): BlockApplyResult = blockchainUpdater.processBlock(b).explicitGet()

  def appendBlockE(b: Block, snapshot: Option[BlockSnapshot] = None): Either[ValidationError, BlockApplyResult] =
    blockchainUpdater.processBlock(b, snapshot)

  def rollbackTo(blockId: ByteStr): DiscardedBlocks = blockchainUpdater.removeAfter(blockId).explicitGet()

  def appendMicroBlock(b: MicroBlock): BlockId = blockchainUpdater.processMicroBlock(b, None).explicitGet()

  def appendMicroBlockE(b: MicroBlock): Either[ValidationError, BlockId] = blockchainUpdater.processMicroBlock(b, None)

  def lastBlockId: ByteStr = blockchainUpdater.lastBlockId.getOrElse(randomSig)

  def carryFee(refId: ByteStr): Either[String, BlockFee] = blockchainUpdater.carryFee(refId)

  def balance(address: Address): Long               = blockchainUpdater.balance(address)
  def balance(address: Address, asset: Asset): Long = blockchainUpdater.balance(address, asset)

  def nftList(address: Address): Seq[(IssuedAsset, AssetDescription)] = rdb.db.withResource(rdb.apiHandle.handle) { resource =>
    AddressPortfolio
      .nftIterator(resource, address, blockchainUpdater.bestLiquidSnapshot.orEmpty, None, blockchainUpdater.assetDescription)
      .toSeq
      .flatten
  }

  def addressTransactions(address: Address, from: Option[ByteStr] = None): Seq[(Height, Transaction)] =
    AddressTransactions
      .allAddressTransactions(
        rdb,
        blockchainUpdater.bestLiquidSnapshot.map(Height(blockchainUpdater.height) -> _),
        address,
        None,
        Set.empty,
        from
      )
      .map { case (m, tx, _) => m.height -> tx }
      .toListL
      .runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))

  def portfolio(address: Address): Seq[(IssuedAsset, Long)] = Domain.portfolio(address, rdb.db, blockchainUpdater)

  def appendAndAssertSucceed(txs: Transaction*): Block = {
    val block = createBlock(txs)
    appendBlock(block)
    txs.foreach { tx =>
      if (!blockchain.transactionSucceeded(tx.id())) {
        throw new AssertionError(s"Should succeed: ${tx.id()}")
      }
    }
    lastBlock
  }

  def appendAndCatchError(txs: Transaction*): ValidationError = {
    val block  = createBlock(txs)
    val result = appendBlockE(block)
    txs.foreach { tx =>
      assert(blockchain.transactionInfo(tx.id()).isEmpty, s"should not pass: $tx")
    }
    result.left.getOrElse(throw new RuntimeException(s"Block appended successfully: $txs"))
  }

  def appendAndAssertFailed(txs: Transaction*): Block = {
    val block = createBlock(txs)
    appendBlockE(block) match {
      case Left(err) =>
        throw new RuntimeException(s"Should be success: $err")

      case Right(_) =>
        txs.foreach(tx => assert(!blockchain.transactionSucceeded(tx.id()), s"should fail: $tx"))
        lastBlock
    }
  }

  def appendAndAssertFailed(tx: Transaction): Block = {
    appendBlock(tx)
    assert(!blockchain.transactionSucceeded(tx.id()), s"should fail: $tx")
    lastBlock
  }

  def appendBlockE(txs: Transaction*): Either[ValidationError, BlockApplyResult] =
    createBlockE(txs).flatMap(appendBlockE(_))

  def appendBlock(txs: Transaction*): Block = {
    val block = createBlock(txs)
    appendBlock(block)
    lastBlock
  }

  def appendKeyBlock(signer: SigningKey = defaultSigner, ref: Option[ByteStr] = None): Block = {
    val block = createBlock(
      ref = ref.orElse(Some(lastBlockId)),
      generator = signer
    )
    appendBlock(block) match {
      case Applied(discardedDiffs = discardedSnapshots) =>
        utxPool.setPrioritySnapshots(discardedSnapshots)
        utxPool.cleanUnconfirmed()
      case Ignored => ()
    }

    lastBlock
  }

  def appendMicroBlockE(txs: Transaction*): Either[Throwable, BlockId] =
    Try(appendMicroBlock(txs*)).toEither

  def createMicroBlockE(
      stateHash: Option[ByteStr] = None,
      signer: Option[SigningKey] = None,
      ref: Option[ByteStr] = None,
      finalizationVoting: Option[FinalizationVoting] = None
  )(txs: Transaction*): Either[ValidationError, MicroBlock] = {
    val lastBlock   = this.lastBlock
    val blockSigner = signer.getOrElse(defaultSigner)
    val stateHashE = if (blockchain.supportsLightNodeBlockFields()) {
      stateHash
        .map(Right(_))
        .getOrElse(
          TxStateSnapshotHashBuilder
            .computeStateHash(
              txs,
              lastBlock.header.stateHash.get,
              StateSnapshot.empty,
              blockSigner,
              rocksDBWriter.lastBlockTimestamp,
              blockchain.lastBlockTimestamp.get,
              isChallenging = false,
              blockchain
            )
            .resultE
        )
        .map(Some(_))
    } else Right(None)

    for {
      sh <- stateHashE
      block <- Block.buildAndSign(
        lastBlock.header.timestamp,
        lastBlock.header.reference,
        lastBlock.header.baseTarget,
        lastBlock.header.generationSignature,
        lastBlock.transactionData ++ txs,
        blockSigner,
        lastBlock.header.featureVotes,
        sh,
        challengedHeader = None,
        FinalizationVoting.combine(lastBlock.header.finalizationVoting, finalizationVoting)
      )
      microblock <- MicroBlock.buildAndSign(
        blockSigner,
        txs,
        reference = ref.getOrElse(blockchainUpdater.lastBlockId.get),
        totalResBlockSig = block.signature,
        block.header.stateHash,
        finalizationVoting
      )
    } yield microblock
  }

  def createMicroBlock(
      stateHash: Option[ByteStr] = None,
      signer: Option[SigningKey] = None,
      ref: Option[ByteStr] = None,
      finalizationVoting: Option[FinalizationVoting] = None
  )(txs: Transaction*): MicroBlock = createMicroBlockE(stateHash, signer, ref, finalizationVoting)(txs*).explicitGet()

  def appendMicroBlock(txs: Transaction*): BlockId = {
    val mb = createMicroBlock()(txs*)
    blockchainUpdater.processMicroBlock(mb, None).explicitGet()
  }

  def rollbackTo(height: Int): Unit = {
    val blockId = blockchain.blockId(height).get
    blockchainUpdater.removeAfter(blockId).explicitGet()
  }

  def rollbackMicros(offset: Int = 1): Unit = {
    val blockId =
      blockchainUpdater.microblockIds
        .drop(offset)
        .headOption
        .getOrElse(throw new IllegalStateException("Insufficient count of microblocks"))

    blockchainUpdater.removeAfter(blockId).explicitGet()
  }

  def createBlock(
      txs: Seq[Transaction] = Nil,
      ref: Option[ByteStr] = blockchainUpdater.lastBlockId,
      strictTime: Boolean = false,
      generator: SigningKey = defaultSigner,
      // Defaults to the key this generator committed - see TxHelpers.vrfKeyOf - because a block only verifies
      // against the VRF key registered for its generator
      vrfKey: Option[VrfKey] = None,
      stateHash: Option[Option[ByteStr]] = None,
      challengedHeader: Option[ChallengedHeader] = None,
      timestamp: Option[Long] = None,
      finalizationVoting: Option[FinalizationVoting] = None
  ): Block =
    createBlockE(txs, ref, strictTime, generator, vrfKey, stateHash, challengedHeader, timestamp, finalizationVoting)
      .explicitGet()

  def createBlockE(
      txs: Seq[Transaction],
      ref: Option[ByteStr] = blockchainUpdater.lastBlockId,
      strictTime: Boolean = false,
      generator: SigningKey = defaultSigner,
      vrfKeyOpt: Option[VrfKey] = None,
      stateHash: Option[Option[ByteStr]] = None,
      challengedHeader: Option[ChallengedHeader] = None,
      timestamp: Option[Long] = None,
      finalizationVoting: Option[FinalizationVoting] = None
  ): Either[ValidationError, Block] = {
    val vrfKey    = vrfKeyOpt.getOrElse(TxHelpers.vrfKeyOf(generator))
    val reference = ref.getOrElse(randomSig)

    val parentHeight     = ref.flatMap(blockchain.heightOf).getOrElse(blockchain.height)
    val parent           = blockchain.blockHeader(parentHeight).map(_.header).getOrElse(lastBlock.header)
    val greatGrandParent = blockchain.blockHeader(parentHeight - 2).map(_.header)

    for {
      resultTimestamp <-
        if (blockchain.height > 0) {
          timestamp
            .map(Right(_))
            .getOrElse(
              posSelector
                .getValidBlockDelay(
                  blockchain.height,
                  vrfKey,
                  parent.baseTarget,
                  blockchain.generatingBalance(generator.toAddress)
                )
                .map(_ + parent.timestamp)
            )
        } else
          Right(testTime.getTimestamp() - (1 hour).toMillis)
      consensus <-
        if (blockchain.height > 0)
          posSelector
            .consensusData(
              vrfKey,
              parentHeight,
              settings.blockchainSettings.genesisSettings.averageBlockDelay,
              parent.baseTarget,
              parent.timestamp,
              greatGrandParent.map(_.timestamp),
              resultTimestamp
            )
        else Right(NxtLikeConsensusBlockData(60, generationSignature))
      resultBt = consensus.baseTarget
      blockWithoutStateHash <- Block
        .buildAndSign(
          if (strictTime) resultTimestamp else testTime.getTimestamp(),
          reference,
          resultBt,
          consensus.generationSignature,
          txs = txs,
          generator,
          featureVotes = Nil,
          stateHash = None,
          challengedHeader,
          finalizationVoting = finalizationVoting
        )
      resultStateHash <- stateHash.map(Right(_)).getOrElse {
        if (blockchain.supportsLightNodeBlockFields(blockchain.height + 1)) {
          val hitSource = posSelector.validateGenerationSignature(blockWithoutStateHash).getOrElse(blockWithoutStateHash.header.generationSignature)
          val blockchainWithNewBlock =
            SnapshotBlockchain(blockchain, StateSnapshot.empty, blockWithoutStateHash, hitSource, BlockFee.empty, blockchain.computeNextReward, None)
          // todo: this does not seem to work when ref is not to a liquid block
          val prevStateHash = blockchain.lastStateHash(Some(blockWithoutStateHash.header.reference))

          BlockDiffer
            .createInitialBlockSnapshot(
              blockchain,
              blockWithoutStateHash.header.reference,
              generator.toAddress,
              ref.flatMap(blockchain.liquidBlock).map(_.signedHeader)
            )
            .flatMap { initSnapshot =>
              val initStateHash = BlockDiffer.computeInitialStateHash(initSnapshot, prevStateHash)

              TxStateSnapshotHashBuilder
                .computeStateHash(
                  txs,
                  initStateHash,
                  initSnapshot,
                  generator,
                  blockchain.lastBlockTimestamp,
                  blockWithoutStateHash.header.timestamp,
                  challengedHeader.nonEmpty,
                  blockchainWithNewBlock
                )
                .resultE
                .map(Some(_))
            }
        } else Right(None)
      }
      resultBlock <- Block
        .buildAndSign(
          if (strictTime) resultTimestamp else testTime.getTimestamp(),
          reference,
          resultBt,
          consensus.generationSignature,
          txs,
          generator,
          featureVotes = Nil,
          resultStateHash,
          challengedHeader,
          finalizationVoting = finalizationVoting
        )
    } yield resultBlock
  }

  def createChallengingBlock(
      challengingMiner: SigningKey,
      challengedBlock: Block,
      strictTime: Boolean = false,
      stateHash: Option[Option[ByteStr]] = None,
      ref: Option[ByteStr] = None,
      txs: Option[Seq[Transaction]] = None,
      challengedHeader: Option[ChallengedHeader] = None,
      timestamp: Option[Long] = None
  ): Block = {
    createBlock(
      txs.getOrElse(challengedBlock.transactionData),
      ref.orElse(blockchain.lastBlockId),
      strictTime = strictTime,
      generator = challengingMiner,
      stateHash = stateHash,
      challengedHeader = Some(
        challengedHeader.getOrElse(
          ChallengedHeader(
            challengedBlock.header.timestamp,
            challengedBlock.header.baseTarget,
            challengedBlock.header.generationSignature,
            Seq.empty,
            challengedBlock.sender,
            challengedBlock.header.stateHash,
            challengedBlock.signature,
            challengedBlock.header.finalizationVoting
          )
        )
      ),
      timestamp = timestamp
    )
  }

  val blocksApi: CommonBlocksApi = {
    def loadBlockMetaAt(db: RocksDB, blockchainUpdater: CompleteBlockchainUpdater)(height: Height): Option[BlockMeta] =
      Application.loadBlockMetaAt(db, blockchainUpdater)(height)

    def loadBlockInfoAt(db: RDB, blockchainUpdater: CompleteBlockchainUpdater)(
        height: Height
    ): Option[(BlockMeta, Seq[(TxMeta, Transaction)])] =
      Application.loadBlockInfoAt(db, blockchainUpdater)(height)

    CommonBlocksApi(
      settings.synchronizationSettings.maxRollback,
      blockchainUpdater,
      loadBlockMetaAt(rdb.db, blockchainUpdater),
      loadBlockInfoAt(rdb, blockchainUpdater)
    )
  }

  // noinspection ScalaStyle
  object helpers {
    def creditWavesFromDefaultSigner(to: Address, amount: Long = 1_0000_0000): Unit = {
      appendBlock(TxHelpers.transfer(to = to, amount = amount))
    }

    def transfer(account: SigningKey, to: Address, amount: Long, asset: Asset): Unit = {
      appendBlock(TxHelpers.transfer(account, to, amount, asset))
    }

    def transferAll(account: SigningKey, to: Address, asset: Asset): Unit = {
      val balanceMinusFee = {
        val balance = blockchain.balance(account.toAddress, asset)
        if (asset == Waves) balance - TestValues.fee else balance
      }
      transfer(account, to, balanceMinusFee, asset)
    }
  }

  val transactionsApi: CommonTransactionsApi = CommonTransactionsApi(
    blockchainUpdater.bestLiquidSnapshot.map(Height(blockchainUpdater.height) -> _),
    rdb,
    blockchain,
    utxPool,
    blockChallenger,
    _ => Future.successful(TracedResult(Right(true))),
    h => blocksApi.blockAtHeight(h)
  )

  val accountsApi: CommonAccountsApi = CommonAccountsApi(
    () => blockchainUpdater.snapshotBlockchain,
    rdb,
    blockchain
  )

  val assetsApi: CommonAssetsApi = CommonAssetsApi(
    () => blockchainUpdater.bestLiquidSnapshot.orEmpty,
    rdb.db,
    blockchain
  )

  val generatorsApi: CommonGeneratorsApi = CommonGeneratorsApi(rdb, blockchain)

  val appender: DefaultAppender = new DefaultAppender(this)(using scheduler)
}

object Domain {
  val DefaultWalletSeed = "wallet".getBytes

  implicit class BlockchainUpdaterExt[A <: BlockchainUpdater & Blockchain](bcu: A) {
    def processBlock(block: Block, snapshot: Option[BlockSnapshot] = None): Either[ValidationError, BlockApplyResult] = {
      val hitSourcesE =
        if (bcu.height == 0)
          Right((block.header.generationSignature, block.header.challengedHeader.map(_.generationSignature), Seq.empty))
        else {
          val parentHeight = bcu.heightOf(block.header.reference).getOrElse(bcu.height)

          val prevHs =
            if (parentHeight > 100)
              bcu.hitSource(parentHeight - 100).get
            else bcu.hitSource(parentHeight).get

          // A generator's VRF key is whatever it registered when committing, see Blockchain.vrfPublicKeyOf
          def vrfKeyOf(generator: com.wavesplatform.account.PublicKey): Either[ValidationError, ByteStr] =
            bcu.vrfPublicKeyOf(generator, Height(parentHeight)).left.map(TxValidationError.GenericError(_))

          for {
            vrfPK <- vrfKeyOf(block.header.generator)
            hs    <- crypto.verifyVRF(block.header.generationSignature, prevHs.arr, vrfPK)
            challengedHs <- block.header.challengedHeader.traverse(ch =>
              vrfKeyOf(ch.generator).flatMap(crypto.verifyVRF(ch.generationSignature, prevHs.arr, _))
            )
            data <- findBlockAndGetGenerators(bcu, block)
          } yield (hs, challengedHs, data.generatorSet)
        }

      hitSourcesE.flatMap { case (hitSource, challengedHitSource, generatorBalances) =>
        bcu.processBlock(block, hitSource, snapshot, generatorBalances, challengedHitSource)
      }
    }
  }

  def portfolio(address: Address, db: RocksDB, blockchainUpdater: CompleteBlockchainUpdater): Seq[(IssuedAsset, Long)] = db.withResource { resource =>
    AddressPortfolio
      .assetBalanceIterator(
        resource,
        address,
        blockchainUpdater.bestLiquidSnapshot.orEmpty,
        id => blockchainUpdater.assetDescription(id).exists(!_.nft)
      )
      .toSeq
      .flatten
  }
}

class DefaultAppender(d: Domain)(implicit appenderScheduler: SchedulerService) {
  private val allChannelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

  private val blockChallenger = new BlockChallengerImpl(
    d.blockchain,
    allChannelGroup,
    Seq.empty,
    d.settings,
    d.testTime,
    d.posSelector,
    appendBlock = b => d.blockAppender(b)
  )

  private val blockEndorser =
    new BlockEndorser.InMemory(d.settings.synchronizationSettings.maxRollback, d.blockchain, d.wallet, d.endorsementStorage, allChannelGroup)

  private val appenderWithCatching = BlockAppender(
    d.blockchain,
    d.testTime,
    d.utxPool,
    d.posSelector,
    new DefaultChannelGroup(GlobalEventExecutor.INSTANCE),
    PeerDatabase.NoOp,
    Some(blockChallenger),
    blockEndorser,
    appenderScheduler
  )(new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp)), _, snapshot = None)

  private val appenderWithoutCatching = BlockAppender(
    d.blockchain,
    d.testTime,
    d.utxPool,
    d.posSelector,
    blockEndorser,
    appenderScheduler
  )(_, snapshot = None)

  def appendBlock(b: Block, requireAppended: Boolean = true, adjustTestTime: Boolean = true): Unit = {
    if (adjustTestTime) adjustTime(b)
    appenderWithCatching(b).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
    if (requireAppended && d.lastBlockId != b.id()) fail(s"Can't apply block $b, see logs")
  }

  def appendBlockWithoutFallback(b: Block, adjustTestTime: Boolean = true): Either[ValidationError, BlockApplyResult] = {
    if (adjustTestTime) adjustTime(b)
    appenderWithoutCatching(b).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
  }

  def adjustTime(b: Block): Unit = {
    val challengingTimestamp = b.header.challengedHeader.fold(Long.MinValue)(_.timestamp)
    d.testTime.setTime(b.header.timestamp.max(challengingTimestamp))
  }
}
