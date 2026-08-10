package tech.hearth.state

import tech.hearth.test.PropSpec
import tech.hearth.utils.EmptyBlockchain

class BlockRewardCalculatorTest extends PropSpec {

  private object TestChain extends EmptyBlockchain

  property("the first rewarded block (height 2) earns the initial reward exactly") {
    BlockRewardCalculator.fullRewardAt(Height(2), TestChain) shouldBe TestChain.settings.rewardsSettings.initialReward
  }

  property("the genesis block (height 1) earns no reward") {
    BlockRewardCalculator.fullRewardAt(Height(1), TestChain) shouldBe 0L
  }

  property("the reward at height 100 matches EmissionCurve.rewardAt(98, ...)") {
    val settings = TestChain.settings.rewardsSettings
    BlockRewardCalculator.fullRewardAt(Height(100), TestChain) shouldBe
      EmissionCurve.rewardAt(98, settings.initialReward, settings.decayRatioFixed)
  }
}
