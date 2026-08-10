package tech.hearth.history

import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.HearthSettings
import org.scalacheck.Gen
import org.scalatest.Suite
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks as GeneratorDrivenPropertyChecks}
import tech.hearth.crypto.SigningKey

trait DomainScenarioDrivenPropertyCheck extends WithDomain { suite: Suite & GeneratorDrivenPropertyChecks =>

  /** @param balances
    *   Accounts to credit in the genesis snapshot. Since there are no genesis transactions any more, this is the only
    *   way to fund an account out of thin air: the snapshot is built from the settings the domain is created with.
    */
  /** @param generators
    *   Accounts to commit as generators in the genesis snapshot. A block only appends if its generator is committed, so
    *   a scenario that mines with an account of its own has to name it here.
    */
  def scenario[S](
      gen: Gen[S],
      bs: HearthSettings = DefaultHearthSettings,
      balances: S => Seq[AddrWithBalance] = (_: S) => Seq.empty[AddrWithBalance],
      generators: S => Seq[SigningKey] = (_: S) => Seq.empty[SigningKey]
  )(assertion: (Domain, S) => Any): Any =
    forAll(gen) { s =>
      withDomain(bs, balances(s), generators = generators(s)) { domain =>
        assertion(domain, s)
      }
    }
}
