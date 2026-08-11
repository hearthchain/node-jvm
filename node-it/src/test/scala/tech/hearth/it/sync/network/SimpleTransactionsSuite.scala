package tech.hearth.it.sync.network

import com.typesafe.config.Config
import tech.hearth.account.Address
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.api.AsyncNetworkApi.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.sync.*
import tech.hearth.it.transactions.BaseTransactionSuite
import tech.hearth.network.{PBTransactionSpec, RawBytes}
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.TxHelpers

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

class SimpleTransactionsSuite extends BaseTransactionSuite {
  import tech.hearth.it.NodeConfigs.*
  override val nodeConfigs: Seq[Config] = Seq(BiggestMiner.quorum(0))

  private def node = nodes.head

  test("valid tx send by network to node should be in blockchain") {
    val tx = TxHelpers.transfer(node.keyPair, Address.fromString(node.address).explicitGet(), 1L, Hearth, minFee, Hearth)

    node.sendByNetwork(RawBytes.fromTransaction(tx))
    node.waitForTransaction(tx.id().toString)

  }

  test("invalid tx send by network to node should be not in UTX or blockchain") {
    val tx = TxHelpers.transfer(
      node.keyPair,
      Address.fromString(node.address).explicitGet(),
      1L,
      Hearth,
      minFee,
      Hearth,
      timestamp = System.currentTimeMillis() + (1 days).toMillis
    )

    node.sendByNetwork(RawBytes.fromTransaction(tx))
    val maxHeight = nodes.map(_.height).max
    nodes.waitForHeight(maxHeight + 1)
    node.ensureTxDoesntExist(tx.id().toString)
  }

  test("should blacklist senders of non-parsable transactions") {
    val blacklistBefore = node.blacklistedPeers
    node.sendByNetwork(RawBytes(PBTransactionSpec.messageCode, "foobar".getBytes(StandardCharsets.UTF_8)))
    node.waitForBlackList(blacklistBefore.size)
  }
}
