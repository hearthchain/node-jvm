package com.wavesplatform.consensus

import cats.syntax.either.*
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.consensus.nxt.NxtLikeConsensusBlockData
import com.wavesplatform.crypto
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.account.PublicKey
import com.wavesplatform.state.{Blockchain, Height}
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.utils.{ApplicationStopReason, BaseTargetReachedMaximum, ScorexLogging, forceStopApplication}
import tech.hearth.crypto.{Ecvrf, VrfKey}

import scala.concurrent.duration.FiniteDuration

/** @param onFatalStop
  *   How the node is brought down when it detects it must not keep running. Injected so tests can observe that the
  *   shutdown was triggered - it cannot be intercepted from outside any more, since JDK 25 removed SecurityManager.
  */
case class PoSSelector(
    blockchain: Blockchain,
    maxBaseTarget: Option[Long],
    onFatalStop: ApplicationStopReason => Unit = forceStopApplication
) extends ScorexLogging {
  import PoSCalculator.*
  import blockchain.settings as blockchainSettings

  protected def posCalculator(): PoSCalculator = FairPoSCalculator.fromSettings(blockchain.settings.functionalitySettings)

  def consensusData(
      vrfKey: VrfKey,
      height: Int,
      targetBlockDelay: FiniteDuration,
      refBlockBT: Long,
      refBlockTS: Long,
      greatGrandParentTS: Option[Long],
      currentTime: Long
  ): Either[ValidationError, NxtLikeConsensusBlockData] = {
    val bt = posCalculator().calculateBaseTarget(targetBlockDelay.toSeconds, height, refBlockBT, refBlockTS, greatGrandParentTS, currentTime)

    checkBaseTargetLimit(bt, height).flatMap(_ =>
      getHitSource(height)
        .map(hs => NxtLikeConsensusBlockData(bt, ByteStr(Ecvrf.prove(vrfKey, hs.arr).proof().bytes())))
    )
  }

  def getValidBlockDelay(height: Int, vrfKey: VrfKey, refBlockBT: Long, balance: Long): Either[ValidationError, Long] = {
    val pc = posCalculator()

    getHit(height, vrfKey)
      .map(pc.calculateDelay(_, refBlockBT, balance))
  }

  def validateBlockDelay(parentHeight: Int, header: BlockHeader, parent: BlockHeader, effectiveBalance: Long): Either[ValidationError, Unit] = {
    for {
      parentHitSource <- getHitSource(parentHeight)
      gs <- vrfPublicKeyOf(header.generator, Height(parentHeight))
        .flatMap(crypto.verifyVRF(header.generationSignature, parentHitSource.arr, _))
        .map(_.arr)
      ts = posCalculator().calculateDelay(hit(gs), parent.baseTarget, effectiveBalance) + parent.timestamp
      _ <- Either.cond(
        ts <= header.timestamp,
        (),
        GenericError(s"Block timestamp ${header.timestamp} less than min valid timestamp $ts")
      )
    } yield ()
  }

  def validateGenerationSignature(block: Block): Either[ValidationError, ByteStr] =
    blockchain.heightOf(block.header.reference).toRight(GenericError(s"Block reference ${block.header.reference} doesn't exist")).flatMap { height =>
      for {
        hs        <- getHitSource(height)
        vrfPK     <- vrfPublicKeyOf(block.header.generator, Height(height))
        hitSource <- crypto.verifyVRF(block.header.generationSignature, hs.arr, vrfPK)
      } yield hitSource
    }

  private def vrfPublicKeyOf(generator: PublicKey, at: Height): Either[ValidationError, ByteStr] =
    blockchain.vrfPublicKeyOf(generator, at).leftMap(GenericError(_))

  def checkBaseTargetLimit(baseTarget: Long, height: Int): Either[ValidationError, Unit] = {
    def stopNode(): ValidationError = {
      log.error(
        s"Base target reached maximum value (settings: synchronization.max-base-target=${maxBaseTarget.getOrElse(-1)}). Anti-fork protection."
      )
      log.error("FOR THIS REASON THE NODE WAS STOPPED AUTOMATICALLY")
      onFatalStop(BaseTargetReachedMaximum)
      GenericError("Base target reached maximum")
    }

    Either.cond(
      // We need to choose some moment with stable baseTarget value in case of loading blockchain from beginning.
      !fairPosActivated(height) || maxBaseTarget.forall(baseTarget < _),
      (),
      stopNode()
    )
  }

  private def calculateBaseTarget(height: Int, timestamp: Long, parent: BlockHeader, grandParent: Option[BlockHeader]): Long = {
    posCalculator().calculateBaseTarget(
      blockchainSettings.genesisSettings.averageBlockDelay.toSeconds,
      height,
      parent.baseTarget,
      parent.timestamp,
      grandParent.map(_.timestamp),
      timestamp
    )
  }

  def validateBaseTarget(height: Int, block: Block, parent: BlockHeader, grandParent: Option[BlockHeader]): Either[ValidationError, Unit] = {
    val blockBT    = block.header.baseTarget
    val expectedBT = calculateBaseTarget(height, block.header.timestamp, parent, grandParent)

    for {
      _ <- Either.cond(
        expectedBT == blockBT,
        (),
        GenericError(s"declared baseTarget $blockBT does not match calculated baseTarget $expectedBT")
      )

      _ <- checkBaseTargetLimit(blockBT, height)
    } yield ()
  }

  private def getHitSource(height: Int): Either[ValidationError, ByteStr] = {
    val hitSource = if (fairPosActivated(height) && height > 100) blockchain.hitSource(height - 100) else blockchain.hitSource(height)
    hitSource.toRight(GenericError(s"Couldn't find hit source for height: $height"))
  }

  private def getHit(height: Int, vrfKey: VrfKey): Either[ValidationError, BigInt] =
    for {
      hitSource <- getHitSource(height)
    } yield hit(Ecvrf.prove(vrfKey, hitSource.arr).beta())

  private def fairPosActivated(height: Int): Boolean = true
}
