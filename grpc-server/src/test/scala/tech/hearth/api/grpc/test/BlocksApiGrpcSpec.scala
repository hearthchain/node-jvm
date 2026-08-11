package tech.hearth.api.grpc.test

import tech.hearth.history.withFlatReward
import com.google.protobuf.ByteString
import tech.hearth.account.Address
import tech.hearth.api.grpc.{BlockRangeRequest, BlockRequest, BlockWithHeight, BlocksApiGrpcImpl}
import tech.hearth.block.Block
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.DigestLength
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.{Domain, defaultSigner}
import tech.hearth.protobuf.*
import tech.hearth.protobuf.block.PBBlocks
import tech.hearth.protobuf.transaction.PBTransactions
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.{BlockRewardCalculator, Blockchain}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.assets.exchange.{ExchangeTransaction, Order, OrderType}
import tech.hearth.transaction.{CommitToGenerationTransaction, TxHelpers}
import tech.hearth.utils.{DiffMatchers, Schedulers, byteStrOrdering}
import monix.execution.ExecutionModel.SynchronousExecution
import monix.execution.Scheduler
import org.scalatest.{Assertion, BeforeAndAfterAll}
import tech.hearth.crypto.SigningKey

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class BlocksApiGrpcSpec extends FreeSpec with BeforeAndAfterAll with DiffMatchers with WithDomain with GrpcApiHelpers {
  private given scheduler: Scheduler = Schedulers.singleThread("grpc", executionModel = SynchronousExecution)
  val sender: SigningKey             = TxHelpers.signer(1)
  val recipient: SigningKey          = TxHelpers.signer(2)
  val timeout: FiniteDuration        = 1.minute

  "GetBlock should work" in withDomain(DomainPresets.RideV6, AddrWithBalance.enoughBalances(sender)) { d =>
    val grpcApi = getGrpcApi(d)

    val block = d.appendBlock(TxHelpers.transfer(sender, recipient.toAddress, 1))

    d.liquidAndSolidAssert { () =>
      val vrf = getBlockVrfPB(d, block)
      vrf.isEmpty shouldBe false
      val expectedResult = BlockWithHeight.of(
        Some(PBBlocks.protobuf(block)),
        2,
        vrf,
        getExpectedRewardShares(2, block.sender.toAddress, d.blockchain)
      )

      val resultById = Await.result(
        grpcApi.getBlock(BlockRequest.of(BlockRequest.Request.BlockId(block.id().toByteString), includeTransactions = true)),
        timeout
      )

      resultById shouldBe expectedResult

      val resultByHeight = Await.result(
        grpcApi.getBlock(BlockRequest.of(BlockRequest.Request.Height(2), includeTransactions = true)),
        timeout
      )

      resultByHeight shouldBe expectedResult
    }
  }

  "GetBlockRange should work" in withDomain(DomainPresets.RideV6, AddrWithBalance.enoughBalances(sender)) { d =>
    val grpcApi = getGrpcApi(d)

    val blocks = (1 to 10).map { _ =>
      d.appendBlock(TxHelpers.transfer(sender, recipient.toAddress, 1))
    }.toList

    d.liquidAndSolidAssert { () =>
      val (observer, result) = createObserver[BlockWithHeight]
      grpcApi.getBlockRange(
        BlockRangeRequest.of(2, 11, BlockRangeRequest.Filter.Empty, includeTransactions = true),
        observer
      )
      result.runSyncUnsafe() shouldBe blocks.zipWithIndex.map { case (block, idx) =>
        val vrf = getBlockVrfPB(d, block)
        vrf.isEmpty shouldBe false
        BlockWithHeight.of(
          Some(PBBlocks.protobuf(block)),
          idx + 2,
          vrf,
          getExpectedRewardShares(idx + 2, block.sender.toAddress, d.blockchain)
        )
      }
    }
  }

  "NODE-972. GetBlock and GetBlockRange should return correct data for orders with attachment" in {
    def checkOrderAttachment(block: BlockWithHeight, expectedAttachment: ByteStr): Assertion = {
      PBTransactions
        .vanilla(block.block.get.transactions.head)
        .explicitGet()
        .asInstanceOf[ExchangeTransaction]
        .order1
        .attachment shouldBe Some(expectedAttachment)
    }

    val sender = TxHelpers.signer(1)
    val issuer = TxHelpers.signer(2)
    val asset  = IssuedAsset(ByteStr.fill(32)(1))
    withDomain(
      DomainPresets.TransactionStateSnapshot,
      balances = AddrWithBalance.enoughBalances(sender) :+ AddrWithBalance(issuer.toAddress, assets = Map(asset -> 1L)),
      assets = Seq(GenesisAssetSettings(asset.id, "asset", 0, 1L, 100000L))
    ) { d =>
      val grpcApi = getGrpcApi(d)

      val attachment = ByteStr.fill(32)(1)
      val exchange =
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(OrderType.BUY, Hearth, asset, version = Order.V4, attachment = Some(attachment)),
          TxHelpers.order(OrderType.SELL, Hearth, asset, version = Order.V4, sender = issuer)
        )

      val exchangeBlock = d.appendBlock(exchange)

      d.liquidAndSolidAssert { () =>
        val resultById = Await.result(
          grpcApi.getBlock(BlockRequest.of(BlockRequest.Request.BlockId(exchangeBlock.id().toByteString), includeTransactions = true)),
          1.minute
        )

        checkOrderAttachment(resultById, attachment)

        val resultByHeight = Await.result(
          grpcApi.getBlock(BlockRequest.of(BlockRequest.Request.Height(2), includeTransactions = true)),
          1.minute
        )

        checkOrderAttachment(resultByHeight, attachment)

        val (observer, resultRange) = createObserver[BlockWithHeight]
        grpcApi.getBlockRange(
          BlockRangeRequest.of(2, 2, BlockRangeRequest.Filter.Empty, includeTransactions = true),
          observer
        )

        checkOrderAttachment(resultRange.runSyncUnsafe().head, attachment)
      }
    }
  }

  "NODE-844. GetBlock should return correct rewardShares" in {
    blockRewardSharesTestCase { case (daoAddress, d, grpcApi) =>
      val block = d.appendBlock()

      val minerReward = d.blockchain.settings.rewardsSettings.initialReward - BlockRewardCalculator.MaxAddressReward

      checkBlockRewards(
        block.id(),
        2,
        Seq(
          RewardShare(ByteString.copyFrom(block.sender.toAddress.toBytes()), minerReward),
          RewardShare(ByteString.copyFrom(daoAddress.toBytes()), BlockRewardCalculator.MaxAddressReward)
        ).sortBy(_.address.toByteStr)
      )(grpcApi)
    }
  }

  "NODE-845. GetBlockRange should return correct rewardShares" in {
    blockRewardSharesTestCase { case (daoAddress, d, grpcApi) =>
      val block = d.appendBlock()

      val (observer, result) = createObserver[BlockWithHeight]
      grpcApi.getBlockRange(
        BlockRangeRequest.of(2, 2, BlockRangeRequest.Filter.Empty, includeTransactions = true),
        observer
      )
      val blocks = result.runSyncUnsafe()

      val minerReward = d.blockchain.settings.rewardsSettings.initialReward - BlockRewardCalculator.MaxAddressReward

      blocks.head.rewardShares shouldBe Seq(
        RewardShare(ByteString.copyFrom(block.sender.toAddress.toBytes()), minerReward),
        RewardShare(ByteString.copyFrom(daoAddress.toBytes()), BlockRewardCalculator.MaxAddressReward)
      ).sortBy(_.address.toByteStr)
    }
  }

  "NODE-922. GetBlock should return correct data for challenging block" in {
    val sender = TxHelpers.signer(1)
    // a challenged miner's balance is banned once challenged (see CLAUDE.md), so it must not be defaultSigner:
    // makeStateSolid()/liquidAndSolidAssert would otherwise fail appending its own empty block with defaultSigner
    val challengedMiner  = TxHelpers.signer(5)
    val challengingMiner = TxHelpers.signer(4)
    val deposit          = CommitToGenerationTransaction.DepositInEmbers
    withDomain(
      TransactionStateSnapshot,
      generators = Seq(defaultSigner, challengedMiner, challengingMiner),
      balances = AddrWithBalance.enoughBalances(sender, defaultSigner) :+ AddrWithBalance(challengingMiner.toAddress, deposit)
    ) { d =>
      val grpcApi = getGrpcApi(d)

      // net of the deposit, still has to clear GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2 (1000 hearth)
      d.appendBlock(
        TxHelpers.transfer(sender, challengingMiner.toAddress, 1200.hearth - deposit)
      )

      (1 to 999).foreach(_ => d.appendBlock())

      val invalidStateHash = ByteStr.fill(DigestLength)(1)
      val originalBlock = d.createBlock(
        Seq(TxHelpers.transfer(sender)),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner, originalBlock)
      val blockHeight      = 1002

      d.appendBlockE(challengingBlock) should beRight

      d.liquidAndSolidAssert { () =>
        val vrf = getBlockVrfPB(d, challengingBlock)
        vrf.isEmpty shouldBe false
        val expectedResult = BlockWithHeight.of(
          Some(PBBlocks.protobuf(challengingBlock)),
          blockHeight,
          vrf,
          getExpectedRewardShares(blockHeight, challengingMiner.toAddress, d.blockchain)
        )

        val resultById = Await.result(
          grpcApi.getBlock(BlockRequest.of(BlockRequest.Request.BlockId(challengingBlock.id().toByteString), includeTransactions = true)),
          timeout
        )

        resultById shouldBe expectedResult

        val resultByHeight = Await.result(
          grpcApi.getBlock(BlockRequest.of(BlockRequest.Request.Height(blockHeight), includeTransactions = true)),
          timeout
        )

        resultByHeight shouldBe expectedResult
      }
    }
  }

  "NODE-922. GetBlockRange should return correct data for challenging block" in {
    val sender = TxHelpers.signer(1)
    // a challenged miner's balance is banned once challenged (see CLAUDE.md), so it must not be defaultSigner:
    // makeStateSolid()/liquidAndSolidAssert would otherwise fail appending its own empty block with defaultSigner
    val challengedMiner  = TxHelpers.signer(5)
    val challengingMiner = TxHelpers.signer(4)
    val deposit          = CommitToGenerationTransaction.DepositInEmbers
    withDomain(
      TransactionStateSnapshot,
      generators = Seq(defaultSigner, challengedMiner, challengingMiner),
      balances = AddrWithBalance.enoughBalances(sender, defaultSigner) :+ AddrWithBalance(challengingMiner.toAddress, deposit)
    ) { d =>
      val grpcApi = getGrpcApi(d)

      // net of the deposit, still has to clear GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2 (1000 hearth)
      d.appendBlock(
        TxHelpers.transfer(sender, challengingMiner.toAddress, 1200.hearth - deposit)
      )

      (1 to 999).foreach(_ => d.appendBlock())

      val invalidStateHash = ByteStr.fill(DigestLength)(1)
      val originalBlock = d.createBlock(
        Seq(TxHelpers.transfer(sender)),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner, originalBlock)
      val blockHeight      = 1002

      d.appendBlockE(challengingBlock) should beRight

      d.liquidAndSolidAssert { () =>
        val (observer, result) = createObserver[BlockWithHeight]
        grpcApi.getBlockRange(
          BlockRangeRequest.of(blockHeight, blockHeight, BlockRangeRequest.Filter.Empty, includeTransactions = true),
          observer
        )

        val vrf = getBlockVrfPB(d, challengingBlock)
        vrf.isEmpty shouldBe false

        result.runSyncUnsafe() shouldBe Seq(
          BlockWithHeight.of(
            Some(PBBlocks.protobuf(challengingBlock)),
            blockHeight,
            vrf,
            getExpectedRewardShares(blockHeight, challengingMiner.toAddress, d.blockchain)
          )
        )
      }
    }
  }

  private def getBlockVrfPB(d: Domain, block: Block): ByteString =
    d.blocksApi.block(block.id()).flatMap(_._1.vrf).map(_.toByteString).getOrElse(ByteString.EMPTY)

  private def getGrpcApi(d: Domain) =
    new BlocksApiGrpcImpl(d.blocksApi)

  private def checkBlockRewards(blockId: ByteStr, height: Int, expected: Seq[RewardShare])(api: BlocksApiGrpcImpl): Assertion = {
    Await
      .result(
        api.getBlock(BlockRequest.of(BlockRequest.Request.BlockId(blockId.toByteString), includeTransactions = false)),
        timeout
      )
      .rewardShares shouldBe expected

    Await
      .result(
        api.getBlock(BlockRequest.of(BlockRequest.Request.Height(height), includeTransactions = false)),
        timeout
      )
      .rewardShares shouldBe expected
  }

  private def blockRewardSharesTestCase(checks: (Address, Domain, BlocksApiGrpcImpl) => Unit): Unit = {
    val daoAddress = TxHelpers.address(3)

    val settings = DomainPresets.ConsensusImprovements
    val settingsWithFeatures = settings
      .copy(blockchainSettings =
        settings.blockchainSettings.copy(
          functionalitySettings = settings.blockchainSettings.functionalitySettings.copy(daoAddress = Some(daoAddress.toString)),
          rewardsSettings = withFlatReward(settings.blockchainSettings.rewardsSettings, BlockRewardCalculator.FullRewardInit + 1.hearth)
        )
      )

    withDomain(settingsWithFeatures) { d =>
      val grpcApi = getGrpcApi(d)

      checks(daoAddress, d, grpcApi)
    }
  }

  private def getExpectedRewardShares(height: Int, miner: Address, blockchain: Blockchain): Seq[RewardShare] = {
    val expectedRewardShares = BlockRewardCalculator.getSortedBlockRewardShares(height, miner, blockchain)
    expectedRewardShares.map { case (addr, reward) =>
      RewardShare(ByteString.copyFrom(addr.toBytes()), reward)
    }
  }
}
