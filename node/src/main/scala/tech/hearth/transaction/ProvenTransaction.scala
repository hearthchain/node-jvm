package tech.hearth.transaction

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.SigningKey

trait ProvenTransaction extends Proven { this: Transaction =>
  type T <: Transaction
  def addProof(proof: ByteStr): T
}

object ProvenTransaction {
  extension (p: ProvenTransaction) {
    def signWith(privateKey: SigningKey): p.T = p.addProof(ByteStr(privateKey.sign(p.bodyBytes())))
  }
}
