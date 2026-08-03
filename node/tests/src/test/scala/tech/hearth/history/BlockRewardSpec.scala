package tech.hearth.history

import cats.syntax.option.*
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.{Constants, FunctionalitySettings, RewardsSettings, WavesSettings}
import tech.hearth.state.BlockRewardCalculator
import tech.hearth.test.*
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.TxHelpers

/** The block reward is a constant: `BlockRewardCalculator.fullRewardAt` returns `rewardsSettings.initial` at every
  * height, and a block header carries no reward vote, so nothing can change it. What is left to test is how a block's
  * reward and the fees it collects are shared out - between the miner and the DAO address, and between the block that
  * collects a fee and the one that references it.
  *
  * Gone with the vote: the properties that drove a chain through a voting window and watched the reward move, and the
  * ones that asserted what happened before BlockRewardDistribution or CappedReward activated - `rewardSharesAt` pins
  * both activation heights at 1, so those states cannot be reached.
  */
class BlockRewardSpec extends FreeSpec with WithDomain {

  private val InitialReward       = 6 * Constants.UnitsInWave
  private val InitialMinerBalance = 10000 * Constants.UnitsInWave
  private val OneTotalFee         = 100000
  private val OneCarryFee         = (OneTotalFee * 0.6).toLong
  private val OneFee              = (OneTotalFee * 0.4).toLong

  private val rewardSettings: WavesSettings = MicroblocksActivatedAt0WavesSettings.copy(
    blockchainSettings = DefaultBlockchainSettings.copy(
      functionalitySettings = FunctionalitySettings(featureCheckBlocksPeriod = 10, blocksForFeatureActivation = 1),
      rewardsSettings = RewardsSettings(10, 5, InitialReward, 1 * Constants.UnitsInWave, 4)
    )
  )

  // Credited by the genesis snapshot, so they have to be known before the domain exists
  private val sourceAddress = TxHelpers.signer(101)
  private val issuer        = TxHelpers.signer(102)
  private val miner1        = TxHelpers.signer(103)
  private val miner2        = TxHelpers.signer(104)

  private val genesisBalances: Seq[AddrWithBalance] = Seq(
    AddrWithBalance(sourceAddress.toAddress, (Constants.TotalWaves - 60000) * Constants.UnitsInWave),
    AddrWithBalance(issuer.toAddress, 40000 * Constants.UnitsInWave),
    AddrWithBalance(miner1.toAddress, InitialMinerBalance),
    AddrWithBalance(miner2.toAddress, InitialMinerBalance)
  )

  private def feePayingTransfer =
    TxHelpers.transfer(issuer, sourceAddress.toAddress, 10 * Constants.UnitsInWave, Waves, OneTotalFee, Waves, ByteStr.empty)

  "Miner receives reward and fees" - {
    "40% of a fee in the block that collects it, the carry in the block that references it" in
      withDomain(rewardSettings, genesisBalances, generators = Seq(miner1, miner2)) { d =>
        d.appendBlock(d.createBlock(Seq(feePayingTransfer), generator = miner1))

        d.balance(miner1.toAddress) shouldBe InitialMinerBalance + InitialReward + OneFee
        d.blockchainUpdater.liquidBlockMeta.map(_.totalFeeInWaves) shouldBe OneTotalFee.some
        d.carryFee(d.lastBlockId).map(_.wavesAmount) shouldBe Right(OneCarryFee)

        // The next block collects no fee of its own, so all it gets on top of its reward is the carry
        d.appendBlock(d.createBlock(Nil, generator = miner2))

        d.balance(miner2.toAddress) shouldBe InitialMinerBalance + InitialReward + OneCarryFee
        d.blockchainUpdater.liquidBlockMeta.map(_.totalFeeInWaves) shouldBe 0L.some
        d.carryFee(d.lastBlockId).map(_.wavesAmount) shouldBe Right(0L)
      }

    "a fee collected by a micro block is carried like any other" in
      withDomain(rewardSettings, genesisBalances, generators = Seq(miner1, miner2)) { d =>
        d.appendBlock(d.createBlock(Nil, generator = miner2))
        d.appendMicroBlockBy(miner2)(feePayingTransfer)

        d.balance(miner2.toAddress) shouldBe InitialMinerBalance + InitialReward + OneFee
        d.blockchainUpdater.liquidBlockMeta.map(_.totalFeeInWaves) shouldBe OneTotalFee.some
        d.carryFee(d.lastBlockId).map(_.wavesAmount) shouldBe Right(OneCarryFee)

        d.appendBlock(d.createBlock(Nil, generator = miner1))

        d.balance(miner1.toAddress) shouldBe InitialMinerBalance + InitialReward + OneCarryFee
        d.carryFee(d.lastBlockId).map(_.wavesAmount) shouldBe Right(0L)
      }

    "when received better liquid block" in
      withDomain(rewardSettings, genesisBalances, generators = Seq(miner1, miner2)) { d =>
        val parent = d.lastBlockId
        // Timestamped from the test clock rather than from PoS: this suite's genesis sits at the epoch, and the micro
        // block below carries a transaction stamped with the current time
        val now   = d.testTime.getTimestamp()
        val first = d.createBlock(Nil, generator = miner1, strictTime = true, timestamp = Some(now))
        // Same parent, earlier timestamp: better, so it replaces the liquid block and the fee it collected with it.
        // Both are built here, while that parent is still the head, so each carries the state hash of its own branch.
        val better =
          d.createBlock(Nil, ref = Some(parent), generator = miner2, strictTime = true, timestamp = Some(now - 1))

        d.appendBlock(first)
        d.appendMicroBlockBy(miner1)(feePayingTransfer)

        d.balance(miner1.toAddress) shouldBe InitialMinerBalance + InitialReward + OneFee
        d.carryFee(d.lastBlockId).map(_.wavesAmount) shouldBe Right(OneCarryFee)

        d.appendBlockE(better) should beRight

        d.balance(miner1.toAddress) shouldBe InitialMinerBalance
        d.balance(miner2.toAddress) shouldBe InitialMinerBalance + InitialReward
        d.blockchainUpdater.liquidBlockMeta.map(_.totalFeeInWaves) shouldBe 0L.some
        d.carryFee(d.lastBlockId).map(_.wavesAmount) shouldBe Right(0L)
      }

    "when all blocks without fees" in
      withDomain(rewardSettings, genesisBalances, generators = Seq(miner1, miner2)) { d =>
        val initialWavesAmount = BigInt(Constants.TotalWaves) * BigInt(Constants.UnitsInWave)

        (1 to 6).foreach { i =>
          val miner = if (i % 2 == 0) miner2 else miner1
          d.appendBlock(d.createBlock(Nil, generator = miner))

          // Every block mints the same reward, so the supply grows by it and the miner that produced the block holds it
          d.blockchain.height shouldBe i + 1
          d.blockchain.wavesAmount(i + 1) shouldBe initialWavesAmount + BigInt(InitialReward) * i
          d.balance(miner1.toAddress) shouldBe InitialMinerBalance + ((i + 1) / 2) * InitialReward
          d.balance(miner2.toAddress) shouldBe InitialMinerBalance + (i / 2) * InitialReward
        }
      }
  }

  "The reward is shared with the dao address" - {

    /** The share the DAO address gets of a block reward, by `BlockRewardCalculator.rewardSharesAt`: nothing at all
      * below the guaranteed miner reward, half of what is above it below the full reward, and a flat maximum from
      * there. Measured as the change across one block, since the miner also holds what the genesis gave it.
      */
    def daoShareOf(fullBlockReward: Long, withDaoAddress: Boolean): Unit = {
      val daoAddress = TxHelpers.address(1)
      val base       = DomainPresets.ConsensusImprovements
      val settings = base.copy(blockchainSettings =
        base.blockchainSettings.copy(
          rewardsSettings = base.blockchainSettings.rewardsSettings.copy(initial = fullBlockReward),
          functionalitySettings = base.blockchainSettings.functionalitySettings
            .copy(daoAddress = Some(daoAddress.toString).filter(_ => withDaoAddress))
        )
      )

      val expectedDaoShare =
        if (!withDaoAddress) 0L
        else if (fullBlockReward < BlockRewardCalculator.GuaranteedMinerReward) 0L
        else if (fullBlockReward < BlockRewardCalculator.FullRewardInit)
          (fullBlockReward - BlockRewardCalculator.GuaranteedMinerReward) / 2
        else BlockRewardCalculator.MaxAddressReward

      withDomain(settings) { d =>
        val miner = d.appendBlock().sender.toAddress

        val minerBefore = d.balance(miner)
        val daoBefore   = d.balance(daoAddress)

        d.appendBlock()

        withClue(s"full reward $fullBlockReward, dao address ${if (withDaoAddress) "defined" else "not defined"}: ") {
          d.balance(daoAddress) - daoBefore shouldBe expectedDaoShare
          d.balance(miner) - minerBefore shouldBe fullBlockReward - expectedDaoShare
        }
      }
    }

    "the dao address gets 2 WAVES when the full block reward is at least 6 WAVES" in
      Seq(6.waves, 7.waves).foreach(daoShareOf(_, withDaoAddress = true))

    "the dao address gets half of what is above the guaranteed miner reward below that" in
      Seq(2.waves, 3.waves).foreach(daoShareOf(_, withDaoAddress = true))

    "the miner gets the full block reward when it is below the guaranteed miner reward" in
      Seq(1.waves).foreach(daoShareOf(_, withDaoAddress = true))

    "the miner gets the full block reward when no dao address is defined" in
      Seq(1.waves, 2.waves, 3.waves, 6.waves, 7.waves).foreach(daoShareOf(_, withDaoAddress = false))
  }

  "Rolling back returns the reward shares of the blocks that were dropped" in {
    val daoAddress = TxHelpers.address(1)
    val fullReward = BlockRewardCalculator.FullRewardInit + 1.waves
    val base       = DomainPresets.ConsensusImprovements
    val settings = base.copy(blockchainSettings =
      base.blockchainSettings.copy(
        rewardsSettings = base.blockchainSettings.rewardsSettings.copy(initial = fullReward),
        functionalitySettings = base.blockchainSettings.functionalitySettings.copy(daoAddress = Some(daoAddress.toString))
      )
    )

    withDomain(settings) { d =>
      val miner = d.appendBlock().sender.toAddress
      d.appendBlock()

      val minerBefore  = d.balance(miner)
      val daoBefore    = d.balance(daoAddress)
      val heightBefore = d.blockchain.height

      d.appendBlock()
      d.appendBlock()

      d.balance(daoAddress) shouldBe daoBefore + 2 * BlockRewardCalculator.MaxAddressReward
      d.balance(miner) shouldBe minerBefore + 2 * (fullReward - BlockRewardCalculator.MaxAddressReward)

      d.rollbackTo(heightBefore)

      d.balance(daoAddress) shouldBe daoBefore
      d.balance(miner) shouldBe minerBefore

      d.appendBlock()

      d.balance(daoAddress) shouldBe daoBefore + BlockRewardCalculator.MaxAddressReward
      d.balance(miner) shouldBe minerBefore + fullReward - BlockRewardCalculator.MaxAddressReward
    }
  }
}
