package tech.hearth.settings

import com.typesafe.config.ConfigFactory

import tech.hearth.state.GenesisBlockHeight

object TestSettings {

  /** Testnet's own initial base target is tuned for the generating balance a live network carries, which is orders of
    * magnitude above what a test funds its accounts with - on those balances it stretches blocks minutes apart, and
    * every suite that appends blocks against the wall clock (`strictTime`) ends up in the future. Tests keep the value
    * the chain used to start from, which yields roughly the configured average block delay.
    */
  private val TestInitialBaseTarget = 153722867L

  extension (genesisSettings: GenesisSettings) {

    /** The same settings without the commitments. A pinned state hash or block id only describes the genesis of the
      * network it was taken from, so a test deriving a genesis of its own from a network preset has to drop them, or
      * [[tech.hearth.block.Block.genesis]] rejects the result.
      */
    def unpinned: GenesisSettings = genesisSettings.copy(stateHash = None, blockId = None)
  }

  /** A network preset describes a real chain: its predefined snapshot credits that chain's accounts and commits its
    * generator, and its genesis settings pin the hash and block id built from them. Tests declare a genesis of their
    * own (see DomainPresets' withGenesisBalances and friends), so this drops both.
    */
  def withTestGenesis(settings: HearthSettings): HearthSettings =
    settings.copy(blockchainSettings =
      settings.blockchainSettings.copy(
        genesisSettings = settings.blockchainSettings.genesisSettings.unpinned.copy(initialBaseTarget = TestInitialBaseTarget),
        predefinedSnapshots = Seq(PredefinedSnapshotSettings(GenesisBlockHeight.toInt))
      )
    )

  val Default: HearthSettings = withTestGenesis(HearthSettings.fromRootConfig(ConfigFactory.load()))
}
