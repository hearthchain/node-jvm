package tech.hearth.transaction

import tech.hearth.account.PublicKey
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.PropSpec
import tech.hearth.transaction.serialization.impl.PBTransactionSerializer
import tech.hearth.transaction.transfer.*
import tech.hearth.crypto.SigningKey

class TransactionSpecification extends PropSpec {

  property("transaction fields should be constructed in a right way") {
    forAll(bytes32gen, bytes32gen, timestampGen, positiveLongGen, positiveLongGen) {
      (senderSeed: Array[Byte], recipientSeed: Array[Byte], time: Long, amount: Long, fee: Long) =>
        val sender    = SigningKey.fromSeed(senderSeed)
        val recipient = SigningKey.fromSeed(recipientSeed)

        val tx = createWavesTransfer(sender, recipient.toAddress, amount, fee, time).explicitGet()

        tx.timestamp shouldEqual time
        tx.amount.value shouldEqual amount
        tx.fee.value shouldEqual fee
        tx.sender shouldEqual PublicKey(sender.publicKey)
        tx.recipient shouldEqual recipient.toAddress
    }
  }

  property("bytes()/parse() roundtrip should preserve a transaction") {
    forAll(bytes32gen, bytes32gen, timestampGen, positiveLongGen, positiveLongGen) {
      (senderSeed: Array[Byte], recipientSeed: Array[Byte], time: Long, amount: Long, fee: Long) =>
        val sender    = SigningKey.fromSeed(senderSeed)
        val recipient = SigningKey.fromSeed(recipientSeed)
        val tx        = createWavesTransfer(sender, recipient.toAddress, amount, fee, time).explicitGet()
        val txAfter   = PBTransactionSerializer.parseBytes(tx.bytes()).get.asInstanceOf[TransferTransaction]

        txAfter.getClass.shouldBe(tx.getClass)

        tx.proofs shouldEqual txAfter.proofs
        tx.sender shouldEqual txAfter.sender
        tx.recipient shouldEqual txAfter.recipient
        tx.timestamp shouldEqual txAfter.timestamp
        tx.amount shouldEqual txAfter.amount
        tx.fee shouldEqual txAfter.fee
    }
  }

}
