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

  /** The least the address could forge on at any height of the window ending at `blockId`: balance plus leases in,
    * less leases out, the generation deposit and the stake lock (see BalanceSnapshot.effectiveBalance).
    */
  private def windowedBalance(blockchain: Blockchain, account: Address, height: Int, blockId: Option[BlockId]): Long = {
    val bottom   = (height - SecondDepth + 1).max(1).min(height)
    val balances = blockchain.balanceSnapshots(account, bottom, blockId)
    val isBanned = blockchain.effectiveBalanceBanHeights(account).exists(h => h >= bottom && h <= height)
    if (balances.isEmpty || isBanned) 0L else balances.view.map(_.effectiveBalance).min
  }

  private def balanceAt(
      blockchain: Blockchain,
      account: Address,
      height: Int,
      blockId: Option[BlockId],
      context: Option[(GenerationPeriod, BigInt)]
  ): Long = {
    val maybeChallengedMiner = blockchain.blockHeader(height + 1).flatMap(_.header.challengedHeader).map(_.generator.toAddress)
    val rawBalance =
      windowedBalance(blockchain, account, height, blockId) +
        maybeChallengedMiner.map(windowedBalance(blockchain, _, height, blockId)).getOrElse(0L)

    context match {
      case None                          => rawBalance
      case Some((workPeriod, totalWork)) => WorkBoost(rawBalance, blockchain.workDone(account, workPeriod), totalWork)
    }
  }
}
