package com.wavesplatform.history

import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.test.*
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.transfer.*
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterMicroblockBadSignaturesTest extends PropSpec, WithDomain {

  type Setup = (SigningKey, TransferTransaction, TransferTransaction)

  private val s = TxHelpers.signer(200)

  property("bad total resulting block signature") {
    withDomain(balances = Seq(s -> 100.waves)) { d =>
      d.appendBlockE(TxHelpers.transfer(s)) should beRight
      val mb = d.createMicroBlock()(TxHelpers.transfer(s))
      d.appendMicroBlockE(mb.copy(wholeBlockSignature = randomSig)) should produce("InvalidSignature")
    }
  }

  property("bad microBlock signature") {
    withDomain(balances = Seq(s -> 100.waves)) { d =>
      d.appendBlockE(TxHelpers.transfer(s)) should beRight
      val mb = d.createMicroBlock()(TxHelpers.transfer(s))
      d.appendMicroBlockE(mb.copy(signature = randomSig)) should produce("InvalidSignature")
    }
  }

  property("other sender") {
    withDomain(balances = Seq(s -> 100.waves)) { d =>
      d.appendBlockE(TxHelpers.transfer(s)) should beRight
      val mb = d.createMicroBlock(signer = Some(s))(TxHelpers.transfer(s))
      d.appendMicroBlockE(mb) should produce("another account")
    }
  }
}
