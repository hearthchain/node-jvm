package com.wavesplatform.generator.utils

import com.wavesplatform.transaction.lease.LeaseTransaction

object Universe {
  @volatile var Leases: List[LeaseTransaction] = Nil
}
