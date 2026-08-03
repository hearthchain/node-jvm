package tech.hearth.state.diffs

import cats.implicits.catsSyntaxSemigroup
import cats.syntax.either.*
import tech.hearth.account.Address
import tech.hearth.block.{Block, BlockSnapshot, FinalizationVoting, MicroBlock, MicroBlockSnapshot, SignedBlockHeader}
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.mining.MiningConstraint
import tech.hearth.state.*
import tech.hearth.state.StateSnapshot.monoid
import tech.hearth.state.TxStateSnapshotHashBuilder.TxStatusInfo
import tech.hearth.transaction.TxValidationError.*
import tech.hearth.transaction.assets.exchange.ExchangeTransaction
import tech.hearth.transaction.lease.LeaseTransaction
import tech.hearth.transaction.smart.script.trace.TracedResult
import tech.hearth.transaction.transfer.MassTransferTransaction.ParsedTransfer
import tech.hearth.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import tech.hearth.transaction.{Asset, Authorized, BlockchainUpdater, CommitToGenerationTransaction, Transaction}

import scala.collection.immutable.VectorMap

object BlockDiffer {
  final case class Result(
      snapshot: StateSnapshot,
      carry: BlockFee,
      totalFee: BlockFee,
      constraint: MiningConstraint,
      keyBlockSnapshot: StateSnapshot,
      computedStateHash: ByteStr
  )

  case class Fraction(dividend: Int, divider: Int) {
    def apply(l: Long): Long = l / divider * dividend
  }

  case class TxFeeInfo(feeAsset: Asset, feeAmount: Long, carry: Portfolio, fee: Portfolio)

  val CurrentBlockFeePart: Fraction = Fraction(2, 5)

  def fromBlock(
      blockchain: Blockchain,
      maybePrevBlock: Option[SignedBlockHeader],
      block: Block,
      snapshot: Option[BlockSnapshot],
      constraint: MiningConstraint,
      hitSource: ByteStr,
      challengedHitSource: Option[ByteStr] = None,
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit = (_, _) => (),
      verify: Boolean = true,
      txSignParCheck: Boolean = true
  ): Either[ValidationError, Result] = {
    challengedHitSource match {
      case Some(hs) if snapshot.isEmpty =>
        fromBlockTraced(
          blockchain,
          maybePrevBlock,
          block.toOriginal,
          snapshot,
          constraint,
          hs,
          loadCacheData,
          verify,
          txSignParCheck
        ).resultE match {
          case Left(_: InvalidStateHash) =>
            fromBlockTraced(
              blockchain,
              maybePrevBlock,
              block,
              snapshot,
              constraint,
              hitSource,
              loadCacheData,
              verify,
              txSignParCheck
            ).resultE
          case Left(err) => Left(GenericError(s"Invalid block challenge: $err"))
          case _         => Left(GenericError("Invalid block challenge"))
        }
      case _ =>
        fromBlockTraced(
          blockchain,
          maybePrevBlock,
          block,
          snapshot,
          constraint,
          hitSource,
          loadCacheData,
          verify,
          txSignParCheck
        ).resultE
    }
  }

  def fromBlockTraced(
      blockchain: Blockchain,
      maybePrevBlock: Option[SignedBlockHeader],
      block: Block,
      snapshot: Option[BlockSnapshot],
      constraint: MiningConstraint,
      hitSource: ByteStr,
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit,
      verify: Boolean,
      txSignParCheck: Boolean
  ): TracedResult[ValidationError, Result] = {
    val blockchainWithNewBlock = SnapshotBlockchain(
      blockchain,
      StateSnapshot.empty,
      block,
      hitSource,
      BlockFee.empty,
      Some(BlockRewardCalculator.fullRewardAt(Height(blockchain.height + 1), blockchain)),
      None
    )
    val initSnapshotE = mkInitialSnapshot(blockchain, block.header.reference, maybePrevBlock, block.sender.toAddress)

    for {
      _            <- TracedResult(Either.cond(!verify || block.signatureValid(), (), GenericError(s"Block $block has invalid signature")))
      initSnapshot <- TracedResult(initSnapshotE)
      prevStateHash = maybePrevBlock.flatMap(_.header.stateHash).getOrElse(blockchain.lastStateHash(None))
      hasChallenge  = block.header.challengedHeader.isDefined
      r <- snapshot match {
        case Some(BlockSnapshot(_, txSnapshots)) =>
          TracedResult(apply(blockchainWithNewBlock, prevStateHash, initSnapshot, block.transactionData, txSnapshots))
        case None =>
          apply(
            blockchainWithNewBlock,
            constraint,
            maybePrevBlock.map(_.header.timestamp),
            prevStateHash,
            initSnapshot,
            hasChallenge,
            block.transactionData,
            loadCacheData,
            verify = verify,
            txSignParCheck = txSignParCheck
          )
      }
      _ <- checkStateHash(blockchainWithNewBlock, block.header.stateHash, r.computedStateHash)
    } yield r
  }

  private def mkInitialSnapshot(blockchain: Blockchain, reference: ByteStr, maybePrevBlock: Option[SignedBlockHeader], minerAddress: Address) = {
    val heightWithNewBlock = Height(blockchain.height)

    val addressRewardsE: Either[String, (Portfolio, Map[Address, Portfolio])] = for {
      daoAddress <- blockchain.settings.functionalitySettings.daoAddressParsed
    } yield {
      val blockRewardShares = BlockRewardCalculator.rewardSharesAt(
        heightWithNewBlock,
        blockchain.lastBlockReward.getOrElse(0L),
        daoAddress
      )
      (
        Portfolio.waves(blockRewardShares.miner),
        daoAddress.fold(Map[Address, Portfolio]())(addr => Map(addr -> Portfolio.waves(blockRewardShares.daoAddress)).filter(_._2.balance > 0))
      )
    }

    if (heightWithNewBlock == Height(0))
      // The genesis block has no transactions and earns no reward: its whole effect is the predefined snapshot
      GenesisSnapshot.build(blockchain.settings.genesisSettings)
    else
      (for {
        (minerReward, daoPortfolio) <- addressRewardsE
        // the block is applied on top of `blockchain`, so it has to reference its last block. this used to be an
        // unwritten assumption; the carry fee below is only the right one if it actually holds
        _ <- Either.cond(
          blockchain.lastBlockId.contains(reference),
          (),
          s"Block references $reference, but the blockchain it is applied to is at ${blockchain.lastBlockId.getOrElse("empty")}"
        )
        // the referenced block collected these fees per transaction; the miner of this block gets to keep them
        feeFromPreviousBlock <- blockchain.carryFee(reference)
        totalMinerReward     <- minerReward.combine(feeFromPreviousBlock.pf)
        totalMinerPortfolio = Map(minerAddress -> totalMinerReward)
        totalRewardPortfolios <- Portfolio.combine(totalMinerPortfolio, daoPortfolio)
        penalties <- maybePrevBlock match {
          case Some(prevBlock) => calculatePenalties(blockchain, prevBlock)
          case None            => Map.empty[Address, Portfolio].asRight[String]
        }
        withPenaltiesPortfolios <- Portfolio.combine(penalties, totalRewardPortfolios)
        resultSnapshot          <- StateSnapshot.empty.addBalances(withPenaltiesPortfolios, blockchain)
      } yield resultSnapshot).leftMap(GenericError(_))
  }

  def fromMicroBlock(
      blockchain: Blockchain,
      prevBlockTimestamp: Option[Long],
      prevStateHash: ByteStr,
      micro: MicroBlock,
      snapshot: Option[MicroBlockSnapshot],
      constraint: MiningConstraint,
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit = (_, _) => (),
      verify: Boolean = true
  ): Either[ValidationError, Result] =
    fromMicroBlockTraced(
      blockchain,
      prevBlockTimestamp,
      prevStateHash,
      micro,
      snapshot,
      constraint,
      loadCacheData,
      verify
    ).resultE

  private def fromMicroBlockTraced(
      blockchain: Blockchain,
      prevBlockTimestamp: Option[Long],
      prevStateHash: ByteStr,
      micro: MicroBlock,
      snapshot: Option[MicroBlockSnapshot],
      constraint: MiningConstraint,
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit,
      verify: Boolean
  ): TracedResult[ValidationError, Result] = {
    for {
      _ <- TracedResult(micro.signaturesValid())
      r <- snapshot match {
        case Some(MicroBlockSnapshot(_, txSnapshots)) =>
          TracedResult(apply(blockchain, prevStateHash, StateSnapshot.empty, micro.transactionData, txSnapshots))
        case None =>
          apply(
            blockchain,
            constraint,
            prevBlockTimestamp,
            prevStateHash,
            StateSnapshot.empty,
            hasChallenge = false,
            micro.transactionData,
            loadCacheData,
            verify = verify,
            txSignParCheck = true
          )
      }
      _ <- checkStateHash(blockchain, micro.stateHash, r.computedStateHash)
    } yield r
  }

  private def calculatePenalties(blockchain: Blockchain, prevBlock: SignedBlockHeader): Either[String, Map[Address, Portfolio]] = {
    val empty = Map.empty[Address, Portfolio].asRight[String]
    val parentBlockInfo = for {
      voting     <- prevBlock.header.finalizationVoting
      prevHeight <- blockchain.heightOf(prevBlock.id())
      period     <- blockchain.generationPeriodOf(Height(prevHeight))
    } yield (period, voting)

    parentBlockInfo.fold(empty) { case (period, voting) =>
      calculatePenalties(blockchain, period, voting)
    }
  }

  private def calculatePenalties(
      blockchain: Blockchain,
      prevBlockPeriod: GenerationPeriod,
      prevBlockVoting: FinalizationVoting
  ): Either[String, Map[Address, Portfolio]] = {
    val empty          = Map.empty[Address, Portfolio].asRight[String]
    lazy val committed = blockchain.committedGenerators(prevBlockPeriod)
    prevBlockVoting.conflict.foldLeft(empty) {
      case (r @ Left(_), _) => r
      case (Right(r), endorsement) =>
        committed.lift(endorsement.endorserIndex.toInt) match {
          case None => Left(s"Invalid endorsement index in $endorsement, valid: [0; ${committed.size}]")
          case Some(cg) =>
            val orig    = r.getOrElse(cg.address, Portfolio.empty)
            val updated = orig.combine(Portfolio.waves(-CommitToGenerationTransaction.DepositInWavelets))
            updated.map(r.updated(cg.address, _))
        }
    }
  }

  def createInitialBlockSnapshot(
      blockchainUpdater: BlockchainUpdater & Blockchain,
      reference: ByteStr,
      miner: Address,
      maybePrevBlock: Option[SignedBlockHeader]
  ): Either[ValidationError, StateSnapshot] =
    mkInitialSnapshot(blockchainUpdater.referencedBlockchain(reference), reference, maybePrevBlock, miner)

  def computeInitialStateHash(initSnapshot: StateSnapshot, prevStateHash: ByteStr): ByteStr =
    if (initSnapshot == StateSnapshot.empty)
      prevStateHash
    else
      TxStateSnapshotHashBuilder.createHashFromSnapshot(initSnapshot, None).createHash(prevStateHash)

  private def apply(
      blockchain: Blockchain,
      initConstraint: MiningConstraint,
      prevBlockTimestamp: Option[Long],
      prevStateHash: ByteStr,
      initSnapshot: StateSnapshot,
      hasChallenge: Boolean,
      txs: Seq[Transaction],
      loadCacheData: (Set[Address], Set[ByteStr]) => Unit,
      verify: Boolean,
      txSignParCheck: Boolean
  ): TracedResult[ValidationError, Result] = {
    val timestamp      = blockchain.lastBlockTimestamp.get
    val blockGenerator = blockchain.lastBlockHeader.get.header.generator.toAddress

    val txDiffer = TransactionDiffer(prevBlockTimestamp, timestamp, verify)

    if (verify && txSignParCheck)
      ParSignatureChecker.checkTxSignatures(txs)

    prepareCaches(blockGenerator, txs, loadCacheData)

    val initStateHash = computeInitialStateHash(initSnapshot, prevStateHash)
    txs
      .foldLeft(
        TracedResult(Result(initSnapshot, BlockFee.empty, BlockFee.empty, initConstraint, initSnapshot, initStateHash).asRight[ValidationError])
      ) {
        case (acc @ TracedResult(Left(_), _, _), _) => acc
        case (
              TracedResult(
                Right(
                  result @ Result(currSnapshot, carryFee, currTotalFee, currConstraint, keyBlockSnapshot, prevStateHash)
                ),
                _,
                _
              ),
              tx
            ) =>
          val currBlockchain = SnapshotBlockchain(blockchain, currSnapshot)
          val res = txDiffer(currBlockchain, tx).flatMap { txSnapshot =>
            val updatedConstraint = currConstraint.put(currBlockchain, tx, txSnapshot)
            if (updatedConstraint.isOverfilled)
              TracedResult(Left(GenericError(s"Limit of txs was reached: $initConstraint -> $updatedConstraint")))
            else {
              val txFeeInfo = computeTxFeeInfo(tx)

              // the miner of this block only gets their 40% of the fee; the rest is carried over to the next block
              val minerPortfolio    = Portfolio.build(txFeeInfo.feeAsset, txFeeInfo.feeAmount).multiply(CurrentBlockFeePart)
              val minerPortfolioMap = Map(blockGenerator -> minerPortfolio)

              for {
                resultTxSnapshot <- txSnapshot.addBalances(minerPortfolioMap, currBlockchain).leftMap(GenericError(_))
                newCarryFee      <- carryFee.combine(txFeeInfo.carry).leftMap(GenericError(_))
                newTotalFee      <- currTotalFee.combine(txFeeInfo.fee).leftMap(GenericError(_))
              } yield {
                val (_, txInfo)         = txSnapshot.transactions.head
                val txInfoWithFee       = txInfo.copy(snapshot = resultTxSnapshot.copy(transactions = VectorMap.empty))
                val newKeyBlockSnapshot = keyBlockSnapshot.withTransaction(txInfoWithFee)

                val newSnapshot = currSnapshot |+| resultTxSnapshot.withTransaction(txInfoWithFee)

                Result(
                  newSnapshot,
                  newCarryFee,
                  newTotalFee,
                  updatedConstraint,
                  newKeyBlockSnapshot,
                  TxStateSnapshotHashBuilder
                    .createHashFromSnapshot(resultTxSnapshot, Some(TxStatusInfo(txInfo.transaction.id(), txInfo.status)))
                    .createHash(prevStateHash)
                )
              }
            }
          }

          res.copy(resultE = res.resultE.recover {
            case _ if hasChallenge =>
              result.copy(
                snapshot = result.snapshot.bindElidedTransaction(currBlockchain, tx),
                computedStateHash = TxStateSnapshotHashBuilder
                  .createHashFromSnapshot(StateSnapshot.empty, Some(TxStatusInfo(tx.id(), TxMeta.Status.Elided)))
                  .createHash(result.computedStateHash)
              )
          })
      }
  }

  private def apply(
      blockchain: Blockchain,
      prevStateHash: ByteStr,
      initSnapshot: StateSnapshot,
      txs: Seq[Transaction],
      txSnapshots: Seq[(StateSnapshot, TxMeta.Status)]
  ): Either[ValidationError, Result] = {
    val initStateHash = computeInitialStateHash(initSnapshot, prevStateHash)
    val init          = Result(initSnapshot, BlockFee.empty, BlockFee.empty, MiningConstraint.Unlimited, initSnapshot, initStateHash)
    txs.zip(txSnapshots).foldLeft(init.asRight[ValidationError]) {
      case (error @ Left(_), _) => error
      case (
            Right(Result(currSnapshot, carryFee, currTotalFee, currConstraint, keyBlockSnapshot, prevStateHash)),
            (tx, (txSnapshot, txStatus))
          ) =>
        val currBlockchain = SnapshotBlockchain(blockchain, currSnapshot)

        val txFeeInfo = if (txStatus == TxMeta.Status.Elided) None else Some(computeTxFeeInfo(tx))
        val nti       = NewTransactionInfo.create(tx, txStatus, txSnapshot, currBlockchain)

        for {
          newCarryFee <- carryFee.combine(txFeeInfo.fold(Portfolio.empty)(_.carry)).leftMap(GenericError(_))
          newTotalFee <- currTotalFee.combine(txFeeInfo.fold(Portfolio.empty)(_.fee)).leftMap(GenericError(_))
        } yield Result(
          currSnapshot |+| txSnapshot.withTransaction(nti),
          newCarryFee,
          newTotalFee,
          currConstraint,
          keyBlockSnapshot.withTransaction(nti),
          TxStateSnapshotHashBuilder.createHashFromSnapshot(txSnapshot, Some(TxStatusInfo(tx.id(), txStatus))).createHash(prevStateHash)
        )
    }
  }

  private def computeTxFeeInfo(tx: Transaction): TxFeeInfo = {
    val (feeAsset, feeAmount) = tx.assetFee
    val fee                   = Portfolio.build(feeAsset, feeAmount)

    // carry is what's left of the fee after the miner of this block has taken their part: the miner of the next
    // block will get it. it's important to take the fraction per transaction (instead of taking a fraction of the
    // summed up fees), so that the carry matches the sum of the per-transaction shares credited below
    val carry = fee.minus(fee.multiply(CurrentBlockFeePart))

    TxFeeInfo(feeAsset, feeAmount, carry, fee)
  }

  private def prepareCaches(blockGenerator: Address, txs: Seq[Transaction], loadCacheData: (Set[Address], Set[ByteStr]) => Unit): Unit = {
    val addresses = Set.newBuilder[Address].addOne(blockGenerator)
    val orders    = Set.newBuilder[ByteStr]

    txs.foreach {
      case tx: ExchangeTransaction =>
        addresses.addAll(Seq(tx.sender.toAddress, tx.buyOrder.senderAddress, tx.sellOrder.senderAddress))
        orders.addOne(tx.buyOrder.id()).addOne(tx.sellOrder.id())
      case tx: LeaseTransaction =>
        addresses.addAll(Seq(tx.sender.toAddress, tx.recipient))
      case tx: MassTransferTransaction =>
        addresses.addAll(Seq(tx.sender.toAddress) ++ tx.transfers.collect { case ParsedTransfer(addr: Address, _) => addr })
      case tx: TransferTransaction =>
        addresses.addAll(Seq(tx.sender.toAddress, tx.recipient))
      case tx: Authorized => addresses.addOne(tx.sender.toAddress)
      case _              => ()
    }

    loadCacheData(addresses.result(), orders.result())
  }

  private def checkStateHash(
      blockchain: Blockchain,
      blockStateHash: Option[ByteStr],
      computedStateHash: ByteStr
  ): TracedResult[ValidationError, Unit] =
    Either.cond(
      !blockchain.supportsLightNodeBlockFields() || blockStateHash.contains(computedStateHash),
      (),
      InvalidStateHash(blockStateHash, Some(computedStateHash))
    )
}
