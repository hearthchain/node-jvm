package tech.hearth.it.grpc

import com.google.common.primitives.Ints
import com.typesafe.config.Config
import tech.hearth.account.{NetworkId, PublicKey}
import tech.hearth.api.grpc.{BlockRangeRequest, BlockRequest, BlocksApiGrpc}
import tech.hearth.it.NodeConfigs
import tech.hearth.it.api.SyncGrpcApi.*
import tech.hearth.it.sync.grpc.GrpcBaseTransactionSuite
import tech.hearth.protobuf.block.*
import tech.hearth.protobuf.transaction.PBRecipients

class BlocksApiSuite extends GrpcBaseTransactionSuite {
  private val BlockV4Height = 3
  private val BlockV5Height = 5
  import NodeConfigs.{Default, overrides}

  // withDefault(1).buildNonConflicting() always assigns the lowest-index NonConflictingNodes entry (node01) as the
  // sole miner - the lowest-balance miner-eligible account in the whole fixture (see CLAUDE.md's node-it fixtures
  // notes), which slows this suite's beforeAll (100 sequential transfers) enough to intermittently time out.
  // Default(6) (node07) is far enough ahead in balance to avoid that.
  override protected def nodeConfigs: Seq[Config] =
    Seq(
      // Block format is a single, unconditional version now (no more per-height feature-gated transitions), and
      // every check below is version-agnostic (networkId), so BlockV4Height/BlockV5Height just pick heights to
      // sample rather than gate an actual behavior change.
      Default(6).overrides(s"""hearth {
                              |  miner {
                              |    quorum = 0
                              |    max-transactions-in-micro-block = 1
                              |  }
                              |}""".stripMargin)
    )

  private lazy val blocksApi = BlocksApiGrpc.blockingStub(sender.grpcChannel)

  private def validateHeaders(range: Range)(assertion: PBBlockHeader => Unit): Unit = {
    val headersByHeight = range.map(height => blocksApi.getBlock(BlockRequest(request = BlockRequest.Request.Height(height))))
    val headersRange    = blocksApi.getBlockRange(BlockRangeRequest(range.min, range.max))

    headersByHeight zip headersRange foreach { case (h1, h2) =>
      h1.getBlock.header shouldEqual h2.getBlock.header
      h1.getBlock.signature shouldEqual h2.getBlock.signature
      h1.getBlock.transactions should be(empty)
      h1.height shouldEqual h2.height
    }

    (headersByHeight ++ headersRange).foreach(bwh => assertion(bwh.block.get.header.get))
  }

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    1 to 100 foreach { i =>
      sender.broadcastTransfer(
        sender.keyPair,
        PBRecipients.create(PublicKey(Ints.toByteArray(i) ++ new Array[Byte](28)).toAddress),
        100000000L,
        100000L
      )
    }
  }

  test("Validate Block v3 header fields") {
    sender.waitForHeight(BlockV4Height)
    validateHeaders(2 to BlockV4Height) { header =>
      header.networkId shouldEqual NetworkId.current
    }
  }

  test("Validate Block v4 header fields") {
    sender.waitForHeight(BlockV5Height)
    validateHeaders(BlockV4Height + 1 until BlockV5Height) { header =>
      header.networkId shouldEqual NetworkId.current
    }
  }

  test("Validate Block v5 header fields") {
    sender.waitForHeight(BlockV5Height + 2)
    validateHeaders(BlockV5Height until BlockV5Height + 2) { header =>
      header.networkId shouldEqual NetworkId.current
    }
  }
}
