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
    supportedFeatures: Seq[Short],
    desiredRewards: Option[Long]
) derives ConfigReader {
  require(maxTransactionsInMicroBlock <= Miner.MaxTransactionsPerMicroblock)
  // Mining without an account is not mining: fail at config load rather than run a node that silently generates nothing.
  require(!enable || accounts.nonEmpty, "hearth.miner.accounts must hold at least one account when mining is enabled")
}

case class MiningAccount(
    mnemonic: Option[String],
    signingAccount: Int = 0,
    vrfAccount: Int = 0,
    blsAccount: Int = 0,
    signingKeySeed: Option[String],
    signingKeyScalar: Option[String] = None,
    vrfKey: Option[String],
    blsKey: Option[String],
    blsKeyScalar: Option[String] = None
)

object MiningAccount {
  // This given is required for default args to work, see FunctionalitySettings.
  given ConfigReader[MiningAccount] = deriveReader
}
