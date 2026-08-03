package tech.hearth.generator.utils

import tech.hearth.transaction.lease.LeaseTransaction

object Universe {
  @volatile var Leases: List[LeaseTransaction] = Nil
}
