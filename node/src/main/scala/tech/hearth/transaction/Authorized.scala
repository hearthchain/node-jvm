package tech.hearth.transaction

import tech.hearth.account.PublicKey

trait Authorized {
  def sender: PublicKey
}
