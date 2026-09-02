package tech.hearth.it.sync.grpc

import com.typesafe.config.ConfigFactory
import tech.hearth.it.{Docker, GrpcIntegrationSuiteWithThreeAddress, GrpcWaitForHeight, Nodes}
import org.scalatest.*

trait GrpcBaseTransactionSuiteLike extends GrpcWaitForHeight with GrpcIntegrationSuiteWithThreeAddress { this: TestSuite & Nodes =>
  // The extension is off by default (template.conf), so only these suites start a gRPC server and get its port
  // published. A suite overriding hearth.extensions in its own nodeConfigs replaces this list and must repeat it.
  override protected def createDocker: Docker =
    new Docker(
      suiteConfig = ConfigFactory.parseString(s"hearth.extensions = [${Docker.GrpcExtension}]"),
      tag = getClass.getSimpleName
    )
}

abstract class GrpcBaseTransactionSuite extends funsuite.AnyFunSuite with GrpcBaseTransactionSuiteLike
