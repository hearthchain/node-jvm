package tech.hearth.it.sync

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.api.TransactionInfo
import tech.hearth.it.keyPairFromSeed
import tech.hearth.it.{BaseFunSuite, Node}
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.{Proofs, TxNonNegativeAmount}
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.crypto.SigningKey

import scala.util.{Random, Try}

class UtxSuite extends BaseFunSuite {
  private val ENOUGH_FEE = 5000000
  private val AMOUNT     = ENOUGH_FEE * 10

  test("Invalid transaction should be removed from utx") {
    val account = UtxSuite.createAccount

    val transferToAccount = TransferTransaction
      .create(
        PublicKey(miner.keyPair.publicKey()),
        Hearth,
        Seq(TransferTransaction.ParsedTransfer(account.toAddress, TxNonNegativeAmount.unsafeFrom(AMOUNT))),
        ENOUGH_FEE,
        System.currentTimeMillis(),
        ByteStr.empty,
        Proofs.empty
      )
      .map(_.signWith(miner.keyPair))
      .explicitGet()

    miner.signedBroadcast(transferToAccount.json())

    nodes.waitForHeightAriseAndTxPresent(transferToAccount.id().toString)

    val firstTransfer = TransferTransaction
      .create(
        PublicKey(account.publicKey()),
        Hearth,
        Seq(TransferTransaction.ParsedTransfer(miner.keyPair.toAddress, TxNonNegativeAmount.unsafeFrom(AMOUNT - ENOUGH_FEE))),
        ENOUGH_FEE,
        System.currentTimeMillis(),
        ByteStr.empty,
        Proofs.empty
      )
      .map(_.signWith(account))
      .explicitGet()

    val secondTransfer = TransferTransaction
      .create(
        PublicKey(account.publicKey()),
        Hearth,
        Seq(TransferTransaction.ParsedTransfer(notMiner.keyPair.toAddress, TxNonNegativeAmount.unsafeFrom(AMOUNT - ENOUGH_FEE))),
        ENOUGH_FEE,
        System.currentTimeMillis(),
        ByteStr.empty,
        Proofs.empty
      )
      .map(_.signWith(account))
      .explicitGet()

    val tx2Id = notMiner.signedBroadcast(secondTransfer.json(), waitForTx = false).id
    val tx1Id = miner.signedBroadcast(firstTransfer.json(), waitForTx = false).id

    nodes.waitFor("empty utx")(_.utxSize)(_.forall(_ == 0))

    val exactlyOneTxInBlockchain =
      txInBlockchain(tx1Id, nodes) ^ txInBlockchain(tx2Id, nodes)

    assert(exactlyOneTxInBlockchain, "Only one tx should be in blockchain")
  }

  def txInBlockchain(txId: String, nodes: Seq[Node]): Boolean = {
    nodes.forall { node =>
      Try(node.transactionInfo[TransactionInfo](txId)).isSuccess
    }
  }
}

object UtxSuite {
  private def createAccount: SigningKey = {
    val seed = Array.fill(32)(-1: Byte)
    Random.nextBytes(seed)
    keyPairFromSeed(seed)
  }
}
