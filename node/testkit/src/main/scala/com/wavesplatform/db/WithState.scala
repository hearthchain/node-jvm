package com.wavesplatform.db

import com.google.common.primitives.Shorts
import com.wavesplatform.account.Address
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.database.{KeyTag, RDB, RocksDBWriter, TestStorageFactory, loadActiveLeases}
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.events.BlockchainUpdateTriggers
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.history.Domain
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.lagonaki.mocks.TestBlock.BlockWithSigner
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.mining.{Miner, MiningConstraint}
import com.wavesplatform.settings.{TestFunctionalitySettings as TFS, *}
import com.wavesplatform.state.diffs.{BlockDiffer, ENOUGH_AMT}
import com.wavesplatform.state.utils.TestRocksDB
import com.wavesplatform.state.*
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxHelpers.defaultAddress
import com.wavesplatform.transaction.smart.script.trace.TracedResult
import com.wavesplatform.transaction.{BlockchainUpdater, Transaction, TxHelpers}
import com.wavesplatform.{NTPTime, TestHelpers}
import org.rocksdb.RocksDB
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.hearth.crypto.SigningKey

import java.nio.file.Files
import scala.util.Using

trait WithState extends BeforeAndAfterAll with DBCacheSettings with Matchers with NTPTime { suite: Suite =>
  protected val ignoreBlockchainUpdateTriggers: BlockchainUpdateTriggers = BlockchainUpdateTriggers.noop

  private val path  = Files.createTempDirectory(s"rocks-temp-${getClass.getSimpleName}").toAbsolutePath
  protected val rdb = RDB.open(dbSettings.copy(directory = path.toAbsolutePath.toString))

  private val MaxKey = Shorts.toByteArray(KeyTag.values.length.toShort)
  private val MinKey = new Array[Byte](2)

  protected def tempDb[A](f: RDB => A): A = {
    val path = Files.createTempDirectory(s"rocks-temp-tmp-${getClass.getSimpleName}").toAbsolutePath
    val rdb  = RDB.open(dbSettings.copy(directory = path.toAbsolutePath.toString))
    try {
      f(rdb)
    } finally {
      rdb.close()
      TestHelpers.deleteRecursively(path)
    }
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    rdb.close()
    TestHelpers.deleteRecursively(path)
  }

  protected def withRocksDBWriter[A](ws: WavesSettings)(test: RocksDBWriter => A): A = {
    try {
      val (_, rdw) = TestStorageFactory(
        ws,
        rdb,
        ntpTime,
        ignoreBlockchainUpdateTriggers
      )
      Using.resource(rdw)(test)
    } finally {
      Seq(rdb.db.getDefaultColumnFamily, rdb.txHandle.handle, rdb.txMetaHandle.handle, rdb.apiHandle.handle).foreach { cfh =>
        rdb.db.deleteRange(cfh, MinKey, MaxKey)
      }
    }
  }

  protected def withRocksDBWriter[A](bs: BlockchainSettings)(test: RocksDBWriter => A): A =
    withRocksDBWriter(TestSettings.Default.copy(blockchainSettings = bs))(test)

  def withRocksDBWriter[A](fs: FunctionalitySettings)(test: RocksDBWriter => A): A =
    withRocksDBWriter(TestRocksDB.createTestBlockchainSettings(fs))(test)

  protected def withTestState[A](ws: WavesSettings)(test: (BlockchainUpdaterImpl, RocksDBWriter) => A): A = {
    try {
      val (bcu, rdw) = TestStorageFactory(
        ws,
        rdb,
        ntpTime,
        ignoreBlockchainUpdateTriggers
      )
      Using.resource(rdw)(test(bcu, _))
    } finally {
      Seq(rdb.db.getDefaultColumnFamily, rdb.txHandle.handle, rdb.txMetaHandle.handle).foreach { cfh =>
        rdb.db.deleteRange(cfh, MinKey, MaxKey)
      }
    }
  }

  /** @param balances
    *   Accounts to credit in the genesis snapshot. There are no genesis transactions any more, so this is the only way
    *   to fund an account: the snapshot is built from the settings, and applied to the block at height 1.
    */
  def withTestState[A](fs: FunctionalitySettings, balances: Seq[AddrWithBalance], assets: Seq[GenesisAssetSettings])(test: (BlockchainUpdaterImpl, RocksDBWriter) => A): A =
    withTestState(
      TestSettings.Default
        .copy(blockchainSettings = TestRocksDB.createTestBlockchainSettings(fs))
        .withGenesisBalances(balances*)
        .withGenesisAssets(assets*)
    )(test)

  def assertDiffEi(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      fs: FunctionalitySettings = TFS.Enabled,
      balances: Seq[AddrWithBalance] = Seq.empty,
      assets: Seq[GenesisAssetSettings] = Seq.empty
  )(
      assertion: Either[ValidationError, StateSnapshot] => Unit
  ): Unit = withTestState(fs, balances, assets) { (bcu, state) =>
    assertDiffEi(preconditions, block, bcu, state)(assertion)
  }

  def assertDiffEi(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      bcu: BlockchainUpdaterImpl,
      state: RocksDBWriter
  )(
      assertion: Either[ValidationError, StateSnapshot] => Unit
  ): Unit = {
    // blockWithComputedStateHash derives the block's state hash from the reward the next block earns, so the differ
    // has to see that same reward - otherwise it hashes a different miner balance and rejects the block. This mirrors
    // what assertDiffEiTraced and the real appender do.
    def nextReward(blockchain: Blockchain): Option[Long] = if (blockchain.height > 0) bcu.computeNextReward else None

    def differ(blockchain: Blockchain, b: Block) =
      BlockDiffer.fromBlock(
        SnapshotBlockchain(blockchain, nextReward(blockchain)),
        None,
        b,
        None,
        MiningConstraint.Unlimited,
        b.header.generationSignature
      )

    preconditions.foreach { precondition =>
      val preconditionBlock = blockWithComputedStateHash(precondition.block, precondition.signer, bcu).resultE.explicitGet()
      val reward            = nextReward(state)
      val BlockDiffer.Result(snapshot, carryFee, totalFee, _, _, computedStateHash) = differ(state, preconditionBlock).explicitGet()
      state.append(
        snapshot,
        carryFee,
        totalFee,
        reward,
        preconditionBlock.header.generationSignature,
        computedStateHash,
        preconditionBlock,
        newFinalizedHeight = GenesisBlockHeight,
        generatorSet = Seq.empty
      )
    }
    val snapshot =
      blockWithComputedStateHash(block.block, block.signer, bcu).resultE
        .flatMap(differ(state, _))
        .map(_.snapshot)
    assertion(snapshot)
  }

  def assertDiffEiTraced(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      fs: FunctionalitySettings = TFS.Enabled,
      balances: Seq[AddrWithBalance] = Seq.empty,
      assets: Seq[GenesisAssetSettings] = Seq.empty
  )(
      assertion: TracedResult[ValidationError, StateSnapshot] => Unit
  ): Unit = withTestState(fs, balances, assets) { (bcu, state) =>
    def getCompBlockchain(blockchain: Blockchain) = {
      val reward = if (blockchain.height > 0) bcu.computeNextReward else None
      SnapshotBlockchain(blockchain, reward)
    }

    def differ(blockchain: Blockchain, prevBlock: Option[Block], b: Block) =
      BlockDiffer.fromBlockTraced(
        getCompBlockchain(blockchain),
        prevBlock,
        b,
        None,
        MiningConstraint.Unlimited,
        b.header.generationSignature,
        (_, _) => (),
        verify = true,
        txSignParCheck = true
      )

    preconditions.foreach { precondition =>
      (for {
        preconditionBlock <- blockWithComputedStateHash(precondition.block, precondition.signer, bcu).resultE
        diffResult        <- differ(state, state.lastBlock, preconditionBlock).resultE
      } yield state.append(
        diffResult.snapshot,
        diffResult.carry,
        diffResult.totalFee,
        reward = None,
        preconditionBlock.header.generationSignature,
        diffResult.computedStateHash,
        preconditionBlock,
        newFinalizedHeight = GenesisBlockHeight,
        generatorSet = Seq.empty
      )).explicitGet()
    }

    val snapshot1 =
      (blockWithComputedStateHash(block.block, block.signer, bcu) match {
        case right @ TracedResult(Right(_), _, _) => right.copy(trace = Nil)
        case err                                  => err
      }).flatMap(differ(state, state.lastBlock, _))

    assertion(snapshot1.map(_.snapshot))
  }

  private def assertDiffAndState(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      fs: FunctionalitySettings,
      withNg: Boolean,
      balances: Seq[AddrWithBalance],
      assets: Seq[GenesisAssetSettings]
  )(
      assertion: (StateSnapshot, Blockchain) => Unit
  ): Unit = withTestState(fs, balances, assets) { (bcu, state) =>
    def getCompBlockchain(blockchain: Blockchain) =
      if (withNg && fs.preActivatedFeatures.get(BlockchainFeatures.BlockReward.id).exists(_ <= blockchain.height)) {
        val reward = if (blockchain.height > 0) bcu.computeNextReward else None
        SnapshotBlockchain(blockchain, reward)
      } else blockchain

    def differ(blockchain: Blockchain, prevBlock: Option[Block], b: Block): Either[ValidationError, BlockDiffer.Result] =
      BlockDiffer.fromBlock(
        getCompBlockchain(blockchain),
        if (withNg) prevBlock else None,
        b,
        None,
        MiningConstraint.Unlimited,
        b.header.generationSignature
      )

    preconditions.foldLeft[Option[Block]](None) { (prevBlock, curBlock) =>
      (for {
        preconditionBlock <- blockWithComputedStateHash(curBlock.block, curBlock.signer, bcu).resultE
        diffResult        <- differ(state, prevBlock, preconditionBlock)
      } yield {
        state.append(
          diffResult.snapshot,
          diffResult.carry,
          diffResult.totalFee,
          reward = None,
          preconditionBlock.header.generationSignature,
          diffResult.computedStateHash,
          preconditionBlock,
          newFinalizedHeight = GenesisBlockHeight,
          generatorSet = Seq.empty
        )
        Some(preconditionBlock)
      }).explicitGet()
    }

    (for {
      checkedBlock <- blockWithComputedStateHash(block.block, block.signer, bcu).resultE
      diffResult   <- differ(state, state.lastBlock, checkedBlock)
    } yield {
      val ngState = NgState(
        checkedBlock,
        diffResult.snapshot,
        diffResult.carry,
        diffResult.totalFee,
        diffResult.computedStateHash,
        fs.preActivatedFeatures.keySet,
        reward = None,
        checkedBlock.header.generationSignature,
        leasesToCancel = Map(),
        finalizationState = FinalizationState.notActivated(checkedBlock)
      )
      assertion(diffResult.snapshot, SnapshotBlockchain(state, ngState))
      state.append(
        diffResult.snapshot,
        diffResult.carry,
        diffResult.totalFee,
        reward = None,
        checkedBlock.header.generationSignature,
        diffResult.computedStateHash,
        checkedBlock,
        newFinalizedHeight = GenesisBlockHeight,
        generatorSet = Seq.empty
      )
      assertion(diffResult.snapshot, state)
    }).explicitGet()
  }

  def assertNgDiffState(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      fs: FunctionalitySettings = TFS.Enabled,
      balances: Seq[AddrWithBalance] = Seq.empty
  )(
      assertion: (StateSnapshot, Blockchain) => Unit
  ): Unit =
    assertDiffAndState(preconditions, block, fs, withNg = true, balances, Seq.empty)(assertion)

  def assertDiffAndState(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      fs: FunctionalitySettings = TFS.Enabled,
      balances: Seq[AddrWithBalance] = Seq.empty
  )(
      assertion: (StateSnapshot, Blockchain) => Unit
  ): Unit =
    assertDiffAndState(preconditions, block, fs, withNg = false, balances, Seq.empty)(assertion)

  def assertDiffAndState(fs: FunctionalitySettings)(test: (Seq[Transaction] => Either[ValidationError, Unit]) => Unit): Unit =
    withTestState(fs, Seq.empty, Seq.empty) { (bcu, state) =>
      def getCompBlockchain(blockchain: Blockchain) = {
        val reward = if (blockchain.height > 0) bcu.computeNextReward else None
        SnapshotBlockchain(blockchain, reward)
      }

      def differ(blockchain: Blockchain, b: Block) =
        BlockDiffer.fromBlock(
          blockchain,
          state.lastBlock,
          b,
          None,
          MiningConstraint.Unlimited,
          b.header.generationSignature
        )

      test { txs =>
        val nextHeight   = state.height + 1
        val block        = TestBlock.create(txs, Block.ProtoBlockVersion)
        val checkedBlock = blockWithComputedStateHash(block.block, block.signer, bcu).resultE.explicitGet()

        val blockchain = getCompBlockchain(state)
        for {
          result <- differ(blockchain, checkedBlock)
        } yield state.append(
          result.snapshot,
          result.carry,
          result.totalFee,
          reward = None,
          checkedBlock.header.generationSignature.take(Block.HitSourceLength),
          result.computedStateHash,
          checkedBlock,
          newFinalizedHeight = GenesisBlockHeight,
          generatorSet = Seq.empty
        )
      }
    }

  def assertBalanceInvariant(snapshot: StateSnapshot, db: RocksDBWriter, rewardAndFee: Long = 0): Unit = {
    snapshot.balances.toSeq
      .map {
        case ((`defaultAddress`, Waves), balance) => Waves -> (balance - db.balance(defaultAddress, Waves) - rewardAndFee)
        case ((address, asset), balance)          => asset -> (balance - db.balance(address, asset))
      }
      .groupMap(_._1)(_._2)
      .foreach { case (_, balances) => balances.sum shouldBe 0 }
    snapshot.leaseBalances.foreach { case (address, balance) => balance shouldBe db.leaseBalance(address) }
  }

  def assertLeft(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      fs: FunctionalitySettings = TFS.Enabled,
      balances: Seq[AddrWithBalance] = Seq.empty
  )(errorMessage: String): Unit =
    assertDiffEi(preconditions, block, fs, balances)(_ should produce(errorMessage))

  def blockWithComputedStateHash(
      blockWithoutStateHash: Block,
      signer: SigningKey,
      blockchain: BlockchainUpdater & Blockchain
  ): TracedResult[ValidationError, Block] =
    WithState.blockWithComputedStateHash(blockWithoutStateHash, signer, blockchain)
}

trait WithDomain extends WithState {
  suite: Suite =>
  val DomainPresets = com.wavesplatform.test.DomainPresets

  def domainSettingsWithFS(fs: FunctionalitySettings): WavesSettings =
    DomainPresets.domainSettingsWithFS(fs)

  def withDomain[A](
      settings: WavesSettings = DomainPresets.SettingsFromDefaultConfig,
      balances: Seq[AddrWithBalance] = Seq.empty,
      wrapDB: RocksDB => RocksDB = identity,
      wrapBU: CompleteBlockchainUpdater => CompleteBlockchainUpdater = identity,
      miner: Miner = Miner.StrictDisabledMiner,
      time: TestTime = TestTime(),
      generators: Seq[SigningKey] = Nil
  )(test: Domain => A): A = {
    // The genesis block has no transactions: its balances are part of the genesis snapshot, which BlockDiffer
    // builds from the settings. So they have to be in the settings the state itself is built from.
    val settingsWithGenesis = settings.withGenesisBalances(balances*).withGenesisGenerators(generators*)

    withRocksDBWriter(settingsWithGenesis) { blockchain =>
      var domain: Domain = null
      val bcu = wrapBU(
        new BlockchainUpdaterImpl(
          blockchain,
          settingsWithGenesis,
          time,
          BlockchainUpdateTriggers.combined(domain.triggers),
          loadActiveLeases(rdb, _, _),
          miner
        )
      )

      try {
        val wrappedDb = wrapDB(rdb.db)
        assert(wrappedDb.getNativeHandle == rdb.db.getNativeHandle, "wrap function should not create new database instance")
        domain = Domain(
          new RDB(wrappedDb, rdb.txMetaHandle, rdb.txHandle, rdb.txSnapshotHandle, rdb.apiHandle, Seq.empty),
          bcu,
          blockchain,
          settingsWithGenesis,
          time
        )
        domain.appendBlock(WithState.createGenesisBlock(settingsWithGenesis))
        test(domain)
      } finally {
        // domain stays null if the setup above threw; closing it unconditionally would mask the real failure with an NPE
        if (domain != null) domain.utxPool.close()
        bcu.shutdown()
      }
    }
  }
}

object WithState {
  case class AddrWithBalance(address: Address, balance: Long = ENOUGH_AMT, assets: Map[IssuedAsset, Long] = Map.empty)

  object AddrWithBalance {
    def enoughBalances(accs: SigningKey*): Seq[AddrWithBalance] =
      accs.map(acc => AddrWithBalance(acc.toAddress))

    given Conversion[(Address, Long), AddrWithBalance] = v => AddrWithBalance(v._1, v._2)
    given Conversion[(SigningKey, Long), AddrWithBalance] = v => AddrWithBalance(v._1.toAddress, v._2)
    given Conversion[(SigningKey, IssuedAsset, Long), AddrWithBalance] = v => AddrWithBalance(v._1.toAddress, 0, Map(v._2 -> v._3))

  }

  /** The generator the test harness mines with. Blocks forged by `defaultSigner` carry a VRF proof made with
    * `defaultVrfKey`, and [[Blockchain.vrfPublicKeyOf]] only resolves that key from a commitment, so every test genesis
    * has to commit the pair for its blocks to verify.
    */
  def genesisGeneratorFor(signer: SigningKey): GenesisGeneratorSettings =
    GenesisGeneratorSettings(
      ByteStr(signer.publicKey()).toString,
      // Derived the same way TxHelpers.commitToGeneration derives them, so that a test re-committing this signer
      // registers the very keys the genesis already committed for it
      TxHelpers.blsKeyOf(signer).publicKey.base16,
      ByteStr(TxHelpers.vrfKeyOf(signer).publicKey()).toString
    )

  def blockWithComputedStateHash(
      blockWithoutStateHash: Block,
      signer: SigningKey,
      blockchain: BlockchainUpdater & Blockchain
  ): TracedResult[ValidationError, Block] = {
    val compBlockchain =
      SnapshotBlockchain(blockchain, StateSnapshot.empty, blockWithoutStateHash, ByteStr.empty, 0, blockchain.computeNextReward, None)
    val prevStateHash = blockchain.lastStateHash(Some(blockWithoutStateHash.header.reference))
    // The block at height 1 earns no reward: its initial snapshot is the predefined genesis snapshot, exactly as
    // BlockDiffer builds it. Using the reward snapshot here instead would compute a state hash the differ rejects.
    TracedResult(
      if (Height(blockchain.height + 1) == GenesisBlockHeight)
        GenesisSnapshot.build(blockchain.settings.genesisSettings, blockchain.settings.functionalitySettings)
      else
        BlockDiffer
          .createInitialBlockSnapshot(
            blockchain,
            blockWithoutStateHash.header.reference,
            blockWithoutStateHash.header.generator.toAddress
          )
    )
      .flatMap { initSnapshot =>
        val initStateHash = BlockDiffer.computeInitialStateHash(initSnapshot, prevStateHash)

        TxStateSnapshotHashBuilder
          .computeStateHash(
            blockWithoutStateHash.transactionData,
            initStateHash,
            initSnapshot,
            signer,
            blockchain.lastBlockTimestamp,
            blockWithoutStateHash.header.timestamp,
            blockWithoutStateHash.header.challengedHeader.isDefined,
            compBlockchain
          )
          .map(Some(_))
      }
  }.flatMap { stateHash =>
    TracedResult(
      Block.buildAndSign(
        version = blockWithoutStateHash.header.version,
        timestamp = blockWithoutStateHash.header.timestamp,
        reference = blockWithoutStateHash.header.reference,
        baseTarget = blockWithoutStateHash.header.baseTarget,
        generationSignature = blockWithoutStateHash.header.generationSignature,
        txs = blockWithoutStateHash.transactionData,
        featureVotes = blockWithoutStateHash.header.featureVotes,
        rewardVote = blockWithoutStateHash.header.rewardVote,
        signer = signer,
        stateHash = stateHash,
        challengedHeader = None,
        finalizationVoting = None
      )
    )
  }

  def createGenesisBlock(settings: WavesSettings): Block =
    Block
      .genesis(
        settings.blockchainSettings.genesisSettings,
        settings.blockchainSettings.functionalitySettings
      )
      .explicitGet()
}
