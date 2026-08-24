package tech.hearth.db

import com.google.common.primitives.Shorts
import tech.hearth.account.Address
import tech.hearth.block.{Block, SignedBlockHeader}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.database.{KeyTag, RDB, RocksDBWriter, TestStorageFactory}
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.history.Domain
import tech.hearth.lagonaki.mocks.TestBlock
import tech.hearth.lagonaki.mocks.TestBlock.BlockWithSigner
import tech.hearth.lang.ValidationError
import tech.hearth.mining.{Miner, MiningConstraint}
import tech.hearth.settings.{TestFunctionalitySettings as TFS, *}
import tech.hearth.state.diffs.{BlockDiffer, ENOUGH_AMT}
import tech.hearth.state.utils.TestRocksDB
import tech.hearth.state.*
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.TxHelpers.defaultAddress
import tech.hearth.transaction.smart.script.trace.TracedResult
import tech.hearth.transaction.{BlockchainUpdater, Transaction, TxHelpers}
import tech.hearth.utils.{ApplicationStopReason, forceStopApplication}
import tech.hearth.{NTPTime, TestHelpers}
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

  protected def withRocksDBWriter[A](ws: HearthSettings)(test: RocksDBWriter => A): A = {
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

  protected def withTestState[A](ws: HearthSettings)(test: (BlockchainUpdaterImpl, RocksDBWriter) => A): A = {
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
  def withTestState[A](fs: FunctionalitySettings, balances: Seq[AddrWithBalance], assets: Seq[GenesisAssetSettings])(
      test: (BlockchainUpdaterImpl, RocksDBWriter) => A
  ): A =
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
      val preconditionBlock = blockOnTopOf(precondition.block, precondition.signer, bcu).resultE.explicitGet()
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
      blockOnTopOf(block.block, block.signer, bcu).resultE
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

    def differ(blockchain: Blockchain, prevBlock: Option[SignedBlockHeader], b: Block) =
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
        preconditionBlock <- blockOnTopOf(precondition.block, precondition.signer, bcu).resultE
        diffResult        <- differ(state, state.lastBlockHeader, preconditionBlock).resultE
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
      (blockOnTopOf(block.block, block.signer, bcu) match {
        case right @ TracedResult(Right(_), _, _) => right.copy(trace = Nil)
        case err                                  => err
      }).flatMap(differ(state, state.lastBlockHeader, _))

    assertion(snapshot1.map(_.snapshot))
  }

  def assertDiffAndState(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      fs: FunctionalitySettings,
      balances: Seq[AddrWithBalance],
      assets: Seq[GenesisAssetSettings]
  )(
      assertion: (StateSnapshot, Blockchain) => Unit
  ): Unit = withTestState(fs, balances, assets) { (bcu, state) =>
    def getCompBlockchain(blockchain: Blockchain) = {
      val reward = if (blockchain.height > 0) bcu.computeNextReward else None
      SnapshotBlockchain(blockchain, reward)
    }

    def differ(blockchain: Blockchain, prevBlock: Option[SignedBlockHeader], b: Block): Either[ValidationError, BlockDiffer.Result] =
      BlockDiffer.fromBlock(
        getCompBlockchain(blockchain),
        prevBlock,
        b,
        None,
        MiningConstraint.Unlimited,
        b.header.generationSignature
      )

    preconditions.foldLeft[Option[SignedBlockHeader]](None) { (prevBlock, curBlock) =>
      (for {
        preconditionBlock <- blockOnTopOf(curBlock.block, curBlock.signer, bcu).resultE
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
        Some(preconditionBlock.signedHeader)
      }).explicitGet()
    }

    (for {
      checkedBlock <- blockOnTopOf(block.block, block.signer, bcu).resultE
      diffResult   <- differ(state, state.lastBlockHeader, checkedBlock)
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
    assertDiffAndState(preconditions, block, fs, balances, Seq.empty)(assertion)

  def assertDiffAndState(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      fs: FunctionalitySettings = TFS.Enabled,
      balances: Seq[AddrWithBalance] = Seq.empty
  )(
      assertion: (StateSnapshot, Blockchain) => Unit
  ): Unit =
    assertDiffAndState(preconditions, block, fs, balances, Seq.empty)(assertion)

  def assertDiffAndState(fs: FunctionalitySettings)(test: (Seq[Transaction] => Either[ValidationError, Unit]) => Unit): Unit =
    withTestState(fs, Seq.empty, Seq.empty) { (bcu, state) =>
      def getCompBlockchain(blockchain: Blockchain) = {
        val reward = if (blockchain.height > 0) bcu.computeNextReward else None
        SnapshotBlockchain(blockchain, reward)
      }

      def differ(blockchain: Blockchain, b: Block) =
        BlockDiffer.fromBlock(
          blockchain,
          state.lastBlockHeader,
          b,
          None,
          MiningConstraint.Unlimited,
          b.header.generationSignature
        )

      test { txs =>
        val block        = TestBlock.create(txs)
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
        case ((`defaultAddress`, Hearth), balance) => Hearth -> (balance - db.balance(defaultAddress, Hearth) - rewardAndFee)
        case ((address, asset), balance)           => asset  -> (balance - db.balance(address, asset))
      }
      .groupMap(_._1)(_._2)
      .foreach { case (_, balances) => balances.sum shouldBe 0 }
    snapshot.leaseBalances.foreach { case (address, balance) => balance shouldBe db.leaseBalance(address) }
  }

  def assertLeft(
      preconditions: Seq[BlockWithSigner],
      block: BlockWithSigner,
      fs: FunctionalitySettings = TFS.Enabled,
      balances: Seq[AddrWithBalance] = Seq.empty,
      assets: Seq[GenesisAssetSettings] = Seq.empty
  )(errorMessage: String): Unit =
    assertDiffEi(preconditions, block, fs, balances, assets)(_ should produce(errorMessage))

  def blockWithComputedStateHash(
      blockWithoutStateHash: Block,
      signer: SigningKey,
      blockchain: BlockchainUpdater & Blockchain
  ): TracedResult[ValidationError, Block] =
    WithState.blockWithComputedStateHash(blockWithoutStateHash, signer, blockchain)

  def blockOnTopOf(
      blockWithoutStateHash: Block,
      signer: SigningKey,
      blockchain: BlockchainUpdater & Blockchain
  ): TracedResult[ValidationError, Block] =
    WithState.blockOnTopOf(blockWithoutStateHash, signer, blockchain)
}

trait WithDomain extends WithState {
  suite: Suite =>
  val DomainPresets = tech.hearth.test.DomainPresets

  def domainSettingsWithFS(fs: FunctionalitySettings): HearthSettings =
    DomainPresets.domainSettingsWithFS(fs)

  /** No test fixture in this repo can drive a StartBoostTransaction to its accept path (see
    * StartBoostTransactionDiffTest's own doc comment), so a RegisteredEnclave entry - needed by every Diff test that
    * checks a "registered miner"/"registered enclave" precondition (Reserve/BindApiKey/Settle) - has to be injected
    * directly rather than produced by a real transaction. Shared here instead of each test hand-rolling its own
    * `export blockchain.{registeredEnclaves as _, *}` wrapper.
    */
  def blockchainWithRegisteredEnclave(blockchain: Blockchain, enclave: RegisteredEnclave): Blockchain =
    new Blockchain {
      export blockchain.{registeredEnclaves as _, *}
      override def registeredEnclaves(at: GenerationPeriod): IndexedSeq[RegisteredEnclave] =
        blockchain.registeredEnclaves(at) :+ enclave
    }

  def withDomain[A](
      settings: HearthSettings = DomainPresets.SettingsFromDefaultConfig,
      balances: Seq[AddrWithBalance] = Seq.empty,
      wrapDB: RocksDB => RocksDB = identity,
      wrapBU: CompleteBlockchainUpdater => CompleteBlockchainUpdater = identity,
      miner: Miner = Miner.StrictDisabledMiner,
      time: TestTime = TestTime(),
      generators: Seq[SigningKey] = Nil,
      assets: Seq[GenesisAssetSettings] = Seq.empty,
      // How the updater brings the node down on an unimplemented activated feature; pass a probe to observe it
      onFatalStop: ApplicationStopReason => Unit = forceStopApplication
  )(test: Domain => A): A = {
    val noExplicitGenerators = generators.isEmpty &&
      settings.blockchainSettings.predefinedSnapshots.find(_.height == GenesisBlockHeight.toInt).forall(_.generators.isEmpty)
    val effectiveGenerators = if (noExplicitGenerators) Seq(TxHelpers.defaultSigner) else generators
    // When defaultSigner is auto-committed as the generator (no explicit generators), it also has to be funded to cover
    // its generation deposit. Fund it unless the caller already did, so an explicit defaultSigner balance still wins.
    val withDefaultSignerFunded =
      if (noExplicitGenerators && !balances.exists(_.address == TxHelpers.defaultSigner.toAddress))
        AddrWithBalance(TxHelpers.defaultSigner.toAddress) +: balances
      else balances
    // Every committed generator needs a genesis balance covering its deposit, for the same reason: the genesis snapshot
    // is rejected with "balance 0 is less than required for generation" otherwise. These entries go last, and
    // withGenesisBalances dedupes by address keeping the first, so whatever the caller declared still wins.
    val effectiveBalances = withDefaultSignerFunded ++ effectiveGenerators.map(g => AddrWithBalance(g.toAddress))
    val settingsWithGenesis =
      settings.withGenesisBalances(effectiveBalances*).withGenesisGenerators(effectiveGenerators*).withGenesisAssets(assets*)

    withRocksDBWriter(settingsWithGenesis) { blockchain =>
      var domain: Domain = null
      val bcu = wrapBU(
        new BlockchainUpdaterImpl(
          blockchain,
          settingsWithGenesis,
          time,
          BlockchainUpdateTriggers.combined(domain.triggers),
          miner,
          onFatalStop
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

    given Conversion[(Address, Long), AddrWithBalance]                 = v => AddrWithBalance(v._1, v._2)
    given Conversion[(SigningKey, Long), AddrWithBalance]              = v => AddrWithBalance(v._1.toAddress, v._2)
    given Conversion[(SigningKey, IssuedAsset, Long), AddrWithBalance] = v => AddrWithBalance(v._1.toAddress, 0, Map(v._2 -> v._3))
    given Conversion[(Address, IssuedAsset, Long), AddrWithBalance]    = v => AddrWithBalance(v._1, 0, Map(v._2 -> v._3))
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

  /** [[blockWithComputedStateHash]], but with the block retargeted onto the chain it is about to be applied to.
    *
    * `TestBlock.create` overloads that take no explicit `ref` fill in `randomSignature()`, and `BlockDiffer` requires a
    * block to reference the blockchain it is applied to (`Block references X, but the blockchain it is applied to is at
    * Y`). The assert* helpers below chain blocks onto a growing state, so only they know the right reference - a caller
    * building the block cannot. Retarget it here rather than making every test thread the id through.
    *
    * Note that this *overrides* whatever reference the block carried: these helpers always apply blocks in sequence on
    * top of the state they are building, so there is no forking to preserve. A test that needs a specific reference -
    * to fork, or to check that a bad one is rejected - has to go through `withDomain`/`Domain.appendBlock`, which is
    * where `BlockchainUpdaterImpl` validates references in the first place (`BlockDiffer` never did).
    */
  def blockOnTopOf(
      blockWithoutStateHash: Block,
      signer: SigningKey,
      blockchain: BlockchainUpdater & Blockchain
  ): TracedResult[ValidationError, Block] = {
    val retargeted = blockchain.lastBlockId match {
      case Some(head) if head != blockWithoutStateHash.header.reference =>
        blockWithoutStateHash.copy(header = blockWithoutStateHash.header.copy(reference = head))
      case _ => blockWithoutStateHash
    }
    blockWithComputedStateHash(retargeted, signer, blockchain)
  }

  def blockWithComputedStateHash(
      blockWithoutStateHash: Block,
      signer: SigningKey,
      blockchain: BlockchainUpdater & Blockchain
  ): TracedResult[ValidationError, Block] = {
    val compBlockchain =
      SnapshotBlockchain(blockchain, StateSnapshot.empty, blockWithoutStateHash, ByteStr.empty, BlockFee.empty, blockchain.computeNextReward, None)
    val prevStateHash = blockchain.lastStateHash(Some(blockWithoutStateHash.header.reference))
    // The block at height 1 earns no reward: its initial snapshot is the predefined genesis snapshot, exactly as
    // BlockDiffer builds it. Using the reward snapshot here instead would compute a state hash the differ rejects.
    TracedResult(
      if (Height(blockchain.height + 1) == GenesisBlockHeight)
        // blockchain is still empty here (height 0), so it has no lastBlockTimestamp to fall back on the way a
        // later predefined-snapshot height would - the block under construction carries the genesis timestamp
        // itself.
        PredefinedSnapshot.build(blockchain.settings.genesisSnapshot, blockchain, blockTimestamp = Some(blockWithoutStateHash.header.timestamp))
      else
        BlockDiffer
          .createInitialBlockSnapshot(
            blockchain,
            blockWithoutStateHash.header.reference,
            blockWithoutStateHash.header.generator.toAddress,
            blockchain.lastBlockHeader
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
        timestamp = blockWithoutStateHash.header.timestamp,
        reference = blockWithoutStateHash.header.reference,
        baseTarget = blockWithoutStateHash.header.baseTarget,
        generationSignature = blockWithoutStateHash.header.generationSignature,
        txs = blockWithoutStateHash.transactionData,
        featureVotes = blockWithoutStateHash.header.featureVotes,
        signer = signer,
        stateHash = stateHash,
        challengedHeader = None,
        finalizationVoting = None
      )
    )
  }

  def createGenesisBlock(settings: HearthSettings): Block =
    Block.genesis(settings.blockchainSettings).explicitGet()
}
