package tech.hearth.mining

import tech.hearth.state.Blockchain
import tech.hearth.crypto.Address

import scala.concurrent.duration.FiniteDuration

object TestMiner {
  object SafelyDisabled extends Miner with MinerDebugInfo {
    override def nextBlockGenerationOffsets: Map[Address, Either[String, FiniteDuration]]                  = Map.empty
    override def scheduleMining(baseBlockchain: Option[Blockchain], cancelMicroBlockMining: Boolean): Unit = {}
    override def state: MinerDebugInfo.State                                                               = MinerDebugInfo.Disabled
  }
}
