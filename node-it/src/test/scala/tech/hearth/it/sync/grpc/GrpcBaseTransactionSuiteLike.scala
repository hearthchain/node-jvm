package tech.hearth.it.sync.grpc

import tech.hearth.it.{GrpcIntegrationSuiteWithThreeAddress, GrpcWaitForHeight, Nodes}
import org.scalatest.*

trait GrpcBaseTransactionSuiteLike extends GrpcWaitForHeight with GrpcIntegrationSuiteWithThreeAddress { this: TestSuite & Nodes => }

abstract class GrpcBaseTransactionSuite extends funsuite.AnyFunSuite with GrpcBaseTransactionSuiteLike
