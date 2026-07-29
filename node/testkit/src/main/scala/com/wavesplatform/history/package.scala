package com.wavesplatform

import com.typesafe.config.ConfigFactory
import com.wavesplatform.block.{Block, MicroBlock}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.features.BlockchainFeature
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.*
import com.wavesplatform.transaction.Transaction
import com.wavesplatform.transaction.TxHelpers
import tech.hearth.crypto.SigningKey

package object history {
  val MaxTransactionsPerBlockDiff = 10
  val MaxBlocksInMemory           = 5
  val DefaultBaseTarget           = 1000L
  val DefaultBlockchainSettings = BlockchainSettings(
    addressSchemeCharacter = 'N',
    functionalitySettings = TestFunctionalitySettings.Enabled,
    // Genesis balances are part of the genesis snapshot built from these settings, and tests declare their own,
    // so start from an empty genesis rather than TESTNET's.
    // The timestamp starts at 0 because the generators driving these tests produce transactions near the epoch, and
    // blocks are timestamped from their transactions (see buildBlockOfTxs) - a 2016 genesis would put every one of
    // those transactions hours "in the past" relative to it.
    genesisSettings = GenesisSettings.TESTNET.copy(balances = Seq.empty, timestamp = 0L),
    rewardsSettings = RewardsSettings.TESTNET
  )

  val config   = ConfigFactory.load()
  val settings = WavesSettings.fromRootConfig(config)

  val MicroblocksActivatedAt0WavesSettings: WavesSettings = settingsWithFeatures()

  def settingsWithFeatures(features: BlockchainFeature*): WavesSettings = {
    val blockchainSettings = DefaultBlockchainSettings.copy(
      functionalitySettings = DefaultBlockchainSettings.functionalitySettings.copy(preActivatedFeatures = features.map(_.id -> 0).toMap)
    )
    settings.copy(blockchainSettings = blockchainSettings)
  }

  val DefaultWavesSettings: WavesSettings = settings.copy(
    blockchainSettings = DefaultBlockchainSettings,
    autoShutdownOnUnsupportedFeature = false
  )

  val defaultSigner          = TestValues.keyPair
  val defaultVrfKey          = TxHelpers.defaultVrfKey
  val generationSignature    = ByteStr(new Array[Byte](Block.GenerationVRFSignatureLength))
  val generationVRFSignature = ByteStr(new Array[Byte](Block.GenerationVRFSignatureLength))

  def correctGenerationSignature(): ByteStr = generationVRFSignature

  def buildBlockOfTxs(refTo: ByteStr, txs: Seq[Transaction]): Block =
    buildBlockOfTxs(refTo, txs, txs.headOption.fold(0L)(_.timestamp))

  def buildBlockOfTxs(refTo: ByteStr, txs: Seq[Transaction], timestamp: Long): Block =
    customBuildBlockOfTxs(refTo, txs, defaultSigner, timestamp)

  def customBuildBlockOfTxs(
      refTo: ByteStr,
      txs: Seq[Transaction],
      signer: SigningKey,
      timestamp: Long,
      bTarget: Long = DefaultBaseTarget
  ): Block =
    Block
      .buildAndSign(
        timestamp = timestamp,
        reference = refTo,
        baseTarget = bTarget,
        generationSignature = correctGenerationSignature(),
        txs = txs,
        signer = signer,
        featureVotes = Seq.empty,
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = None
      )
      .explicitGet()

  def customBuildMicroBlockOfTxs(
      totalRefTo: ByteStr,
      prevTotal: Block,
      txs: Seq[Transaction],
      signer: SigningKey,
      ts: Long
  ): (Block, MicroBlockWithTotalId) = {
    val newTotalBlock = customBuildBlockOfTxs(totalRefTo, prevTotal.transactionData ++ txs, signer, ts)
    val nonSigned = MicroBlock
      .buildAndSign(
        generator = signer,
        transactionData = txs,
        reference = prevTotal.id(),
        wholeBlockSignature = newTotalBlock.signature,
        stateHash = newTotalBlock.header.stateHash,
        finalizationVoting = None
      )
      .explicitGet()
    (newTotalBlock, new MicroBlockWithTotalId(nonSigned, newTotalBlock.id()))
  }

  def buildMicroBlockOfTxs(totalRefTo: ByteStr, prevTotal: Block, txs: Seq[Transaction], signer: SigningKey): (Block, MicroBlockWithTotalId) = {
    val newTotalBlock = buildBlockOfTxs(totalRefTo, prevTotal.transactionData ++ txs)
    val nonSigned = MicroBlock
      .buildAndSign(
        generator = signer,
        transactionData = txs,
        reference = prevTotal.id(),
        wholeBlockSignature = newTotalBlock.signature,
        stateHash = newTotalBlock.header.stateHash,
        finalizationVoting = None
      )
      .explicitGet()
    (newTotalBlock, new MicroBlockWithTotalId(nonSigned, newTotalBlock.id()))
  }

  def randomSig: ByteStr = TestBlock.randomOfLength(Block.BlockIdLength)

  def chainBlocks(txs: Seq[Seq[Transaction]]): Seq[Block] = chainBlocksFrom(randomSig, txs)

  /** Chains blocks onto an existing block, e.g. the domain's genesis block. */
  def chainBlocksFrom(refTo: ByteStr, txs: Seq[Seq[Transaction]]): Seq[Block] = {
    def chainBlocksR(refTo: ByteStr, txs: Seq[Seq[Transaction]]): Seq[Block] = txs match {
      case (x :: xs) =>
        val block = buildBlockOfTxs(refTo, x)
        block +: chainBlocksR(block.id(), xs)
      case _ => Seq.empty
    }

    chainBlocksR(refTo, txs)
  }

  def chainBaseAndMicro(totalRefTo: ByteStr, base: Transaction, micros: Seq[Seq[Transaction]]): (Block, Seq[MicroBlockWithTotalId]) =
    chainBaseAndMicro(totalRefTo, Seq(base), micros, defaultSigner, base.timestamp)

  def chainBaseAndMicro(
      totalRefTo: ByteStr,
      base: Seq[Transaction],
      micros: Seq[Seq[Transaction]],
      signer: SigningKey,
      timestamp: Long
  ): (Block, Seq[MicroBlockWithTotalId]) = {
    val block = customBuildBlockOfTxs(totalRefTo, base, signer, timestamp)
    val microBlocks = micros
      .foldLeft((block, Seq.empty[MicroBlockWithTotalId])) { case ((lastTotal, allMicros), txs) =>
        val (newTotal, micro) = customBuildMicroBlockOfTxs(totalRefTo, lastTotal, txs, signer, timestamp)
        (newTotal, allMicros :+ micro)
      }
      ._2
    (block, microBlocks)
  }

  def spoilSignature(b: Block): Block = b.copy(signature = TestBlock.randomSignature())
}
