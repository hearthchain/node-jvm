package tech.hearth.consensus

import com.typesafe.config.ConfigFactory
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.block.Block
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.bls.BlsKeyPair
import tech.hearth.database.{RDB, TestStorageFactory}
import tech.hearth.db.{DBCacheSettings, WithState}
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.lagonaki.mocks.TestBlock
import tech.hearth.settings.{HearthSettings, *}
import tech.hearth.state.*
import tech.hearth.state.diffs.ENOUGH_AMT
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.BlockchainUpdater
import tech.hearth.utils.{SystemTime, Time}
import tech.hearth.{TestHelpers, WithNewDBForEachTest, crypto}
import org.scalacheck.{Arbitrary, Gen}
import tech.hearth.crypto.{Crypto, Ecvrf, SigningKey, VrfKey}

import java.nio.file.Files
import scala.concurrent.duration.*
import scala.util.Random
import scala.util.chaining.*

class FPoSSelectorTest extends FreeSpec with WithNewDBForEachTest with DBCacheSettings {
  import FPoSSelectorTest.*

  // Hearth's PoSSelector consensus is VRF-only: consensusData always produces an ECVRF proof, so a pre-VRF NG (v3)
  // block can't be forged. The old Blake2b256 / NgBlockVersion row is dropped for that reason.
  private val generationSignatureMethods = Table(
    ("method", "vrf activated"),
    ("VRF", true)
  )

  "block delay" - {
    "same on the same height in different forks" in forAll(generationSignatureMethods) { case (_, vrfActivated: Boolean) =>
      withEnv(chainGen(List(ENOUGH_AMT / 2, ENOUGH_AMT / 3), 110), vrfActivated) { case Env(_, blockchain, miners, blocks) =>
        val (miner1, vrf1) = miners.head
        val (miner2, vrf2) = miners.tail.head

        val miner1Balance = blockchain.effectiveBalance(miner1.toAddress, 0)

        val fork1 = mkFork(100, miner1, vrf1, blockchain, blocks.last)
        val fork2 = mkFork(100, miner2, vrf2, blockchain, blocks.last)

        val fork1Delay = {
          val blockForHit =
            fork1
              .lift(100)
              .orElse(
                blockchain
                  .blockHeader(blockchain.height + fork1.length - 100)
                  .map((_, blockchain.hitSource(blockchain.height + fork1.length - 100).get))
              )
              .getOrElse(fork1.head)

          val gs =
            if (vrfActivated) blockForHit._2.arr
            else PoSCalculator.generationSignature(blockForHit._2, PublicKey(miner1.publicKey))
          calcDelay(gs, fork1.head._1.header.baseTarget, miner1Balance)
        }

        val fork2Delay = {
          val blockForHit =
            fork2
              .lift(100)
              .orElse(
                blockchain
                  .blockHeader(blockchain.height + fork2.length - 100)
                  .map((_, blockchain.hitSource(blockchain.height + fork2.length - 100).get))
              )
              .getOrElse(fork2.head)

          val gs =
            if (vrfActivated) blockForHit._2.arr
            else PoSCalculator.generationSignature(blockForHit._2, PublicKey(miner1.publicKey))
          calcDelay(gs, fork2.head._1.header.baseTarget, miner1Balance)
        }

        fork1Delay shouldEqual fork2Delay
      }
    }
  }

  "block delay validation" - {
    "succeed when delay is correct" in forAll(generationSignatureMethods) { case (_, vrfActivated: Boolean) =>
      withEnv(chainGen(List(ENOUGH_AMT), 10), vrfActivated) { case Env(pos, blockchain, miners, _) =>
        val (miner, vrf) = miners.head
        val height       = blockchain.height
        val minerBalance = blockchain.effectiveBalance(miner.toAddress, 0)
        val lastBlock    = blockchain.lastBlockHeader.get
        val block        = forgeBlock(miner, vrf, blockchain, pos)()

        pos.validateBlockDelay(height, block.header, lastBlock.header, minerBalance) should beRight
      }
    }

    "failed when delay less than expected" in forAll(generationSignatureMethods) { case (_, vrfActivated: Boolean) =>
      withEnv(chainGen(List(ENOUGH_AMT), 10), vrfActivated) { case Env(pos, blockchain, miners, _) =>
        val (miner, vrf) = miners.head
        val height       = blockchain.height
        val minerBalance = blockchain.effectiveBalance(miner.toAddress, 0)
        val lastBlock    = blockchain.lastBlockHeader.get
        val block        = forgeBlock(miner, vrf, blockchain, pos)(updateDelay = _ - 1)

        pos
          .validateBlockDelay(
            height,
            block.header,
            lastBlock.header,
            minerBalance
          ) should produce("less than min valid timestamp")
      }
    }
  }

  "base target validation" - {
    "succeed when BT is correct 1" in forAll(generationSignatureMethods) { case (_, vrfActivated: Boolean) =>
      withEnv(chainGen(List(ENOUGH_AMT), 10), vrfActivated) { case Env(pos, blockchain, miners, _) =>
        val (miner, vrf) = miners.head
        val height       = blockchain.height
        val lastBlock    = blockchain.lastBlockHeader.get
        val block        = forgeBlock(miner, vrf, blockchain, pos)()

        pos
          .validateBaseTarget(
            height + 1,
            block,
            lastBlock.header,
            blockchain.blockHeader(height - 2).map(_.header)
          ) shouldBe Right(())
      }
    }

    "failed when BT less than expected" in forAll(generationSignatureMethods) { case (_, vrfActivated: Boolean) =>
      withEnv(chainGen(List(ENOUGH_AMT), 10), vrfActivated) { case Env(pos, blockchain, miners, _) =>
        val (miner, vrf) = miners.head
        val height       = blockchain.height
        val lastBlock    = blockchain.lastBlockHeader.get.header
        val block        = forgeBlock(miner, vrf, blockchain, pos)(updateBT = _ - 1)

        pos
          .validateBaseTarget(
            height + 1,
            block,
            lastBlock,
            blockchain.blockHeader(height - 2).map(_.header)
          ) should produce("does not match calculated baseTarget")
      }
    }

    "failed when BT greater than expected" in forAll(generationSignatureMethods) { case (_, vrfActivated: Boolean) =>
      withEnv(chainGen(List(ENOUGH_AMT), 10), vrfActivated) { case Env(pos, blockchain, miners, _) =>
        val (miner, vrf) = miners.head
        val height       = blockchain.height
        val lastBlock    = blockchain.lastBlockHeader.get
        val block        = forgeBlock(miner, vrf, blockchain, pos)(updateBT = _ + 1)

        pos
          .validateBaseTarget(
            height + 1,
            block,
            lastBlock.header,
            blockchain.blockHeader(height - 2).map(_.header)
          ) should produce("does not match calculated baseTarget")
      }
    }
  }

  "generation signature validation" - {
    "succeed when GS is correct" in forAll(generationSignatureMethods) { case (_, vrfActivated: Boolean) =>
      withEnv(chainGen(List(ENOUGH_AMT), 10), vrfActivated) { case Env(pos, blockchain, miners, _) =>
        val (miner, vrf) = miners.head
        val block        = forgeBlock(miner, vrf, blockchain, pos)()

        pos
          .validateGenerationSignature(block)
          .isRight shouldBe true
      }
    }

    "failed when GS is incorrect" in forAll(generationSignatureMethods) { case (_, vrfActivated: Boolean) =>
      withEnv(chainGen(List(ENOUGH_AMT), 100), vrfActivated) { case Env(pos, blockchain, miners, _) =>
        val (miner, vrf) = miners.head
        val block        = forgeBlock(miner, vrf, blockchain, pos)(updateGS = gs => ByteStr(gs.arr.tap(Random.nextBytes)))

        pos
          .validateGenerationSignature(
            block
          ) should (if (!vrfActivated) produce("Generation signatures does not match") else produce("Invalid VRF proof"))
      }
    }
  }

  "old calculator" - {
    "delay" in {
      FairPoSCalculator.V1.calculateDelay(BigInt(1), 100L, 10000000000000L) shouldBe 705491
      FairPoSCalculator.V1.calculateDelay(BigInt(2), 200L, 20000000000000L) shouldBe 607358
      FairPoSCalculator.V1.calculateDelay(BigInt(3), 300L, 30000000000000L) shouldBe 549956
    }

    "base target" in {
      FairPoSCalculator.V1.calculateBaseTarget(100L, 30, 100L, 100000000000L, Some(99000L), 100000L) shouldBe 99L
      FairPoSCalculator.V1.calculateBaseTarget(100L, 10, 100L, 100000000000L, None, 100000000000L) shouldBe 100L
      FairPoSCalculator.V1.calculateBaseTarget(100L, 10, 100L, 100000000000L, Some(99999700000L), 100000000000L) shouldBe 100L
      FairPoSCalculator.V1.calculateBaseTarget(100L, 30, 100L, 100000000000L, Some(1L), 1000000L) shouldBe 101L
    }
  }

  "new calculator" - {
    "delay" in {
      FairPoSCalculator.V2.calculateDelay(BigInt(1), 100L, 10000000000000L) shouldBe 715491
      FairPoSCalculator.V2.calculateDelay(BigInt(2), 200L, 20000000000000L) shouldBe 617358
      FairPoSCalculator.V2.calculateDelay(BigInt(3), 300L, 30000000000000L) shouldBe 559956
    }

    "base target" in {
      FairPoSCalculator.V2.calculateBaseTarget(100L, 30, 100L, 100000000000L, Some(99000L), 100000L) shouldBe 99L
      FairPoSCalculator.V2.calculateBaseTarget(100L, 10, 100L, 100000000000L, None, 100000000000L) shouldBe 100L
      FairPoSCalculator.V2.calculateBaseTarget(100L, 10, 100L, 100000000000L, Some(99999700000L), 100000000000L) shouldBe 100L
      FairPoSCalculator.V2.calculateBaseTarget(100L, 30, 100L, 100000000000L, Some(1L), 1000000L) shouldBe 101L
    }
  }

  "PoSSelector should verify generation signature for new blocks which reference non-last block correctly" in {
    Seq(1, 100).foreach { blockCount =>
      withEnv(chainGen(List(ENOUGH_AMT, ENOUGH_AMT), blockCount), VRFActivated = true) { case Env(pos, blockchain, miners, _) =>
        val (currentMiner, cvrf) = miners.head
        val (anotherMiner, avrf) = miners(1)

        val blockToApply = forgeBlock(currentMiner, cvrf, blockchain, pos)()
        val anotherBlock = forgeBlock(anotherMiner, avrf, blockchain, pos)()

        blockToApply.header.reference shouldBe anotherBlock.header.reference

        blockchain.processBlock(
          blockToApply,
          crypto
            // The generation signature is verified against the miner's VRF key, not its signing key
            .verifyVRF(blockToApply.header.generationSignature, blockchain.hitSource(blockCount + 1).get.arr, ByteStr(cvrf.publicKey()))
            .explicitGet(),
          snapshot = None,
          generatorSet = Seq.empty
        ) should beRight

        blockchain.lastBlockId shouldBe Some(blockToApply.id())

        pos.validateGenerationSignature(anotherBlock) should beRight
      }
    }

    // Consensus is VRF-only, so this second case forges v5 blocks like the first, just at height 2
    withEnv(chainGen(List(ENOUGH_AMT, ENOUGH_AMT), 1), VRFActivated = true) { case Env(pos, blockchain, miners, _) =>
      val (currentMiner, cvrf) = miners.head
      val (anotherMiner, avrf) = miners(1)

      val blockToApply = forgeBlock(currentMiner, cvrf, blockchain, pos)()
      val anotherBlock = forgeBlock(anotherMiner, avrf, blockchain, pos)()

      blockToApply.header.reference shouldBe anotherBlock.header.reference

      blockchain.processBlock(
        blockToApply,
        pos.validateGenerationSignature(blockToApply).explicitGet(),
        snapshot = None,
        generatorSet = Seq.empty
      ) should beRight

      blockchain.lastBlockId shouldBe Some(blockToApply.id())

      pos.validateGenerationSignature(anotherBlock) should beRight
    }
  }

  def withEnv(
      gen: Time => Gen[(Seq[(SigningKey, VrfKey)], Map[Address, Long], Seq[GenesisGeneratorSettings], Seq[Block])],
      VRFActivated: Boolean = false
  )(f: Env => Unit): Unit = {
    // we are not using the db instance from WithDB trait as it should be recreated between property checks
    val path = Files.createTempDirectory("lvl").toAbsolutePath
    val rdb  = RDB.open(dbSettings.copy(directory = path.toAbsolutePath.toString))

    // Sampled before the state is created: the accounts and generators are part of the genesis snapshot, which is
    // built from the settings the state is created with
    val (accounts, genesisBalances, genesisGenerators, blocks) = gen(ntpTime).sample.get

    // The generators carry VRF keys of their own rather than the ones TxHelpers derives, so the genesis settings are
    // built directly instead of going through HearthSettings.withGenesisGenerators
    val writerSettings = {
      val base = TestSettings.Default.withFunctionalitySettings(
        TestFunctionalitySettings.Stub
      )
      base.copy(blockchainSettings =
        base.blockchainSettings.copy(predefinedSnapshots =
          Seq(
            PredefinedSnapshotSettings(
              GenesisBlockHeight.toInt,
              balances = TestHelpers.genesisBalances(genesisBalances),
              generators = if (VRFActivated) genesisGenerators else Seq.empty
            )
          )
        )
      )
    }
    val defaultWriter = TestStorageFactory(writerSettings, rdb, SystemTime, BlockchainUpdateTriggers.noop)._2
    val settings0     = HearthSettings.fromRootConfig(loadConfig(ConfigFactory.load()))
    // The updater builds the genesis snapshot from its own settings, so it has to see the same genesis as the writer -
    // otherwise it applies the packaged config's balances, whose base58 addresses no longer parse.
    val settings = settings0.copy(
      autoShutdownOnUnsupportedFeature = false,
      blockchainSettings = writerSettings.blockchainSettings
    )
    val bcu =
      new BlockchainUpdaterImpl(defaultWriter, settings, ntpTime, ignoreBlockchainUpdateTriggers)
    val pos = PoSSelector(bcu, settings.synchronizationSettings.maxBaseTarget)
    try {
      // The generator can only produce block templates: a state hash has to be computed against the state the block is
      // applied to, and carrying one changes the block's id, so each block also has to be retargeted onto the previous
      // rebuilt one. blockOnTopOf does both, against the chain as it grows here.
      val appendedBlocks = blocks.map { template =>
        val block = WithState.blockOnTopOf(template, TestBlock.defaultSigner, bcu).resultE.explicitGet()
        bcu.processBlock(
          block,
          block.header.generationSignature.take(Block.HitSourceLength),
          snapshot = None,
          generatorSet = Seq.empty
        ) should beRight
        block
      }

      f(Env(pos, bcu, accounts, appendedBlocks))
      bcu.shutdown()
    } finally {
      bcu.shutdown()
      defaultWriter.close()
      rdb.close()
      TestHelpers.deleteRecursively(path)
    }
  }
}

object FPoSSelectorTest {

  // noinspection ScalaStyle
  implicit class KComb[A](a: A) {
    def |<(f: A => Unit): A = {
      f(a)
      a
    }
  }

  final case class Env(pos: PoSSelector, blockchain: Blockchain & BlockchainUpdater, miners: Seq[(SigningKey, VrfKey)], blocks: Seq[Block])

  def produce(errorMessage: String): ProduceError = new ProduceError(errorMessage)

  def mkFork(
      blockCount: Int,
      miner: SigningKey,
      vrfKey: VrfKey,
      blockchain: Blockchain & BlockchainUpdater,
      lastBlock: Block
  ): List[(Block, ByteStr)] = {
    val height = blockchain.height

    val lastBlockHitSource = blockchain.hitSource(height).get

    ((1 to blockCount) foldLeft List((lastBlock, lastBlockHitSource))) { (forkChain, ind) =>
      val blockForHit =
        forkChain
          .lift(100)
          .orElse(blockchain.blockHeader(height + ind - 100).map((_, blockchain.hitSource(height + ind - 100).get)))
          .getOrElse(forkChain.head)

      val (gs, hitSource) = {
        val result = Ecvrf.prove(vrfKey, blockForHit._2.arr)
        (result.proof().bytes(), result.beta())
      }

      val delay: Long = 60000

      val bt = FairPoSCalculator.V2.calculateBaseTarget(
        60,
        height + ind - 1,
        forkChain.head._1.header.baseTarget,
        forkChain.head._1.header.timestamp,
        (forkChain.lift(2).map(_._1.header) orElse blockchain.blockHeader(height + ind - 3).map(_.header)) map (_.timestamp),
        forkChain.head._1.header.timestamp + delay
      )

      // No state hash: this fork is only ever built in memory - the caller reads base targets and timestamps off it to
      // compare delays, and never appends it. Computing one is impossible anyway, since from the second block on the
      // reference is a fork block that the blockchain has never seen (BlockDiffer requires the block to sit on its head).
      val newBlock = Block
        .buildAndSign(
          forkChain.head._1.header.timestamp + delay,
          forkChain.head._1.id(),
          bt,
          ByteStr(gs),
          txs = Nil,
          miner,
          featureVotes = Seq.empty,
          stateHash = None,
          challengedHeader = None,
          finalizationVoting = None
        )
        .explicitGet()

      (newBlock, ByteStr(hitSource)) :: forkChain
    }
  }

  def forgeBlock(
      miner: SigningKey,
      vrfKey: VrfKey,
      blockchain: Blockchain & BlockchainUpdater,
      pos: PoSSelector
  )(
      updateDelay: Long => Long = identity,
      updateBT: Long => Long = identity,
      updateGS: ByteStr => ByteStr = identity
  ): Block = {
    val height          = blockchain.height
    val lastBlockHeader = blockchain.lastBlockHeader.get
    val ggParentTS      = blockchain.blockHeader(height - 2).map(_.header.timestamp)
    val minerBalance    = blockchain.effectiveBalance(miner.toAddress, 0)
    val delay = updateDelay(
      pos
        .getValidBlockDelay(
          height,
          vrfKey,
          lastBlockHeader.header.baseTarget,
          minerBalance
        )
        .explicitGet()
    )

    val cData = pos
      .consensusData(
        vrfKey,
        height,
        60.seconds,
        lastBlockHeader.header.baseTarget,
        lastBlockHeader.header.timestamp,
        ggParentTS,
        lastBlockHeader.header.timestamp + delay
      )
      .explicitGet()

    WithState
      .blockWithComputedStateHash(
        Block
          .buildAndSign(
            lastBlockHeader.header.timestamp + delay,
            lastBlockHeader.id(),
            updateBT(cData.baseTarget),
            updateGS(cData.generationSignature),
            txs = Nil,
            miner,
            featureVotes = Seq.empty,
            stateHash = None,
            challengedHeader = None,
            finalizationVoting = None
          )
          .explicitGet(),
        miner,
        blockchain
      )
      .resultE
      .explicitGet()
  }

  val accountGen: Gen[(SigningKey, VrfKey)] =
    Gen
      .containerOfN[Array, Byte](32, Arbitrary.arbitrary[Byte])
      .map(seed => SigningKey.fromSeed(seed) -> VrfKey.fromSeed(seed))

  // A distinct BLS endorser key per miner, derived from its signing key so it is deterministic and non-zero
  private def endorserKeyOf(signer: SigningKey): BlsKeyPair = BlsKeyPair.fromSeed(Crypto.defaultBackend().sha256(signer.publicKey()))

  /** The accounts are credited by the genesis snapshot, which the block at height 1 carries, so that block is empty
    * where it used to hold the genesis transactions. Each miner is also registered as a genesis generator, so that its
    * VRF public key can be looked up when its blocks' generation signatures are verified.
    */
  def chainGen(balances: List[Long], blockCount: Int)(
      t: Time
  ): Gen[(Seq[(SigningKey, VrfKey)], Map[Address, Long], Seq[GenesisGeneratorSettings], Seq[Block])] = {
    val ts = t.correctedTime()

    Gen
      .listOfN(balances.length, accountGen)
      .map(_ zip balances)
      .map { accountsWithBalances =>
        val genesisBalances = accountsWithBalances.map { case ((acc, _), balance) => acc.toAddress -> balance }.toMap
        val genesisGenerators = accountsWithBalances.map { case ((signer, vrfKey), _) =>
          GenesisGeneratorSettings(
            ByteStr(signer.publicKey()).toString,
            endorserKeyOf(signer).publicKey.base16,
            ByteStr(vrfKey.publicKey()).toString
          )
        }
        val lastTxTimestamp = ts + accountsWithBalances.size

        val genesisBlock = TestBlock.create(lastTxTimestamp + 1, Seq.empty).block

        val chain = (1 to blockCount foldLeft List(genesisBlock)) { (blocks, d) =>
          val newBlock = TestBlock
            .create(
              lastTxTimestamp + 1 + d,
              blocks.head.id(),
              Seq.empty
            )
            .block
          newBlock :: blocks
        }

        (accountsWithBalances.map(_._1), genesisBalances, genesisGenerators, chain.reverse)
      }
  }

  def calcDelay(gs: Array[Byte], prevBT: Long, effBalance: Long): Long = {
    val hit = PoSCalculator.hit(gs)
    FairPoSCalculator.V2.calculateDelay(hit, prevBT, effBalance)
  }
}
