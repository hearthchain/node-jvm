package tech.hearth.settings

import tech.hearth.mining.Miner
import pureconfig.*
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

case class MinerSettings(
    enable: Boolean,
    quorum: Int,
    intervalAfterLastBlockThenGenerationIsAllowed: FiniteDuration,
    noQuorumMiningDelay: FiniteDuration,
    microBlockInterval: FiniteDuration,
    minimalBlockGenerationOffset: FiniteDuration,
    maxTransactionsInMicroBlock: Int,
    minMicroBlockAge: FiniteDuration,
    accounts: Seq[MiningAccount],
    supportedFeatures: Seq[Short]
) derives ConfigReader {
  require(maxTransactionsInMicroBlock <= Miner.MaxTransactionsPerMicroblock)
}

case class MiningAccount(
    mnemonic: Option[String],
    signingAccount: Int = 0,
    vrfAccount: Int = 0,
    blsAccount: Int = 0,
    signingKey: Option[String],
    vrfKey: Option[String],
    blsKey: Option[String]
)

object MiningAccount {
  // This given is required for default args to work, see FunctionalitySettings.
  given ConfigReader[MiningAccount] = deriveReader
}
