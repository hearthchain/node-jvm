package tech.hearth.state.patch

import tech.hearth.account.NetworkId
import tech.hearth.features.BlockchainFeature
import tech.hearth.state.{Blockchain, StateSnapshot}
import play.api.libs.json.{Json, Reads}

import scala.io.Source

trait PatchDataLoader {
  protected def readPatchData[T: Reads](): T =
    Json
      .parse(
        Source
          .fromResource(s"patches/${getClass.getSimpleName.replace("$", "")}-${NetworkId.current.value}.json")
          .mkString
      )
      .as[T]
}

trait DiffPatchFactory extends PartialFunction[Blockchain, StateSnapshot]

abstract class PatchAtHeight(networkIdToHeight: (NetworkId, Int)*) extends PatchDataLoader with DiffPatchFactory {
  private val networkIdToHeightMap       = networkIdToHeight.toMap
  protected def patchHeight: Option[Int] = networkIdToHeightMap.get(NetworkId.current)

  override def isDefinedAt(blockchain: Blockchain): Boolean =
    networkIdToHeightMap.get(blockchain.settings.networkId).contains(blockchain.height)
}

abstract class PatchOnFeature(feature: BlockchainFeature, networks: Set[NetworkId] = Set.empty) extends PatchDataLoader with DiffPatchFactory {
  override def isDefinedAt(blockchain: Blockchain): Boolean = {
    (networks.isEmpty || networks.contains(blockchain.settings.networkId)) &&
    blockchain.featureActivationHeight(feature).contains(blockchain.height)
  }
}
