package com.wavesplatform.transaction

import com.wavesplatform.account.PublicKey
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.serialization.impl.PBTransactionSerializer
import com.wavesplatform.transaction.transfer.*
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
