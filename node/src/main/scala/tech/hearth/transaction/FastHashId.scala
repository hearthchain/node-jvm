package tech.hearth.transaction

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import monix.eval.Coeval

trait FastHashId extends Proven {
  val id: Coeval[ByteStr] = Coeval.evalOnce(ByteStr(crypto.fastHash(bodyBytes())))
}
