package tech.hearth.features

case class BlockchainFeature(id: Short, description: String)

object BlockchainFeatures {

  val SmallerMinimalGeneratingBalance = BlockchainFeature(1, "Minimum Generating Balance of 1000 HRTH")

  // When next fork-parameter is created, you must replace all uses of the DummyFeature with the new one.
  val Dummy = BlockchainFeature(-1, "Non Votable!")

  private val dict = Seq(
    SmallerMinimalGeneratingBalance
  ).map(f => f.id -> f).toMap

  val implemented: Set[Short] = dict.keySet

  def feature(id: Short): Option[BlockchainFeature] = dict.get(id)
}
