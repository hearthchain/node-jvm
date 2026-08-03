package tech.hearth.transaction

import cats.data.ValidatedNel
import tech.hearth.lang.ValidationError

package object validation {
  type ValidatedV[A] = ValidatedNel[ValidationError, A]
  type ValidatedNV   = ValidatedV[Unit]
}
