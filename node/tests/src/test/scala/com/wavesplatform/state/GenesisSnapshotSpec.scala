package com.wavesplatform.state

import com.google.common.primitives.Ints
import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.bls.BlsKeyPair
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.mining.MiningConstraint
import com.wavesplatform.settings.{BlockchainSettings, GenesisAssetSettings, GenesisBalanceSettings, GenesisGeneratorSettings, WavesSettings}
import com.wavesplatform.state.diffs.BlockDiffer
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.test.{FreeSpec, NumericExt}
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.TxHelpers.*
import com.wavesplatform.utils.EmptyBlockchain
import org.scalatest.EitherValues
import tech.hearth.crypto.{Crypto, SigningKey, VrfKey}

class GenesisSnapshotSpec extends FreeSpec with WithDomain with EitherValues {
  // Genesis recipients are bech32m, which needs a default network. Nothing configures one yet, see the note in the PR.
  tech.hearth.crypto.Address.Network.setDefault(tech.hearth.crypto.Address.Network.TESTNET)

  private val assetId = ByteStr(Array.fill[Byte](32)(7))
  private val issuer  = TxHelpers.signer(1)

  // BlsKeyPair.fromSeed yields a zero key (a point at infinity) for a seed shorter than 32 bytes
  private def blsKeyPair(i: Int): BlsKeyPair = BlsKeyPair.fromSeed(Crypto.defaultBackend().sha256(Ints.toByteArray(i)))
  private def vrfKey(i: Int): VrfKey         = VrfKey.fromSeed(Crypto.defaultBackend().sha256(Ints.toByteArray(i)))

  private def generatorSettings(generator: SigningKey, blsKey: BlsKeyPair, vrf: VrfKey): GenesisGeneratorSettings =
    GenesisGeneratorSettings(ByteStr(generator.publicKey()).toString, blsKey.publicKey.base58, ByteStr(vrf.publicKey()).toString)

  private def settingsWith(
      base: WavesSettings = DeterministicFinality,
      assets: Seq[GenesisAssetSettings] = Seq.empty,
      generators: Seq[GenesisGeneratorSettings] = Seq.empty,
      balances: Seq[GenesisBalanceSettings] = Seq.empty
  ): WavesSettings =
    base.copy(blockchainSettings =
      base.blockchainSettings.copy(genesisSettings =
        base.blockchainSettings.genesisSettings.copy(assets = assets, generators = generators, balances = balances)
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
      withDomain(TransactionStateSnapshot, Seq(AddrWithBalance(address(1), 100.waves), AddrWithBalance(address(2), 5.waves))) { d =>
        d.blockchain.height shouldBe 1
        d.lastBlock.transactionData shouldBe empty
        d.blockchain.balance(address(1)) shouldBe 100.waves
        d.blockchain.balance(address(2)) shouldBe 5.waves
        d.blockchain.wavesAmount(1) shouldBe 105.waves
      }

    "issues the predefined assets and credits them" in
      withDomain(
        settingsWith(
          assets = Seq(assetSettings(quantity = 1000)),
          balances = Seq(
            GenesisBalanceSettings(address(1).toBech32, 100.waves, Map(assetId.toString -> 600L)),
            GenesisBalanceSettings(address(2).toBech32, 5.waves, Map(assetId.toString -> 400L))
          )
        )
      ) { d =>
        val asset       = IssuedAsset(assetId)
        val description = d.blockchain.assetDescription(asset).value

        description.totalVolume shouldBe BigInt(1000)
        description.decimals shouldBe 2
        description.reissuable shouldBe false
        description.issuer shouldBe PublicKey(issuer.publicKey())
        description.issueHeight shouldBe Height(1)

        d.blockchain.balance(address(1), asset) shouldBe 600L
        d.blockchain.balance(address(2), asset) shouldBe 400L
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
          ws.blockchainSettings.genesisSettings,
          ws.blockchainSettings.functionalitySettings,
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
      val ws = settingsWith(balances =
        Seq(GenesisBalanceSettings(address(1).toBech32, 100.waves), GenesisBalanceSettings(address(2).toBech32, 5.waves))
      )
      val snapshot = applyGenesis(ws).snapshot

      snapshot.balances.get((address(1), Waves: com.wavesplatform.transaction.Asset)) shouldBe Some(100.waves)
      snapshot.balances.get((address(2), Waves: com.wavesplatform.transaction.Asset)) shouldBe Some(5.waves)
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
      snapshot.balances.get((address(1), asset: com.wavesplatform.transaction.Asset)) shouldBe Some(1000L)
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
      val ws           = settingsWith(balances = Seq(GenesisBalanceSettings(address(1).toBech32, 100.waves)))
      val (block, r)   = genesisBlockAndResult(ws)

      block.header.stateHash.value shouldBe r.computedStateHash
      // The signature covers the state hash, because the genesis block is protobuf-serialized
      block.signatureValid() shouldBe true
    }
  }

  "the genesis snapshot is rejected when" - {
    def buildFails(settings: WavesSettings): String =
      GenesisSnapshot
        .build(settings.blockchainSettings.genesisSettings, settings.blockchainSettings.functionalitySettings)
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
      buildFails(settings) should include("Duplicate genesis balance recipient")
    }
    
    "committed generator does not have enough balance" in {
      val generator = TxHelpers.signer(1005)
      val settings = settingsWith(
        generators = Seq(GenesisGeneratorSettings(Base58.encode(generator.publicKey()), blsKeyOf(generator).publicKey.base58, Base58.encode(vrfKey(1005).publicKey()))),
        balances = Seq(GenesisBalanceSettings(generator.toAddress.toString, 5.waves))
      )
      
      buildFails(settings) should include("not enough funds for deposit")
    }
  }
}
