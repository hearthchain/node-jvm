package tech.hearth.settings

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.typesafe.config.Config
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.state.{EmissionCurve, GenesisBlockHeight, Height}
import pureconfig.*
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.*

/** HRTH's block reward decay curve (hearth-tokenomics-spec S2): `R(h) = initialReward * 2^(-h/halfLifeBlocks)`,
  * `h` counting blocks since the first rewarded height. `decayRatioFixed` is `2^(-1/halfLifeBlocks)` pre-derived
  * offline, once, at arbitrary precision, and pinned as a `Q(EmissionCurve.FixedPointBits)` fixed-point integer -
  * exactly like a network's genesis `stateHash`/`blockId` are pinned rather than recomputed (see
  * [[GenesisSettings]]). This is what makes the curve reproducible bit-for-bit by any client implementation, not
  * only this one: nobody derives an irrational root or calls a transcendental function at runtime, every
  * implementation only ever does fixed-point integer multiply-and-shift against the same literal. `initialReward`
  * (R0) is likewise pre-derived (`cEmit * ln(2) / halfLifeBlocks`, floored to the nearest ember) rather than
  * computed at startup. `halfLifeBlocks` itself is not consensus-relevant - it is carried only for display
  * (`RewardApiRoute`) and as documentation of how the two derived constants above were produced.
  */
case class RewardsSettings(
    cEmit: Long,
    initialReward: Long,
    decayRatioFixed: BigInt,
    halfLifeBlocks: Long
) derives ConfigReader {
  require(cEmit > 0, "c-emit must be greater than 0")
  require(initialReward >= 0, "initial-reward must be greater than or equal to 0")
  require(decayRatioFixed > 0, "decay-ratio-fixed must be greater than 0")
  // The hard cap holds by construction only because every block's reward is <= the previous one (see EmissionCurve):
  // a ratio > 1.0 would make the reward grow over time, silently breaking that invariant for a misconfigured custom
  // network. Exactly 1.0 (flat, no decay) is allowed - node/testkit's DefaultRewardsSettings/withFlatReward rely on it.
  require(
    decayRatioFixed <= (BigInt(1) << EmissionCurve.FixedPointBits),
    "decay-ratio-fixed must represent a ratio <= 1 (2^FixedPointBits)"
  )
  require(halfLifeBlocks > 0, "half-life-blocks must be greater than 0")
}

object RewardsSettings {
  // 10-year half-life, 60s blocks (525,600 blocks/year): halfLifeBlocks = 5,256,000.
  val MAINNET: RewardsSettings = apply(
    cEmit = 95_000_000L * Constants.UnitsInHearth,
    initialReward = 1252834515L,
    decayRatioFixed = BigInt("340282322045415694657836056900309514630"),
    halfLifeBlocks = 5_256_000L
  )

  // Same cEmit as MAINNET, but a short half-life so the decay curve is actually observable on a running chain
  // instead of only in unit tests. Not economically meaningful, purely for testing observability.
  val TESTNET: RewardsSettings = apply(
    cEmit = 95_000_000L * Constants.UnitsInHearth,
    initialReward = 4572845982860L,
    decayRatioFixed = BigInt("340118610667410880413344550167336787510"),
    halfLifeBlocks = 1_440L
  )

  val STAGENET: RewardsSettings = apply(
    cEmit = 95_000_000L * Constants.UnitsInHearth,
    initialReward = 65848982153194L,
    decayRatioFixed = BigInt("337931864918735857425456001828432707560"),
    halfLifeBlocks = 100L
  )
}

case class FunctionalitySettings(
    featureCheckBlocksPeriod: Int = 1000,
    blocksForFeatureActivation: Int = 800,
    preActivatedFeatures: Map[Short, Int] = Map.empty,
    maxTransactionTimeBackOffset: FiniteDuration = 120.minutes,
    maxTransactionTimeForwardOffset: FiniteDuration = 90.minutes,
    minBlockTime: FiniteDuration = 15.seconds,
    delayDelta: Int = 8,
    daoAddress: Option[String] = None,
    blockRewardBoostPeriod: Int = 1000,
    maxValidEndorsers: Int = 5,
    generationPeriodLength: Int = 1000
) {
  lazy val daoAddressParsed: Either[String, Option[Address]] =
    daoAddress.traverse(Address.fromString(_)).leftMap(_ => "Incorrect dao-address")

  require(featureCheckBlocksPeriod > 0, "feature-check-blocks-period must be greater than 0")
  require(
    (blocksForFeatureActivation > 0) && (blocksForFeatureActivation <= featureCheckBlocksPeriod),
    s"blocks-for-feature-activation must be in range 1 to $featureCheckBlocksPeriod"
  )
  require(generationPeriodLength > 0, "generation-period-length must be greater than 0")

  def activationWindowSize(height: Int): Int = featureCheckBlocksPeriod

  def activationWindow(height: Int): Range =
    if (height < 1) Range(0, 0)
    else {
      val ws = activationWindowSize(height)
      Range.inclusive((height - 1) / ws * ws + 1, ((height - 1) / ws + 1) * ws)
    }

  def generatingBalanceDepth(height: Int): Int = 1000
}

object FunctionalitySettings {
  // This given is required for default args to work.
  // Details: https://github.com/pureconfig/pureconfig/issues/1673
  // Note: the proposed approach with `extension` doesn't work.
  given ConfigReader[FunctionalitySettings] = deriveReader

  val MAINNET: FunctionalitySettings = apply(
    featureCheckBlocksPeriod = 5000,
    blocksForFeatureActivation = 4000,
    // TODO temporary stub, replace with the real hearth DAO address before launch
    daoAddress = Some("hrth1nw24ly6qrzatspdzy72t5lhpgcklw7ehuhszwk"),
    blockRewardBoostPeriod = 300_000,
    maxValidEndorsers = 128, // BLS has much worse performance from 129
    generationPeriodLength = 10_000
  )

  val TESTNET: FunctionalitySettings = apply(
    featureCheckBlocksPeriod = 3000,
    blocksForFeatureActivation = 2700,
    // TODO temporary stub, replace with the real hearth DAO address before launch
    daoAddress = Some("thrth1nw24ly6qrzatspdzy72t5lhpgcklw7ehcqpjhn"),
    blockRewardBoostPeriod = 2_000,
    maxValidEndorsers = 64,
    generationPeriodLength = 3000
  )

  val STAGENET: FunctionalitySettings = apply(
    featureCheckBlocksPeriod = 100,
    blocksForFeatureActivation = 40,
    preActivatedFeatures = (1 to 13).map(_.toShort -> 0).toMap,
    // TODO temporary stub, replace with the real hearth DAO address before launch
    daoAddress = Some("shrth1nw24ly6qrzatspdzy72t5lhpgcklw7ehgwv5cg"),
    maxValidEndorsers = 32,
    generationPeriodLength = 1000
  )
}

/** An asset issued by a predefined snapshot. Since there is no issue transaction to derive it from, the id is
  * specified explicitly. `minFee` is mandatory: every issued asset must carry a non-zero minimum fee floor for
  * paying transaction fees in it (see MinAssetFee), there is no "sponsorship disabled" state any more. There is no
  * issuer either: nothing ever checks who issued an asset any more (no Reissue/Burn/SponsorFee to gate by it), so
  * it isn't tracked.
  */
case class GenesisAssetSettings(
    id: ByteStr,
    name: String,
    decimals: Int,
    quantity: Long,
    minFee: Long,
    description: String = ""
)

object GenesisAssetSettings {
  // This given is required for default args to work, see FunctionalitySettings.
  given ConfigReader[GenesisAssetSettings] = deriveReader
}

/** Changes an already-issued asset's minAssetFee at this predefined snapshot's height, without re-issuing it. */
case class MinAssetFeeSettings(assetId: ByteStr, minFee: Long) derives ConfigReader

/** An account committed to generating blocks starting from the generation period of the predefined snapshot's height.
  *
  * @param endorserPublicKey
  *   The BLS key this generator's endorsements are verified with
  * @param vrfPublicKey
  *   The VRF key this generator's block generation signatures are verified against
  */
case class GenesisGeneratorSettings(publicKey: String, endorserPublicKey: String, vrfPublicKey: String) derives ConfigReader

/** Balances credited by a predefined snapshot. Every asset referenced here must be listed in the same [[PredefinedSnapshotSettings.assets]]. */
case class GenesisBalanceSettings(recipient: String, hearth: Long = 0L, assets: Map[String, Long] = Map.empty)

object GenesisBalanceSettings {
  // This given is required for default args to work, see FunctionalitySettings.
  given ConfigReader[GenesisBalanceSettings] = deriveReader
}

/** A chunk of state applied outside of transaction processing, before the block at [[height]] applies its own
  * transactions. Since there is no issue transaction any more, this is the only way to mint a new asset; it can also
  * credit asset balances and commit generators. Only the height-1 (genesis) entry may credit Hearth - Hearth supply
  * growth beyond genesis is tracked as block rewards only. Height-keyed and applied unconditionally for every block
  * at that height - not tied to feature activation in code, though a network's config typically lines a snapshot's
  * height up with a feature activation height as a matter of convention. The height-1 entry is the genesis snapshot.
  */
case class PredefinedSnapshotSettings(
    height: Int,
    assets: Seq[GenesisAssetSettings] = Seq.empty,
    generators: Seq[GenesisGeneratorSettings] = Seq.empty,
    balances: Seq[GenesisBalanceSettings] = Seq.empty,
    minAssetFees: Seq[MinAssetFeeSettings] = Seq.empty
)

object PredefinedSnapshotSettings {
  // This given is required for default args to work, see FunctionalitySettings.
  given ConfigReader[PredefinedSnapshotSettings] = deriveReader

  // Genesis premine is 5% of the hard cap (hearth-tokenomics-spec S2.1: Cmax = Pgen + Cemit, Pgen = 0.05 Cmax); the
  // other 95% (RewardsSettings.MAINNET.cEmit) is earned by forging, not distributed at genesis. Illustrative split
  // of the 5%: fallen-chains burn claim 3%, DAO treasury 1%, team (vested) 1% - the DAO address is the same one
  // FunctionalitySettings.MAINNET commits to for consensus (daoAddress), the burn-claim/team addresses are
  // placeholders in the same vein (TODO: replace both with the real addresses before launch).
  val MAINNET: Seq[PredefinedSnapshotSettings] = Seq(
    PredefinedSnapshotSettings(
      height = GenesisBlockHeight.toInt,
      balances = List(
        GenesisBalanceSettings("hrth1h3s3jrkjgd3f3c705tpczxxmrkxehg6v74gye7", 3_000_000L * Constants.UnitsInHearth), // burn-claim, 3%
        GenesisBalanceSettings("hrth1nw24ly6qrzatspdzy72t5lhpgcklw7ehuhszwk", 1_000_000L * Constants.UnitsInHearth), // DAO treasury, 1%
        GenesisBalanceSettings("hrth1e5ecq68dxwl7r5gdslt23u5c0c875fjc5f9qu7", 1_000_000L * Constants.UnitsInHearth)  // team (vested), 1%
      )
    )
  )

  // Same 5%/95% split as MAINNET (see above); short half-life instead (RewardsSettings.TESTNET) so the decay curve
  // is actually observable on a running testnet.
  val TESTNET: Seq[PredefinedSnapshotSettings] = Seq(
    PredefinedSnapshotSettings(
      height = GenesisBlockHeight.toInt,
      balances = List(
        GenesisBalanceSettings("thrth1x0welf80ljp2psdstmfywkhqmj9s7q5hjgzpvj", 3_000_000L * Constants.UnitsInHearth), // burn-claim, 3%
        GenesisBalanceSettings("thrth1nw24ly6qrzatspdzy72t5lhpgcklw7ehcqpjhn", 1_000_000L * Constants.UnitsInHearth), // DAO treasury, 1%
        GenesisBalanceSettings("thrth1wpm9trpt4fm4ucmmq556f6j6arzxg7c4n9rgsj", 1_000_000L * Constants.UnitsInHearth)  // team (vested), 1%
      )
    )
  )

  // Deliberately not following the 5%/95% split above: an internal-only devnet, premined in full for the fastest
  // possible bring-up, with an even shorter half-life (RewardsSettings.STAGENET) than TESTNET. Not economically
  // meaningful, so its total supply is not held to the hard cap the way MAINNET/TESTNET's genesis balances are.
  val STAGENET: Seq[PredefinedSnapshotSettings] = Seq(
    PredefinedSnapshotSettings(
      height = GenesisBlockHeight.toInt,
      balances = List(
        GenesisBalanceSettings("3Mi63XiwniEj6mTC557pxdRDddtpj7fZMMw", Constants.UnitsInHearth * Constants.TotalHearth)
      )
    )
  )
}

/** The genesis block is derived from the settings below, so a node that disagrees about any of them silently builds
  * a chain of its own. [[stateHash]] and [[blockId]] pin that derivation: when either is set,
  * [[tech.hearth.block.Block.genesis]] refuses to build a genesis block that does not match it, and the node stops
  * on start instead of forking.
  *
  * @param stateHash
  *   The hash of the snapshot built from the height-1 [[PredefinedSnapshotSettings]]. Pins the state the genesis
  *   block carries.
  * @param blockId
  *   The id of the genesis block, which is the hash of its header, so it covers the state hash along with `timestamp`
  *   and `initial-base-target`. It does not cover `signature`, which is not part of the header and is verified on its
  *   own. This is the value peers compare when they decide whether they are on the same chain.
  */
case class GenesisSettings(
    timestamp: Long,
    signature: Option[ByteStr],
    initialBaseTarget: Long,
    averageBlockDelay: FiniteDuration,
    stateHash: Option[ByteStr] = None,
    blockId: Option[ByteStr] = None
) {
  def blockTimestamp: Long = timestamp
}

object GenesisSettings { // TODO: Move to network-defaults.conf
  // This given is required for default args to work, see FunctionalitySettings.
  given ConfigReader[GenesisSettings] = deriveReader

  // Note: the predefined signatures of the pre-snapshot genesis blocks are gone along with the genesis transactions
  // they were made over. The blocks below are signed by Block.GenesisGenerator instead.
  val MAINNET: GenesisSettings  = GenesisSettings(1465742577614L, None, 153722867L, 60.seconds)
  val TESTNET: GenesisSettings  = GenesisSettings(1478000000000L, None, 153722867L, 60.seconds)
  val STAGENET: GenesisSettings = GenesisSettings(1561705836768L, None, 5000, 1.minute)
}

case class BlockchainSettings(
    addressSchemeCharacter: Char,
    functionalitySettings: FunctionalitySettings,
    genesisSettings: GenesisSettings,
    rewardsSettings: RewardsSettings,
    predefinedSnapshots: Seq[PredefinedSnapshotSettings] = Seq.empty
) {
  require(
    predefinedSnapshots.map(_.height).distinct.size == predefinedSnapshots.size,
    s"Duplicate predefined snapshot height in: ${predefinedSnapshots.map(_.height)}"
  )

  /** The height-1 predefined snapshot, or an empty one if the settings don't declare one - settings built directly
    * in code (tests, tools) often don't; a real network's config always does (enforced when it's parsed).
    */
  lazy val genesisSnapshot: PredefinedSnapshotSettings =
    predefinedSnapshots.find(_.height == GenesisBlockHeight.toInt).getOrElse(PredefinedSnapshotSettings(GenesisBlockHeight.toInt))

  /** Total amount of Hearth declared in the genesis (height 1) predefined snapshot. */
  lazy val initialBalance: Long = genesisSnapshot.balances.map(_.hearth).foldLeft(0L)(Math.addExact)

  /** This network's own supply ceiling: genesis premine plus everything the emission curve still has left to mint
    * (`hearth-tokenomics-spec` S2.1: `Cmax = Pgen + Cemit`). Derived from this network's own settings rather than
    * the global `Constants.TotalHearth`, so it holds for every network including STAGENET, whose premine/emission
    * deliberately don't sum to `Constants.TotalHearth` (see `PredefinedSnapshotSettings.STAGENET`).
    */
  lazy val hardCap: Long = Math.addExact(initialBalance, rewardsSettings.cEmit)
}

private[settings] object BlockchainType {
  val STAGENET = "STAGENET"
  val TESTNET  = "TESTNET"
  val MAINNET  = "MAINNET"
}

object BlockchainSettings {
  def fromRootConfig(config: Config): BlockchainSettings =
    ConfigSource.fromConfig(config).at("hearth.blockchain").loadOrThrow[BlockchainSettings]

  given ConfigReader[BlockchainSettings] = ConfigReader.fromCursor(cur =>
    for {
      objCur               <- cur.asObjectCursor
      blockchainTypeString <- objCur.atKey("type").flatMap(_.asString).map(_.toUpperCase)
      (addressSchemeCharacter, functionalitySettings, genesisSettings, rewardsSettings, predefinedSnapshots) <- blockchainTypeString match {
        case BlockchainType.STAGENET =>
          Right(('S', FunctionalitySettings.STAGENET, GenesisSettings.STAGENET, RewardsSettings.STAGENET, PredefinedSnapshotSettings.STAGENET))
        case BlockchainType.TESTNET =>
          Right(('T', FunctionalitySettings.TESTNET, GenesisSettings.TESTNET, RewardsSettings.TESTNET, PredefinedSnapshotSettings.TESTNET))
        case BlockchainType.MAINNET =>
          Right(('W', FunctionalitySettings.MAINNET, GenesisSettings.MAINNET, RewardsSettings.MAINNET, PredefinedSnapshotSettings.MAINNET))
        case _ =>
          // Custom
          for {
            customObjCur       <- objCur.atKey("custom").flatMap(_.asObjectCursor)
            networkId          <- customObjCur.atKey("address-scheme-character").flatMap(_.asString).map(_.charAt(0))
            functionality      <- customObjCur.atKey("functionality").flatMap(ConfigReader[FunctionalitySettings].from)
            genesis            <- customObjCur.atKey("genesis").flatMap(ConfigReader[GenesisSettings].from)
            rewards            <- customObjCur.atKey("rewards").flatMap(ConfigReader[RewardsSettings].from)
            predefinedSnapshot <- customObjCur.atKey("predefined-snapshots").flatMap(ConfigReader[Seq[PredefinedSnapshotSettings]].from)
          } yield {
            require(functionality.minBlockTime <= genesis.averageBlockDelay, "min-block-time should be <= average-block-delay")
            (networkId, functionality, genesis, rewards, predefinedSnapshot)
          }
      }

    } yield BlockchainSettings(addressSchemeCharacter, functionalitySettings, genesisSettings, rewardsSettings, predefinedSnapshots)
  )
}
