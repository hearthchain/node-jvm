package tech.hearth.history

import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.transfer.*
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterMicroblockBadSignaturesTest extends PropSpec, WithDomain {

  type Setup = (SigningKey, TransferTransaction, TransferTransaction)

  private val s = TxHelpers.signer(200)

  property("bad total resulting block signature") {
    withDomain(balances = Seq(s -> 100.hearth)) { d =>
      d.appendBlockE(TxHelpers.transfer(s)) should beRight
      val mb = d.createMicroBlock()(TxHelpers.transfer(s))
      d.appendMicroBlockE(mb.copy(wholeBlockSignature = randomSig)) should produce("InvalidSignature")
    }
  }

  property("bad microBlock signature") {
    withDomain(balances = Seq(s -> 100.hearth)) { d =>
      d.appendBlockE(TxHelpers.transfer(s)) should beRight
      val mb = d.createMicroBlock()(TxHelpers.transfer(s))
      d.appendMicroBlockE(mb.copy(signature = randomSig)) should produce("InvalidSignature")
    }
  }

  property("other sender") {
    withDomain(balances = Seq(s -> 100.hearth)) { d =>
      d.appendBlockE(TxHelpers.transfer(s)) should beRight
      val mb = d.createMicroBlock(signer = Some(s))(TxHelpers.transfer(s))
      d.appendMicroBlockE(mb) should produce("another account")
    }
  }
}
