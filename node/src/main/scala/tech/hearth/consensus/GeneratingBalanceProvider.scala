package tech.hearth.consensus

import tech.hearth.block.Block.BlockId
import tech.hearth.state.{Blockchain, GenerationPeriod, Height}
import tech.hearth.crypto.Address

object GeneratingBalanceProvider {
  val MinimalEffectiveBalanceForGenerator: Long = 100000000000L

  private val SecondDepth = 1000

  def isMiningAllowed(generatingBalance: Long): Boolean =
    generatingBalance >= MinimalEffectiveBalanceForGenerator

  def isGeneratingBalanceValid(balance: Long): Boolean =
    isMiningAllowed(balance)

  def balance(blockchain: Blockchain, account: Address, blockId: Option[BlockId] = None): Long = {
    val height = blockId.flatMap(blockchain.heightOf).getOrElse(blockchain.height)
    balanceAt(blockchain, account, height, blockId, workContext(blockchain, height))
  }

  /** hearth-tokenomics-spec S7.1: work done in a generation period boosts generating balance in the *next* one -
    * "let epoch be a generation period" (see CLAUDE.md's "workBoost"). Precomputes the (workPeriod, totalWork)
    * pair once for callers - like appender.findBlockAndGetGenerators - that need to boost many accounts' balances
    * for the same block: without this, each of those accounts' own `balance` call would independently re-sum the
    * whole committee's work from scratch (an O(committee) scan), making building one block's generator set
    * O(committee^2). `atHeight` is the height a balance is being computed *as of* (i.e. `blockId`'s height, or the
    * chain tip) - the period being generated into is whichever one the block right after it falls in, matching
    * `appender.findBlockAndGetGenerators`'s own `parentHeight.next` -> `generationPeriodOf` derivation.
    *
    * The sum itself is Blockchain.totalWork, shared with StakingPayout so the two consensus readings of a period's
    * work can never drift apart.
    */
  def workContext(blockchain: Blockchain, atHeight: Int): Option[(GenerationPeriod, BigInt)] =
    blockchain.generationPeriodOf(Height(atHeight + 1)).flatMap(_.prev).map(workPeriod => workPeriod -> blockchain.totalWork(workPeriod))

  /** Same as `balance`, but reuses a `workContext` the caller already computed once instead of recomputing it -
    * see `workContext`'s own doc comment for why this matters.
    */
  def balanceWithContext(blockchain: Blockchain, account: Address, blockId: Option[BlockId], context: Option[(GenerationPeriod, BigInt)]): Long = {
    val height = blockId.flatMap(blockchain.heightOf).getOrElse(blockchain.height)
    balanceAt(blockchain, account, height, blockId, context)
  }

  /** Effective balance with the address's stake taken out: staking HRTH costs forging weight, not just liquidity.
    *
    * The term subtracted is `StakeRecord.active`, not `locked` - the stake the address is *earning* Cred on this
    * period, so the same embers buy the yield and pay for it. That is also what keeps this safe to read as a
    * current value against a windowed `effectiveBalance`: `active` only ever moves in `StakeRecord.activated`, at
    * a period boundary, so forging weight changes only where the committee itself does. Subtracting `locked`
    * instead would let any committed generator zero its own generating balance mid-period with one cheap
    * transaction and no fund movement, which hands it a lever over `EndorsementFilter`'s 2/3 quorum denominator
    * and over the `validGenerators.nonEmpty` case in `appender.findBlockAndGetGenerators`. The HRTH a raised stake
    * locks is still unspendable in the meantime, through `lockedStake`; it just keeps counting as skin in the game
    * until the period it was staked for actually starts.
    *
    * Every consensus caller hands in a blockchain positioned at the block it is asking about
    * (`appender.appendBlock` does it explicitly, with `blockchainUpdater.referencedBlockchain(...)`), so reading
    * the stake off `blockchain` rather than as of `blockId` resolves to the same state - the same property
    * `workContext`'s own `workDone` read already relies on. A future caller passing a `blockId` older than the
    * blockchain's tip would break that and would need the stake resolved at that height instead.
    */
  def unstakedEffectiveBalance(blockchain: Blockchain, account: Address, depth: Int, blockId: Option[BlockId] = None): Long =
    math.max(0L, blockchain.effectiveBalance(account, depth, blockId) - blockchain.stakedForPeriod(account))

  private def balanceAt(
      blockchain: Blockchain,
      account: Address,
      height: Int,
      blockId: Option[BlockId],
      context: Option[(GenerationPeriod, BigInt)]
  ): Long = {
    val depth = SecondDepth

    val maybeChallengedMiner = blockchain.blockHeader(height + 1).flatMap(_.header.challengedHeader).map(_.generator.toAddress)
    val rawBalance =
      unstakedEffectiveBalance(blockchain, account, depth, blockId) +
        maybeChallengedMiner.map(unstakedEffectiveBalance(blockchain, _, depth, blockId)).getOrElse(0L)

    context match {
      case None                          => rawBalance
      case Some((workPeriod, totalWork)) => WorkBoost(rawBalance, blockchain.workDone(account, workPeriod), totalWork)
    }
  }
}
