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
    * totalWork is a BigInt sum, not a plain Long one: individual `workDone` values are bounded (safeSum'd at
    * write time in SettleTransactionDiff), but summing across an unbounded number of committed generators isn't
    * itself guaranteed to fit a Long.
    */
  def workContext(blockchain: Blockchain, atHeight: Int): Option[(GenerationPeriod, BigInt)] =
    blockchain.generationPeriodOf(Height(atHeight + 1)).flatMap(_.prev).map { workPeriod =>
      val totalWork = blockchain.committedGenerators(workPeriod).view.map(g => BigInt(blockchain.workDone(g.address, workPeriod))).sum
      workPeriod -> totalWork
    }

  /** Same as `balance`, but reuses a `workContext` the caller already computed once instead of recomputing it -
    * see `workContext`'s own doc comment for why this matters.
    */
  def balanceWithContext(blockchain: Blockchain, account: Address, blockId: Option[BlockId], context: Option[(GenerationPeriod, BigInt)]): Long = {
    val height = blockId.flatMap(blockchain.heightOf).getOrElse(blockchain.height)
    balanceAt(blockchain, account, height, blockId, context)
  }

  /** Effective balance with the address's locked stake taken out: staking HRTH costs forging weight, not just
    * liquidity (see StakeTransactionDiff).
    *
    * `effectiveBalance` is a minimum over a lookback window, but the stake subtracted from it is the *current*
    * locked amount rather than a maximum over that same window, and deliberately so. A stake only ever drops at a
    * generation-period boundary, which is the same moment the HRTH it locked genuinely becomes spendable again
    * (see StakeRecord.locked), so there is no interval in which this reports a higher balance than the account was
    * really entitled to. Raising a stake, conversely, takes effect on the spot. That makes the whole stake ledger a
    * single current value per address rather than a windowed history the way the balance itself needs to be.
    */
  private def unstakedEffectiveBalance(blockchain: Blockchain, account: Address, depth: Int, blockId: Option[BlockId]): Long =
    math.max(0L, blockchain.effectiveBalance(account, depth, blockId) - blockchain.lockedStake(account))

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
