package tech.hearth.settings

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.typesafe.config.Config
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.state.{GenesisBlockHeight, Height}
import pureconfig.*
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.*

case class RewardsSettings(
    term: Int,
    termAfterCappedRewardFeature: Int,
    initial: Long,
    minIncrement: Long,
    votingInterval: Int
) derives ConfigReader {
  require(initial >= 0, "initial must be greater than or equal to 0")
  require(minIncrement > 0, "min-increment must be greater than 0")
  require(term > 0, "term must be greater than 0")
  require(votingInterval > 0, "voting-interval must be greater than 0")
  require(votingInterval <= term, s"voting-interval must be less than or equal to term($term)")
  require(termAfterCappedRewardFeature > 0, "term-after-capped-reward-feature must be greater than 0")
  require(
    votingInterval <= termAfterCappedRewardFeature,
    s"voting-interval must be less than or equal to term-after-capped-reward-feature($termAfterCappedRewardFeature)"
  )

  def nearestTermEnd(activatedAt: Height, height: Height, modifyTerm: Boolean): Height = {
    require(height >= activatedAt)
    val diff         = height - activatedAt + 1
    val modifiedTerm = if (modifyTerm) termAfterCappedRewardFeature else term
    val mul          = math.ceil(diff.toDouble / modifiedTerm).toInt
    activatedAt + mul * modifiedTerm - 1
  }

  def votingWindow(activatedAt: Int, height: Int, modifyTerm: Boolean): Range = {
    val end   = nearestTermEnd(Height(activatedAt), Height(height), modifyTerm)
    val start = end - votingInterval + 1
    if (Height(height) >= start) Range.inclusive(start.toInt, height)
    else Range(0, 0)
  }
}

object RewardsSettings {
  val MAINNET, TESTNET, STAGENET = apply(
    100000,
    50000,
    6 * Constants.UnitsInWave,
    50000000,
    10000
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
case class GenesisBalanceSettings(recipient: String, waves: Long = 0L, assets: Map[String, Long] = Map.empty)

object GenesisBalanceSettings {
  // This given is required for default args to work, see FunctionalitySettings.
  given ConfigReader[GenesisBalanceSettings] = deriveReader
}

/** A chunk of state applied outside of transaction processing, before the block at [[height]] applies its own
  * transactions. Since there is no issue transaction any more, this is the only way to mint a new asset; it can also
  * credit asset balances and commit generators. Only the height-1 (genesis) entry may credit Waves - Waves supply
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

  val MAINNET: Seq[PredefinedSnapshotSettings] = Seq(
    PredefinedSnapshotSettings(
      height = GenesisBlockHeight.toInt,
      balances = List(
        GenesisBalanceSettings("3PAWwWa6GbwcJaFzwqXQN5KQm7H96Y7SHTQ", Constants.UnitsInWave * Constants.TotalWaves - 5 * Constants.UnitsInWave),
        GenesisBalanceSettings("3P8JdJGYc7vaLu4UXUZc1iRLdzrkGtdCyJM", Constants.UnitsInWave),
        GenesisBalanceSettings("3PAGPDPqnGkyhcihyjMHe9v36Y4hkAh9yDy", Constants.UnitsInWave),
        GenesisBalanceSettings("3P9o3ZYwtHkaU1KxsKkFjJqJKS3dLHLC9oF", Constants.UnitsInWave),
        GenesisBalanceSettings("3PJaDyprvekvPXPuAtxrapacuDJopgJRaU3", Constants.UnitsInWave),
        GenesisBalanceSettings("3PBWXDFUc86N2EQxKJmW8eFco65xTyMZx6J", Constants.UnitsInWave)
      )
    )
  )

  val TESTNET: Seq[PredefinedSnapshotSettings] = Seq(
    PredefinedSnapshotSettings(
      height = GenesisBlockHeight.toInt,
      balances = List(
        GenesisBalanceSettings("3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8", (Constants.UnitsInWave * Constants.TotalWaves * 0.04).toLong),
        GenesisBalanceSettings("3NBVqYXrapgJP9atQccdBPAgJPwHDKkh6A8", (Constants.UnitsInWave * Constants.TotalWaves * 0.02).toLong),
        GenesisBalanceSettings("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", (Constants.UnitsInWave * Constants.TotalWaves * 0.02).toLong),
        GenesisBalanceSettings("3NCBMxgdghg4tUhEEffSXy11L6hUi6fcBpd", (Constants.UnitsInWave * Constants.TotalWaves * 0.02).toLong),
        GenesisBalanceSettings(
          "3N18z4B8kyyQ96PhN5eyhCAbg4j49CgwZJx",
          (Constants.UnitsInWave * Constants.TotalWaves - Constants.UnitsInWave * Constants.TotalWaves * 0.1).toLong
        )
      )
    )
  )

  val STAGENET: Seq[PredefinedSnapshotSettings] = Seq(
    PredefinedSnapshotSettings(
      height = GenesisBlockHeight.toInt,
      balances = List(
        GenesisBalanceSettings("3Mi63XiwniEj6mTC557pxdRDddtpj7fZMMw", Constants.UnitsInWave * Constants.TotalWaves)
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

  /** Total amount of Waves declared in the genesis (height 1) predefined snapshot. */
  lazy val initialBalance: Long = genesisSnapshot.balances.map(_.waves).foldLeft(0L)(Math.addExact)
}

private[settings] object BlockchainType {
  val STAGENET = "STAGENET"
  val TESTNET  = "TESTNET"
  val MAINNET  = "MAINNET"
}

object BlockchainSettings {
  def fromRootConfig(config: Config): BlockchainSettings =
    ConfigSource.fromConfig(config).at("waves.blockchain").loadOrThrow[BlockchainSettings]

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
