package tech.hearth.state

import com.google.common.primitives.Ints
import tech.hearth.account.PublicKey
import tech.hearth.block.Block
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.DigestLength
import tech.hearth.crypto.bls.BlsKeyPair
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.mining.MiningConstraint
import tech.hearth.settings.{
  BlockchainSettings,
  GenesisAssetSettings,
  GenesisBalanceSettings,
  GenesisGeneratorSettings,
  PredefinedSnapshotSettings,
  WavesSettings
}
import tech.hearth.state.diffs.BlockDiffer
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.TxHelpers.*
import tech.hearth.utils.EmptyBlockchain
import org.scalatest.EitherValues
import tech.hearth.crypto.{Crypto, SigningKey, VrfKey}

class PredefinedSnapshotSpec extends FreeSpec with WithDomain with EitherValues {
  private val assetId = ByteStr(Array.fill[Byte](32)(7))
  private val issuer  = TxHelpers.signer(1)

  // BlsKeyPair.fromSeed yields a zero key (a point at infinity) for a seed shorter than 32 bytes
  private def blsKeyPair(i: Int): BlsKeyPair = BlsKeyPair.fromSeed(Crypto.defaultBackend().sha256(Ints.toByteArray(i)))
  private def vrfKey(i: Int): VrfKey         = VrfKey.fromSeed(Crypto.defaultBackend().sha256(Ints.toByteArray(i)))

  private def generatorSettings(generator: SigningKey, blsKey: BlsKeyPair, vrf: VrfKey): GenesisGeneratorSettings =
    GenesisGeneratorSettings(ByteStr(generator.publicKey()).toString, blsKey.publicKey.base16, ByteStr(vrf.publicKey()).toString)

  private def settingsWith(
      base: WavesSettings = DeterministicFinality,
      assets: Seq[GenesisAssetSettings] = Seq.empty,
      generators: Seq[GenesisGeneratorSettings] = Seq.empty,
      balances: Seq[GenesisBalanceSettings] = Seq.empty
  ): WavesSettings =
    base.copy(blockchainSettings =
      base.blockchainSettings.copy(predefinedSnapshots =
        Seq(PredefinedSnapshotSettings(GenesisBlockHeight.toInt, assets = assets, generators = generators, balances = balances))
      )
    )

  private def assetSettings(quantity: Long, reissuable: Boolean = false): GenesisAssetSettings =
    GenesisAssetSettings(
      id = assetId,
      issuer = ByteStr(issuer.publicKey()).toString,
      name = "Genesis",
      decimals = 2,
      quantity = quantity,
      description = "Issued in the genesis block",
      reissuable = reissuable
    )

  "the genesis block" - {
    "has no transactions and applies the predefined snapshot" in
      withDomain(TransactionStateSnapshot, Seq(address(1) -> 100.waves, address(2) -> 5.waves, defaultAddress -> 200.waves)) { d =>
        d.blockchain.height shouldBe 1
        d.lastBlock.transactionData shouldBe empty
        d.blockchain.balance(address(1)) shouldBe 100.waves
        d.blockchain.balance(address(2)) shouldBe 5.waves
        d.blockchain.wavesAmount(1) shouldBe 305.waves
        d.appendBlock()
        d.blockchain.wavesAmount(2) shouldBe 305.waves + d.blockchain.settings.rewardsSettings.initial
      }

    "issues the predefined assets and credits them" in {
      val asset = IssuedAsset(assetId)
      withDomain(
        settingsWith(assets = Seq(assetSettings(quantity = 1000))),
        balances = Seq(
          AddrWithBalance(address(1), 100.waves, Map(asset -> 600L)),
          AddrWithBalance(address(2), 5.waves, Map(asset -> 400L))
        )
      ) { d =>

        val description = d.blockchain.assetDescription(asset).value

        description.totalVolume shouldBe BigInt(1000)
        description.decimals shouldBe 2
        description.reissuable shouldBe false
        description.issuer shouldBe PublicKey(issuer.publicKey())
        description.issueHeight shouldBe Height(1)

        d.blockchain.balance(address(1), asset) shouldBe 600L
        d.blockchain.balance(address(2), asset) shouldBe 400L
      }
    }

    "keeps the predefined assets usable once it is no longer the liquid block" in {
      val asset = IssuedAsset(assetId)
      withDomain(
        settingsWith(assets = Seq(assetSettings(quantity = 1000))),
        balances = Seq(
          AddrWithBalance(address(1), 100.waves, Map(asset -> 600L)),
          AddrWithBalance(address(2), 5.waves, Map(asset -> 400L))
        )
      ) { d =>
        // Reads at height 1 go through SnapshotBlockchain, which answers from the liquid genesis snapshot. Append past
        // it so the assertions below hit the persisted state instead.
        d.appendBlock()
        d.appendBlock()

        val persisted = d.rocksDBWriter.assetDescription(asset).value
        persisted.totalVolume shouldBe BigInt(1000)
        persisted.decimals shouldBe 2
        persisted.issuer shouldBe PublicKey(issuer.publicKey())
        persisted.issueHeight shouldBe Height(1)

        d.rocksDBWriter.balance(address(1), asset) shouldBe 600L
        d.rocksDBWriter.balance(address(2), asset) shouldBe 400L

        // The point of issuing it: a genesis asset has to be spendable like any other
        d.appendBlockE(TxHelpers.transfer(signer(1), address(2), 100L, asset)) should beRight
        d.blockchain.balance(address(1), asset) shouldBe 500L
        d.blockchain.balance(address(2), asset) shouldBe 500L
      }
    }

    "commits the predefined generators from the very first period" in {
      val generator = TxHelpers.signer(3)
      val blsKey    = blsKeyPair(42)
      val vrf       = vrfKey(42)

      withDomain(
        settingsWith(
          base = DeterministicFinality,
          generators = Seq(generatorSettings(generator, blsKey, vrf)),
          balances = Seq(GenesisBalanceSettings(generator.toAddress.toBech32, 100000.waves))
        )
      ) { d =>
        val period   = d.blockchain.currentGenerationPeriod.value
        val expected = CommittedGenerator(generator.toAddress, blsKey.publicKey, ByteStr(vrf.publicKey()))

        d.blockchain.committedGenerators(period) should contain theSameElementsAs Seq(expected)
      }
    }
  }

  "BlockDiffer applies the genesis snapshot" - {
    // withDomain is unusable on this branch, so drive BlockDiffer against a blockchain that is still at height 0
    def preGenesisBlockchain(ws: WavesSettings): Blockchain = new EmptyBlockchain {
      override lazy val settings: BlockchainSettings = ws.blockchainSettings
      override def height: Int                       = 0
      override def activatedFeatures: Map[Short, Height] =
        ws.blockchainSettings.functionalitySettings.preActivatedFeatures.view.mapValues(Height(_)).toMap
    }

    def genesisBlockAndResult(ws: WavesSettings): (Block, BlockDiffer.Result) = {
      val block = Block
        .genesis(
          ws.blockchainSettings
        )
        .explicitGet()
      val blockchain = preGenesisBlockchain(ws)

      block.transactionData shouldBe empty
      val r = BlockDiffer
        .fromBlock(blockchain, None, block, None, MiningConstraint.Unlimited, block.header.generationSignature)
        .explicitGet()
      (block, r)
    }

    def applyGenesis(ws: WavesSettings): BlockDiffer.Result = genesisBlockAndResult(ws)._2

    "crediting the configured balances" in {
      val ws =
        settingsWith(balances = Seq(GenesisBalanceSettings(address(1).toBech32, 100.waves), GenesisBalanceSettings(address(2).toBech32, 5.waves)))
      val snapshot = applyGenesis(ws).snapshot

      snapshot.balances.get((address(1), Waves: tech.hearth.transaction.Asset)) shouldBe Some(100.waves)
      snapshot.balances.get((address(2), Waves: tech.hearth.transaction.Asset)) shouldBe Some(5.waves)
      snapshot.transactions shouldBe empty
    }

    "issuing the configured assets" in {
      val ws = settingsWith(
        assets = Seq(assetSettings(quantity = 1000)),
        balances = Seq(GenesisBalanceSettings(address(1).toBech32, 100.waves, Map(assetId.toString -> 1000L)))
      )
      val snapshot = applyGenesis(ws).snapshot
      val asset    = IssuedAsset(assetId)

      snapshot.assetStatics.keySet shouldBe Set(asset)
      snapshot.assetVolumes(asset).volume shouldBe BigInt(1000)
      snapshot.balances.get((address(1), asset: tech.hearth.transaction.Asset)) shouldBe Some(1000L)
    }

    "committing the configured generators" in {
      val generator = TxHelpers.signer(3)
      val blsKey    = blsKeyPair(42)
      val vrf       = vrfKey(42)
      val ws = settingsWith(
        base = DeterministicFinality,
        generators = Seq(generatorSettings(generator, blsKey, vrf)),
        balances = Seq(GenesisBalanceSettings(generator.toAddress.toBech32, 100000.waves))
      )
      applyGenesis(ws).snapshot.nextCommittedGenerators shouldBe
        Seq(GenerationCommitment(PublicKey(generator.publicKey()), blsKey.publicKey, ByteStr(vrf.publicKey())))
    }

    "recomputing the state hash the genesis block carries" in {
      val ws         = settingsWith(balances = Seq(GenesisBalanceSettings(address(1).toBech32, 100.waves)))
      val (block, r) = genesisBlockAndResult(ws)

      block.header.stateHash.value shouldBe r.computedStateHash
      // The signature covers the state hash, because the genesis block is protobuf-serialized
      block.signatureValid() shouldBe true
    }
  }

  "the genesis block commitments" - {
    // Everything the genesis block puts into the state comes from these settings, so a node started with the wrong ones
    // silently builds its own chain. The commitments below are what stops it.
    val unpinnedBlockchainSettings = settingsWith(balances = Seq(GenesisBalanceSettings(address(1).toBech32, 100.waves))).blockchainSettings
    val unpinned                   = unpinnedBlockchainSettings.genesisSettings
    val genesis                    = Block.genesis(unpinnedBlockchainSettings).explicitGet()
    val pinned                     = unpinned.copy(stateHash = genesis.header.stateHash, blockId = Some(genesis.id()))
    val pinnedBlockchainSettings   = unpinnedBlockchainSettings.copy(genesisSettings = pinned)
    val snapshot                   = unpinnedBlockchainSettings.predefinedSnapshots.find(_.height == GenesisBlockHeight.toInt).value

    val otherHash = ByteStr(Array.fill[Byte](DigestLength)(1))

    "are optional" in {
      unpinned.stateHash shouldBe None
      unpinned.blockId shouldBe None
      Block.genesis(unpinnedBlockchainSettings) should beRight
    }

    "are accepted when they match the settings they were derived from" in {
      val block = Block.genesis(pinnedBlockchainSettings).explicitGet()
      block.header.stateHash shouldBe pinned.stateHash
      block.id() shouldBe pinned.blockId.value
    }

    "reject a state hash that is not the hash of the configured snapshot" in {
      Block
        .genesis(pinnedBlockchainSettings.copy(genesisSettings = pinned.copy(stateHash = Some(otherHash))))
        .left
        .value
        .toString should include(
        s"Genesis state hash mismatch: settings declare $otherHash"
      )
    }

    "reject a block id that is not the id of the configured block" in {
      Block
        .genesis(pinnedBlockchainSettings.copy(genesisSettings = pinned.copy(blockId = Some(otherHash))))
        .left
        .value
        .toString should include(
        s"Genesis block id mismatch: settings declare $otherHash"
      )
    }

    "reject a balance that was changed without updating them" in {
      val extraBalance = snapshot.copy(balances = snapshot.balances :+ GenesisBalanceSettings(address(2).toBech32, 5.waves))
      Block.genesis(pinnedBlockchainSettings.copy(predefinedSnapshots = Seq(extraBalance))).left.value.toString should include(
        "Genesis state hash mismatch"
      )
    }

    "reject a generator that was committed without updating them" in {
      val generator = TxHelpers.signer(4)
      val extraGenerator = snapshot.copy(
        generators = Seq(generatorSettings(generator, blsKeyPair(4), vrfKey(4))),
        balances = snapshot.balances :+ GenesisBalanceSettings(generator.toAddress.toBech32, 100000.waves)
      )
      Block.genesis(pinnedBlockchainSettings.copy(predefinedSnapshots = Seq(extraGenerator))).left.value.toString should include(
        "Genesis state hash mismatch"
      )
    }

    // The state hash covers the snapshot only; the header fields around it are pinned by the block id alone
    "reject a timestamp that was changed without updating them" in {
      val moved = pinnedBlockchainSettings.copy(genesisSettings = pinned.copy(timestamp = pinned.timestamp + 1))
      Block.genesis(moved).left.value.toString should include(s"Genesis block id mismatch: settings declare ${pinned.blockId.value}")
    }

    "reject a base target that was changed without updating them" in {
      val retargeted = pinnedBlockchainSettings.copy(genesisSettings = pinned.copy(initialBaseTarget = pinned.initialBaseTarget + 1))
      Block.genesis(retargeted).left.value.toString should include("Genesis block id mismatch")
    }
  }

  "the predefined snapshot is rejected when" - {
    def buildFails(settings: WavesSettings): String =
      PredefinedSnapshot
        .build(settings.blockchainSettings.predefinedSnapshots.find(_.height == GenesisBlockHeight.toInt).value, EmptyBlockchain)
        .left
        .value
        .toString

    "an asset is not fully distributed" in {
      val settings = settingsWith(
        assets = Seq(assetSettings(quantity = 1000)),
        balances = Seq(GenesisBalanceSettings(address(1).toBech32, 100.waves, Map(assetId.toString -> 600L)))
      )
      buildFails(settings) should include("does not match the distributed amount 600")
    }

    "a balance references an unknown asset" in {
      val settings = settingsWith(balances = Seq(GenesisBalanceSettings(address(1).toBech32, 100.waves, Map(assetId.toString -> 600L))))
      buildFails(settings) should include("unknown asset")
    }

    "a recipient is listed twice" in {
      val settings = settingsWith(
        balances = Seq(GenesisBalanceSettings(address(1).toBech32, 100.waves), GenesisBalanceSettings(address(1).toBech32, 5.waves))
      )
      buildFails(settings) should include("Duplicate predefined snapshot balance recipient")
    }

    "committed generator does not have enough balance" in {
      val generator = TxHelpers.signer(1005)
      val settings = settingsWith(
        generators = Seq(
          GenesisGeneratorSettings(
            Base16.encode(generator.publicKey()),
            blsKeyOf(generator).publicKey.base16,
            Base16.encode(vrfKey(1005).publicKey())
          )
        ),
        balances = Seq(GenesisBalanceSettings(generator.toAddress.toString, 5.waves))
      )

      buildFails(settings) should include("not enough funds for deposit")
    }
  }

  "a predefined snapshot beyond genesis" - {
    "mints a new asset exactly at its configured height, and rolls it back below it" in {
      val asset = IssuedAsset(assetId)
      val snapshotAtHeight3 = PredefinedSnapshotSettings(
        height = 3,
        assets = Seq(assetSettings(quantity = 1000)),
        balances = Seq(GenesisBalanceSettings(address(2).toBech32, 0, Map(assetId.toString -> 1000L)))
      )
      val settings = TransactionStateSnapshot.copy(blockchainSettings =
        TransactionStateSnapshot.blockchainSettings.copy(predefinedSnapshots =
          TransactionStateSnapshot.blockchainSettings.predefinedSnapshots :+ snapshotAtHeight3
        )
      )

      withDomain(settings, Seq(address(1) -> 1.waves)) { d =>
        d.blockchain.height shouldBe 1
        d.blockchain.assetDescription(asset) shouldBe None

        d.appendBlock() // height 2
        d.blockchain.height shouldBe 2
        d.blockchain.assetDescription(asset) shouldBe None

        d.appendBlock() // height 3
        d.blockchain.height shouldBe 3
        val description = d.blockchain.assetDescription(asset).value
        description.totalVolume shouldBe BigInt(1000)
        d.blockchain.balance(address(2), asset) shouldBe 1000L

        d.rollbackTo(2)
        d.blockchain.height shouldBe 2
        d.blockchain.assetDescription(asset) shouldBe None
        d.blockchain.balance(address(2), asset) shouldBe 0L
      }
    }

    "rejects an asset id that already exists on chain" in {
      val asset              = IssuedAsset(assetId)
      val withAssetAtGenesis = settingsWith(base = TransactionStateSnapshot, assets = Seq(assetSettings(quantity = 1000)))
      val snapshotAtHeight3 = PredefinedSnapshotSettings(
        height = 3,
        assets = Seq(assetSettings(quantity = 1000)),
        balances = Seq(GenesisBalanceSettings(address(2).toBech32, 0, Map(assetId.toString -> 1000L)))
      )
      val settings = withAssetAtGenesis.copy(blockchainSettings =
        withAssetAtGenesis.blockchainSettings.copy(predefinedSnapshots =
          withAssetAtGenesis.blockchainSettings.predefinedSnapshots :+ snapshotAtHeight3
        )
      )

      withDomain(
        settings,
        balances = Seq(AddrWithBalance(address(1), 1.waves, Map(asset -> 1000L)))
      ) { d =>
        d.appendBlock() // height 2
        d.appendBlockE() should produce("an asset with this id already exists")
      }
    }

    "rejects crediting Waves at a non-genesis height" in {
      val snapshotAtHeight3 = PredefinedSnapshotSettings(height = 3, balances = Seq(GenesisBalanceSettings(address(2).toBech32, 5.waves)))
      val settings = TransactionStateSnapshot.copy(blockchainSettings =
        TransactionStateSnapshot.blockchainSettings.copy(predefinedSnapshots =
          TransactionStateSnapshot.blockchainSettings.predefinedSnapshots :+ snapshotAtHeight3
        )
      )

      withDomain(settings, Seq(address(1) -> 1.waves)) { d =>
        d.appendBlock() // height 2
        d.appendBlockE() should produce("crediting Waves is only supported at genesis")
      }
    }

    "commits a generator at a non-genesis height who was already funded at genesis, with no balances entry of its own" in {
      val generator         = TxHelpers.signer(5)
      val blsKey            = blsKeyPair(43)
      val vrf               = vrfKey(43)
      val snapshotAtHeight3 = PredefinedSnapshotSettings(height = 3, generators = Seq(generatorSettings(generator, blsKey, vrf)))
      val settings = TransactionStateSnapshot.copy(blockchainSettings =
        TransactionStateSnapshot.blockchainSettings.copy(predefinedSnapshots =
          TransactionStateSnapshot.blockchainSettings.predefinedSnapshots :+ snapshotAtHeight3
        )
      )

      // `generator` is funded once, at genesis; the height-3 snapshot only commits it, crediting nothing further -
      // the deposit check has to resolve its real cumulative balance rather than what this snapshot alone touched.
      withDomain(settings, Seq(generator.toAddress -> 100000.waves)) { d =>
        d.appendBlock() // height 2
        d.appendBlock() // height 3
        d.blockchain.height shouldBe 3
      }
    }
  }
}
