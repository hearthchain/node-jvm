package tech.hearth.it.sync.grpc

import com.google.protobuf.ByteString
import tech.hearth.it.api.SyncGrpcApi.*
import tech.hearth.it.keyPairFromSeed
import tech.hearth.it.sync.minFee
import tech.hearth.protobuf.transaction.Recipient

class GeneratingBalanceSuite extends GrpcBaseTransactionSuite {

  test("Generating balance should be correct") {
    val amount = 1000000000L

    val senderAddress = ByteString.copyFrom(sender.keyPair.toAddress.toBytes())

    val recipient        = keyPairFromSeed("recipient".getBytes)
    val recipientAddress = ByteString.copyFrom(recipient.toAddress.toBytes())

    val initialBalance = sender.wavesBalance(senderAddress)

    sender.broadcastTransfer(sender.keyPair, Recipient().withPublicKeyHash(recipientAddress), amount, minFee, waitForTx = true)

    val afterTransferBalance = sender.wavesBalance(senderAddress)

    sender.broadcastTransfer(recipient, Recipient().withPublicKeyHash(senderAddress), amount - minFee, minFee, waitForTx = true)

    val finalBalance = sender.wavesBalance(senderAddress)

    assert(initialBalance.generating <= initialBalance.effective, "initial incorrect")
    assert(afterTransferBalance.generating <= afterTransferBalance.effective, "after transfer incorrect")
    assert(finalBalance.generating <= finalBalance.effective, "final incorrect")
  }
}
