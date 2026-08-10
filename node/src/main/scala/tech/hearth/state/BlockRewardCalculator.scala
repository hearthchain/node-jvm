package tech.hearth.state

import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.settings.Constants
import tech.hearth.state.diffs.BlockDiffer.Fraction

object BlockRewardCalculator {

  case class BlockRewardShares(miner: Long, daoAddress: Long) {
    private[BlockRewardCalculator] def multiply(by: Long): BlockRewardShares = BlockRewardShares(
      miner = miner * by,
      daoAddress = daoAddress * by
    )
  }

  val CurrentBlockRewardPart: Fraction   = Fraction(1, 3)
  val RemaindRewardAddressPart: Fraction = Fraction(1, 2)

  val FullRewardInit: Long        = 6 * Constants.UnitsInHearth
  val MaxAddressReward: Long      = 2 * Constants.UnitsInHearth
  val GuaranteedMinerReward: Long = 2 * Constants.UnitsInHearth
  val RewardBoost                 = 10

  // The genesis block (height 1) earns no reward (see mkInitialSnapshot); the emission curve's own h=0 is the
  // first block that does, height 2.
  private val FirstRewardedHeight = Height(2)

  def fullRewardAt(height: Height, blockchain: Blockchain): Long = {
    val h = height - FirstRewardedHeight
    if (h < 0) 0L
    else {
      val settings = blockchain.settings.rewardsSettings
      EmissionCurve.rewardAt(h, settings.initialReward, settings.decayRatioFixed)
    }
  }

  def rewardSharesAt(
      height: Height,
      fullBlockReward: Long,
      daoAddress: Option[Address]
  ): BlockRewardShares = {
    val blockRewardDistributionHeight = Height(1)
    val cappedRewardHeight            = Height(1)

    if (height >= blockRewardDistributionHeight) {
      if (height >= cappedRewardHeight) {
        if (fullBlockReward < GuaranteedMinerReward) {
          BlockRewardShares(fullBlockReward, 0)
        } else if (fullBlockReward < FullRewardInit) {
          calculateRewards(fullBlockReward, RemaindRewardAddressPart.apply(fullBlockReward - GuaranteedMinerReward), daoAddress)
        } else {
          calculateRewards(fullBlockReward, MaxAddressReward, daoAddress)
        }
      } else {
        calculateRewards(fullBlockReward, CurrentBlockRewardPart.apply(fullBlockReward), daoAddress)
      }
    } else BlockRewardShares(fullBlockReward, 0)
  }

  def getSortedBlockRewardShares(height: Int, fullBlockReward: Long, generator: Address, blockchain: Blockchain): Seq[(Address, Long)] = {
    val daoAddress = blockchain.settings.functionalitySettings.daoAddressParsed.toOption.flatten

    val rewardShares = rewardSharesAt(Height(height), fullBlockReward, daoAddress)

    import tech.hearth.utils.byteStrOrdering

    (Seq(generator -> rewardShares.miner) ++ daoAddress.map(_ -> rewardShares.daoAddress))
      .filter(_._2 > 0)
      .sortBy { case (addr, _) => ByteStr(addr.toBytes) }
  }

  def getSortedBlockRewardShares(height: Int, generator: Address, blockchain: Blockchain): Seq[(Address, Long)] = {
    val fullBlockReward = blockchain.blockReward(height).getOrElse(0L)
    getSortedBlockRewardShares(height, fullBlockReward, generator, blockchain)
  }

  private def calculateRewards(blockReward: Long, addressReward: Long, daoAddress: Option[Address]) = {
    val daoAddressReward = daoAddress.fold(0L) { _ => addressReward }
    BlockRewardShares(blockReward - daoAddressReward, daoAddressReward)
  }
}
