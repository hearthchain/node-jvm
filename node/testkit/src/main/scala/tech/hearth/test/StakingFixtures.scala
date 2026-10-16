package tech.hearth.test

import tech.hearth.TestValues
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.SigningKey
import tech.hearth.history.Domain
import tech.hearth.settings.{GenesisAssetSettings, HearthSettings}
import tech.hearth.state.{GenerationPeriod, Height}
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers

/** Domain setup shared by the suites that drive stakes across period boundaries. */
object StakingFixtures {

  /** Long enough that the block after a boundary block is still inside the same period: a transaction is validated
    * against the period of the block carrying it, so a commitment or a stake for the next period has to land before
    * the next boundary, not on it. Periods are [1, 4], [5, 8], [9, 12], ...
    */
  val PeriodLength = 4

  val FirstPeriod: GenerationPeriod = GenerationPeriod(Height(1), PeriodLength)

  val CredAsset: IssuedAsset = IssuedAsset(ByteStr.fill(32)(3))

  /** Issues [[CredAsset]] at genesis with no supply: a payout mints into an asset that already exists. */
  val CredGenesis: GenesisAssetSettings =
    GenesisAssetSettings(CredAsset.id.toString, "CRED", decimals = 0, quantity = 0, minFee = TestValues.fee)

  def stakingSettings(credAsset: Option[IssuedAsset] = Some(CredAsset)): HearthSettings =
    DeterministicFinality.configure(_.copy(generationPeriodLength = PeriodLength, credAsset = credAsset.map(_.id.toString)))

  /** Advances to the first block of the next period, committing `miner` for it first so that it can mine there. Leaves
    * the chain on the boundary block itself, which is where the payout happens.
    */
  def crossPeriodBoundary(d: Domain, miner: SigningKey = TxHelpers.defaultSigner, afterEachBlock: Domain => Unit = _ => ()): Unit = {
    val nextStart = d.blockchain.currentGenerationPeriod.get.next.start
    // In the very next block, which PeriodLength keeps inside the current period: CommitToGenerationTransactionDiff
    // would reject it in the boundary block itself, where "the next period" has already moved on by one
    d.appendBlock(TxHelpers.commitToGeneration(nextStart, miner))
    afterEachBlock(d)
    while (d.blockchain.height < nextStart.toInt) {
      d.appendBlock()
      afterEachBlock(d)
    }
  }

  def stakeForNextPeriod(d: Domain, staker: SigningKey, amount: Long): Unit =
    d.appendBlock(TxHelpers.stake(staker, periodStart = d.blockchain.currentGenerationPeriod.get.next.start, amount = amount))
}
