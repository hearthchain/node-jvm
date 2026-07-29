package com.wavesplatform.settings

import com.wavesplatform.mining.Miner
import pureconfig.*

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
) derives ConfigReader
