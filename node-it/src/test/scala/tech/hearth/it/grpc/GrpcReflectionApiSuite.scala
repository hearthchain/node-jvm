package tech.hearth.it.grpc

import com.typesafe.config.Config
import tech.hearth.it.NodeConfigs
import tech.hearth.it.NodeConfigs.Default
import tech.hearth.it.sync.grpc.GrpcBaseTransactionSuite
import io.grpc.{CallOptions, ManagedChannelBuilder}
import io.grpc.reflection.v1.ServerReflectionGrpc.getServerReflectionInfoMethod
import io.grpc.reflection.v1.ServerReflectionRequest
import io.grpc.stub.ClientCalls

import scala.util.Try

class GrpcReflectionApiSuite extends GrpcBaseTransactionSuite {
  import NodeConfigs.{overrides, quorum}

  // NodeConfigs.Builder(Default, 1, Seq()).buildNonConflicting() always maps a single defaultEntities node to the
  // lowest-index NonConflictingNodes entry, node01 - the lowest-balance miner-eligible account in the whole fixture
  // (see CLAUDE.md's node-it fixtures notes). As the suite's sole miner, its PoS delay for the very first block
  // regularly exceeded GrpcIntegrationSuiteWithThreeAddress's 50s beforeAll waitForHeight(2) timeout. Default(6)
  // (node07) is far enough ahead in balance to reach height 2 comfortably.
  override protected def nodeConfigs: Seq[Config] =
    Seq(
      Default(6)
        .quorum(0)
        .overrides("hearth.extensions = [tech.hearth.api.grpc.GRPCServerExtension\ntech.hearth.events.BlockchainUpdates]")
    )

  test("successful getServerReflectionInfo call for BU") {
    val buChannel = ManagedChannelBuilder
      .forAddress(nodes.head.nodeApiEndpoint.getHost, nodes.head.nodeExternalPort(6881))
      .usePlaintext()
      .build()
    val call = buChannel.newCall(getServerReflectionInfoMethod, CallOptions.DEFAULT)
    // Proto packages moved from hearth.* to hearth.* in the protobuf-schemas migration (see CLAUDE.md's protobuf
    // package migration notes); this symbol is stale from before it.
    val request = ServerReflectionRequest.newBuilder().setFileContainingSymbol("hearth.events.grpc.BlockchainUpdatesApi").build()
    val result  = Try(ClientCalls.blockingUnaryCall(call, request))
    result.isSuccess shouldBe true
    result.get.hasFileDescriptorResponse shouldBe true
  }

  test("successful getServerReflectionInfo call for GRPC methods") {
    val call    = nodes.head.grpcChannel.newCall(getServerReflectionInfoMethod, CallOptions.DEFAULT)
    val request = ServerReflectionRequest.newBuilder().setFileContainingSymbol("hearth.node.grpc.BlocksApi").build()
    val result  = Try(ClientCalls.blockingUnaryCall(call, request))
    result.isSuccess shouldBe true
    result.get.hasFileDescriptorResponse shouldBe true
  }
}
