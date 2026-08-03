package tech.hearth.it.transactions

import tech.hearth.it.{Docker, DockerBased, Node, Nodes}
import monix.eval.Coeval
import org.scalatest.Suite

trait NodesFromDocker extends Nodes with DockerBased { suite: Suite =>
  protected val dockerNodes: Coeval[Seq[Docker.DockerNode]] = dockerSingleton
    .map(_.startNodes(nodeConfigs))
    .memoize

  override protected def nodes: Seq[Node] = dockerNodes()
}
