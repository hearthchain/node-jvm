package com.wavesplatform.state.diffs

import cats.implicits.{catsSyntaxSemigroup, toFoldableOps}
import cats.syntax.either.*
import com.wavesplatform.account.Address
import com.wavesplatform.block.{Block, BlockSnapshot, FinalizationVoting, MicroBlock, MicroBlockSnapshot}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.mining.MiningConstraint
import com.wavesplatform.state.*
import com.wavesplatform.state.StateSnapshot.monoid
import com.wavesplatform.state.TxStateSnapshotHashBuilder.TxStatusInfo
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.TxValidationError.*
import com.wavesplatform.transaction.assets.exchange.ExchangeTransaction
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.smart.script.trace.TracedResult
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.wavesplatform.transaction.{Asset, Authorized, BlockchainUpdater, CommitToGenerationTransaction, Transaction}

import scala.collection.immutable.VectorMap

object BlockDiffer {
  final case class Result(
      snapshot: StateSnapshot,
      carry: Long,
      totalFee: Long,
      constraint: MiningConstraint,
      keyBlockSnapshot: StateSnapshot,
      computedStateHash: ByteStr
  )

  case class Fraction(dividend: Int, divider: Int) {
    def apply(l: Long): Long = l / divider * dividend
  }

  case class TxFeeInfo(feeAsset: Asset, feeAmount: Long, carry: Long, wavesFee: Long)

  val CurrentBlockFeePart: Fraction = Fraction(2, 5)

  def fromBlock(
      blockchain: Blockchain,
      maybePrevBlock: Option[Block],
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
      maybePrevBlock: Option[Block],
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
      0,
      Some(BlockRewardCalculator.fullRewardAt(Height(blockchain.height + 1), blockchain)),
      None
    )
    val initSnapshotE = mkInitialSnapshot(blockchain, maybePrevBlock, block.sender.toAddress)

    println(s"\n\tHEIGHT:${blockchainWithNewBlock.height}\n\tBDIF: $initSnapshotE")

    for {
      _            <- TracedResult(Either.cond(!verify || block.signatureValid(), (), GenericError(s"Block $block has invalid signature")))
      initSnapshot <- TracedResult(initSnapshotE)
      prevStateHash = maybePrevBlock.flatMap(_.header.stateHash).getOrElse(blockchain.lastStateHash(None))
      hasChallenge  = block.header.challengedHeader.isDefined
      r <- snapshot match {
        case Some(BlockSnapshot(_, txSnapshots)) =>
          TracedResult.wrapValue(
            apply(blockchainWithNewBlock, prevStateHash, initSnapshot, block.transactionData, txSnapshots)
          )
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

  private def mkInitialSnapshot(blockchain: Blockchain, maybePrevBlock: Option[Block], minerAddress: Address) = {
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
      GenesisSnapshot.build(blockchain.settings.genesisSettings, blockchain.settings.functionalitySettings)
    else
      (for {
        (minerReward, daoPortfolio) <- addressRewardsE
        feeFromPreviousBlock        <- prevBlockFeePart(maybePrevBlock)
        _ = println(s"FEE FROM PREV: $feeFromPreviousBlock")
        totalMinerReward            <- minerReward.combine(feeFromPreviousBlock)
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
          TracedResult.wrapValue(apply(blockchain, prevStateHash, StateSnapshot.empty, micro.transactionData, txSnapshots))
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

  private def calculatePenalties(blockchain: Blockchain, prevBlock: Block): Either[String, Map[Address, Portfolio]] = {
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

  private def prevBlockFeePart(maybePrevBlock: Option[Block]): Either[String, Portfolio] =
    // it's important to combine tx fee fractions (instead of getting a fraction of the combined tx fee)
    // so that we end up with the same value as when computing per-transaction fee part
    // during microblock processing below
    maybePrevBlock.fold(Right(Portfolio.empty)) { b =>
      b.transactionData
        .map { t =>
          val pf = Portfolio.build(t.assetFee)
          pf.minus(pf.multiply(CurrentBlockFeePart))
        }
        .foldM(Portfolio.empty)(_.combine(_))
    }

  def createInitialBlockSnapshot(
      blockchainUpdater: BlockchainUpdater & Blockchain,
      reference: ByteStr,
      miner: Address,
      maybePrevBlock: Option[Block]
  ): Either[ValidationError, StateSnapshot] =
    mkInitialSnapshot(blockchainUpdater.referencedBlockchain(reference), maybePrevBlock, miner)

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
    val timestamp       = blockchain.lastBlockTimestamp.get
    val blockGenerator  = blockchain.lastBlockHeader.get.header.generator.toAddress
    val rideV6Activated = true // RideV6 is active

    val txDiffer = TransactionDiffer(prevBlockTimestamp, timestamp, verify)

    if (verify && txSignParCheck)
      ParSignatureChecker.checkTxSignatures(txs, rideV6Activated)

    prepareCaches(blockGenerator, txs, loadCacheData)

    val initStateHash = computeInitialStateHash(initSnapshot, prevStateHash)
    txs
      .foldLeft(TracedResult(Result(initSnapshot, 0L, 0L, initConstraint, initSnapshot, initStateHash).asRight[ValidationError])) {
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

              // unless NG is activated, miner has already received all the fee from this block by the time the first
              // transaction is processed (see abode), so there's no need to include tx fee into portfolio.
              // if NG is activated, just give them their 40%
              val minerPortfolio    = Portfolio.build(txFeeInfo.feeAsset, txFeeInfo.feeAmount).multiply(CurrentBlockFeePart)
              val minerPortfolioMap = Map(blockGenerator -> minerPortfolio)

              println(s"\n\tMINER: $minerPortfolioMap")

              txSnapshot.addBalances(minerPortfolioMap, currBlockchain).leftMap(GenericError(_)).map { resultTxSnapshot =>
                val (_, txInfo)         = txSnapshot.transactions.head
                val txInfoWithFee       = txInfo.copy(snapshot = resultTxSnapshot.copy(transactions = VectorMap.empty))
                val newKeyBlockSnapshot = keyBlockSnapshot.withTransaction(txInfoWithFee)

                val newSnapshot = currSnapshot |+| resultTxSnapshot.withTransaction(txInfoWithFee)

                Result(
                  newSnapshot,
                  carryFee + txFeeInfo.carry,
                  currTotalFee + txFeeInfo.wavesFee,
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
  ): Result = {
    val initStateHash = computeInitialStateHash(initSnapshot, prevStateHash)
    txs.zip(txSnapshots).foldLeft(Result(initSnapshot, 0L, 0L, MiningConstraint.Unlimited, initSnapshot, initStateHash)) {
      case (Result(currSnapshot, carryFee, currTotalFee, currConstraint, keyBlockSnapshot, prevStateHash), (tx, (txSnapshot, txStatus))) =>
        val currBlockchain = SnapshotBlockchain(blockchain, currSnapshot)

        val txFeeInfo = if (txStatus == TxMeta.Status.Elided) None else Some(computeTxFeeInfo(tx))
        val nti       = NewTransactionInfo.create(tx, txStatus, txSnapshot, currBlockchain)

        Result(
          currSnapshot |+| txSnapshot.withTransaction(nti),
          carryFee + txFeeInfo.map(_.carry).getOrElse(0L),
          currTotalFee + txFeeInfo.map(_.wavesFee).getOrElse(0L),
          currConstraint,
          keyBlockSnapshot.withTransaction(nti),
          TxStateSnapshotHashBuilder.createHashFromSnapshot(txSnapshot, Some(TxStatusInfo(tx.id(), txStatus))).createHash(prevStateHash)
        )
    }
  }

  private def computeTxFeeInfo(tx: Transaction): TxFeeInfo = {
    val hasNg                 = true
    val hasSponsorship        = false
    val (feeAsset, feeAmount) = tx.assetFee
    val currentBlockFee       = CurrentBlockFeePart(feeAmount)

    // carry is 60% of waves fees the next miner will get. obviously carry fee only makes sense when both
    // NG and sponsorship is active. also if sponsorship is active, feeAsset can only be Waves
    val carry    = if (hasNg && hasSponsorship) feeAmount - currentBlockFee else 0
    val wavesFee = if (feeAsset == Waves) feeAmount else 0L

    TxFeeInfo(feeAsset, feeAmount, carry, wavesFee)
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
