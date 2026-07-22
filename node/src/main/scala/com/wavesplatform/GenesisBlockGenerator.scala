package com.wavesplatform

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.consensus.PoSCalculator.hit
import com.wavesplatform.consensus.{FairPoSCalculator, NxtPoSCalculator}
import com.wavesplatform.crypto.*
import com.wavesplatform.crypto.bls.BlsKeyPair
import com.wavesplatform.features.{BlockchainFeature, BlockchainFeatures}
import com.wavesplatform.settings.*
import com.wavesplatform.utils.*
import pureconfig.*
import pureconfig.generic.semiauto.deriveReader
import tech.hearth.crypto.*

import java.io.{File, FileNotFoundException}
import java.nio.file.Files
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

object GenesisBlockGenerator {

  private type Share = Long

  case class DistributionItem(seedText: String, amount: Share, nonce: Int = 0, miner: Boolean = true)

  object DistributionItem {
    // This given is required for default args to work.
    // Details: https://github.com/pureconfig/pureconfig/issues/1673
    // Note: the proposed approach with `extension` doesn't work.
    given ConfigReader[DistributionItem] = deriveReader
  }

  case class Settings(
      networkType: String,
      baseTarget: Option[Long],
      averageBlockDelay: FiniteDuration,
      timestamp: Option[Long],
      distributions: List[DistributionItem],
      preActivatedFeatures: Option[List[Int]],
      minBlockTime: Option[FiniteDuration],
      delayDelta: Option[Int]
  ) derives ConfigReader {

    val initialBalance: Share = distributions.map(_.amount).sum

    val chainId: Byte = networkType.head.toByte

    private val features: Map[Short, Int] =
      preActivatedFeatures
        .getOrElse(List(BlockchainFeatures.FairPoS.id.toInt, BlockchainFeatures.BlockV5.id.toInt))
        .map(f => f.toShort -> 0)
        .toMap

    val functionalitySettings: FunctionalitySettings = FunctionalitySettings(
      Int.MaxValue,
      Int.MaxValue,
      preActivatedFeatures = features,
      minBlockTime = minBlockTime.getOrElse(15.seconds),
      delayDelta = delayDelta.getOrElse(8)
    )

    def preActivated(feature: BlockchainFeature): Boolean = features.contains(feature.id)
  }

  case class FullAddressInfo(
      mnemonic: String,
      signingKey: SigningKey,
      vrfKey: VrfKey,
      blsKey: BlsKeyPair,
      miner: Boolean
  )

  def toFullAddressInfo(item: DistributionItem): FullAddressInfo = {
    val seedHash = Bip39.toSeed(item.seedText)

    FullAddressInfo(
      item.seedText,
      KeyTree.signingKey(seedHash, item.nonce),
      KeyTree.vrfKey(seedHash, item.nonce),
      BlsKeyPair.fromScalar(KeyTree.blsSecretKey(seedHash, item.nonce)),
      item.miner
    )
  }

  def main(args: Array[String]): Unit = {
    val inputConfFile = new File(args.headOption.getOrElse(throw new IllegalArgumentException("Specify a path to genesis.conf")))
    if (!inputConfFile.exists()) throw new FileNotFoundException(inputConfFile.getCanonicalPath)

    val outputConfFile = args
      .drop(1)
      .headOption
      .map(new File(_).getAbsoluteFile.ensuring(f => !f.isDirectory && f.getParentFile.isDirectory || f.getParentFile.mkdirs()))

    val settings = parseSettings(ConfigFactory.parseFile(inputConfFile).resolve())
    val confBody = createConfig(settings)
    outputConfFile.foreach(ocf => Files.write(ocf.toPath, confBody.utf8Bytes))
  }

  def parseSettings(config: Config): Settings = {
    ConfigSource.fromConfig(config).at("genesis-generator").loadOrThrow[Settings]
  }

  def createConfig(settings: Settings): String = {
    def generateAndReport(addrInfos: Iterable[FullAddressInfo], settings: GenesisSettings): String = {
      val output = new StringBuilder(8192)
      output.append("Addresses:\n")
      addrInfos.foreach { acc =>
        output.append(s"""
                         | Seed text:           ${acc.mnemonic}
                         | Public account key:  ${Hex.encode(acc.signingKey.publicKey())}
                         | Account address:     ${acc.signingKey.toAddress.toBech32}
                         | ===
                         |""".stripMargin)
      }

      val confBody =
        s"""genesis {
           |  average-block-delay = ${settings.averageBlockDelay.toMillis}ms
           |  initial-base-target = ${settings.initialBaseTarget}
           |  timestamp = ${settings.timestamp}
           |  block-timestamp = ${settings.blockTimestamp}
           |  signature = "${settings.signature.get}"
           |  generators = [
           |    ${settings.generators
            .map(x => s"""{public-key = "${x.publicKey}", endorser-public-key = "${x.endorserPublicKey}", vrf-public-key = "${x.vrfPublicKey}"}""")
            .mkString(",\n    ")}
           |  ]
           |  balances = [
           |    ${settings.balances.map(x => s"""{recipient = "${x.recipient}", waves = ${x.waves}}""").mkString(",\n    ")}
           |  ]
           |}
           |""".stripMargin

      output.append("Settings:\n")
      output.append(confBody)
      System.out.print(output.result())
      confBody
    }

    com.wavesplatform.account.AddressScheme.current = new AddressScheme {
      override val chainId: Byte = settings.chainId
    }

    val shares: Seq[(FullAddressInfo, Share)] = settings.distributions
      .map(x => (toFullAddressInfo(x), x.amount))
      .sortBy(_._2)
    val minerShares = shares.filter(_._1.miner)

    val timestamp = settings.timestamp.getOrElse(System.currentTimeMillis())

    val genesisBalances: Seq[GenesisBalanceSettings] = shares.map { case (addrInfo, amount) =>
      GenesisBalanceSettings(addrInfo.signingKey.toAddress.toBech32, amount)
    }

    val genesisGenerators: Seq[GenesisGeneratorSettings] = minerShares.map { case (addrInfo, _) =>
      GenesisGeneratorSettings(
        ByteStr(addrInfo.signingKey.publicKey()).toString,
        addrInfo.blsKey.publicKey.base58,
        ByteStr(addrInfo.vrfKey.publicKey()).toString
      )
    }

    def genesisSettings(predefined: Option[Long]): GenesisSettings =
      predefined
        .map(baseTarget => mkGenesisSettings(baseTarget))
        .getOrElse(mkGenesisSettings(calcInitialBaseTarget()))

    def mkGenesisSettings(baseTarget: Long): GenesisSettings = {
      val reference     = ByteStr(Array.fill(SignatureLength)(-1: Byte))
      val genesisSigner = Block.GenesisGenerator

      // The genesis block carries no transactions, so its signature covers the header only
      val genesis = Block
        .buildAndSign(
          version = 1,
          timestamp = timestamp,
          reference = reference,
          baseTarget,
          ByteStr(Array.fill(crypto.DigestLength)(0: Byte)),
          txs = Seq.empty,
          signer = genesisSigner,
          featureVotes = Seq.empty,
          rewardVote = -1L,
          stateHash = None,
          challengedHeader = None,
          finalizationVoting = None
        )
        .explicitGet()

      GenesisSettings(
        timestamp,
        Some(genesis.signature),
        genesis.header.baseTarget,
        settings.averageBlockDelay,
        generators = genesisGenerators,
        balances = genesisBalances
      )
    }

    def calcInitialBaseTarget(): Long = {
      val posCalculator =
        if (settings.preActivated(BlockchainFeatures.FairPoS))
          if (settings.preActivated(BlockchainFeatures.BlockV5)) FairPoSCalculator.fromSettings(settings.functionalitySettings)
          else FairPoSCalculator.V1
        else NxtPoSCalculator

      val hitSourceCache = TrieMap[(VrfKey, ByteStr), (BigInt, ByteStr)]()

      def getHitWithSource(account: VrfKey, hitSource: ByteStr): (BigInt, ByteStr) =
        hitSourceCache.getOrElseUpdate(
          (account, hitSource), {
            val gs = Ecvrf.prove(account, hitSource.arr).beta()

            (hit(gs), ByteStr(gs))
          }
        )

      def inverseCalculateDelay(balance: Long, hitRate: Double): Int =
        posCalculator match {
          case FairPoSCalculator(minBlockTime, _) =>
            val averageBlockDelay = settings.averageBlockDelay.toMillis
            require(
              averageBlockDelay > minBlockTime,
              s"average-block-delay: ${averageBlockDelay}ms should be > min-block-time: ${minBlockTime}ms"
            )
            val z = (1 - Math.exp((averageBlockDelay - minBlockTime) / 70000.0)) * balance
            (5e17 * (Math.log(hitRate) / z)).toInt
          case NxtPoSCalculator =>
            (FairPoSCalculator.MaxHit * hitRate / settings.averageBlockDelay.toSeconds / balance).toInt
        }

      def nextBaseTarget(baseTarget: Long, height: Int, maybeGreatGrandParentTimestamp: Option[Long], parentTimestamp: Long, timestamp: Long): Share =
        posCalculator.calculateBaseTarget(
          settings.averageBlockDelay.toSeconds,
          height,
          baseTarget,
          parentTimestamp,
          maybeGreatGrandParentTimestamp,
          timestamp
        )

      def parallelMapMin[A, B, C](seq: Seq[A], f: A => B, fMin: B => C)(implicit c: Ordering[C]): B = {
        val partSize        = (seq.size / Runtime.getRuntime.availableProcessors()).max(1)
        val parallelResults = seq.grouped(partSize).map(part => Future(part.map(f).minBy(fMin)))
        Await.result(Future.sequence(parallelResults), Duration.Inf).minBy(fMin)
      }

      def calc(hitSources: Seq[ByteStr], timestamps: Seq[Long], baseTargets: Seq[Long], height: Int, n: Int): Seq[Long] =
        if (n == 0)
          baseTargets
        else {
          val currentHitSource = if (height > 100) hitSources(100) else hitSources.head
          val (delay, newHitSource) = parallelMapMin[(FullAddressInfo, Share), (Long, ByteStr), Long](
            minerShares,
            { case (miner, balance) =>
              val (hit, newHitSource) = getHitWithSource(miner.vrfKey, currentHitSource)
              val delay1              = posCalculator.calculateDelay(hit, baseTargets.head, balance)
              (delay1, newHitSource)
            },
            _._1
          )
          val newTimestamp  = timestamps.head + delay
          val newBaseTarget = nextBaseTarget(baseTargets.head, height, timestamps.lift(2), timestamps.head, newTimestamp)
          calc(
            newHitSource +: hitSources,
            newTimestamp +: timestamps,
            newBaseTarget +: baseTargets,
            height + 1,
            n - 1
          )
        }

      val startHitSource  = ByteStr(Array.fill(crypto.DigestLength)(0: Byte))
      val startBaseTarget = inverseCalculateDelay(minerShares.map(_._2).max, 0.5)

      val totalCount       = 1000
      val significantCount = 100

      val baseTargets = calc(Seq(startHitSource), Seq(0), Seq(startBaseTarget), 1, totalCount)
      baseTargets.take(significantCount).sum / significantCount
    }

    generateAndReport(
      addrInfos = shares.map(_._1),
      settings = genesisSettings(settings.baseTarget)
    )
  }
}
