package com.wavesplatform.history

import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.settings.WavesSettings
import org.scalacheck.Gen
import org.scalatest.Suite
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks as GeneratorDrivenPropertyChecks}

trait DomainScenarioDrivenPropertyCheck extends WithDomain { suite: Suite & GeneratorDrivenPropertyChecks =>

  /** @param balances
    *   Accounts to credit in the genesis snapshot. Since there are no genesis transactions any more, this is the only
    *   way to fund an account out of thin air: the snapshot is built from the settings the domain is created with.
    */
  def scenario[S](
      gen: Gen[S],
      bs: WavesSettings = DefaultWavesSettings,
      balances: S => Seq[AddrWithBalance] = (_: S) => Seq.empty[AddrWithBalance]
  )(assertion: (Domain, S) => Any): Any =
    forAll(gen) { s =>
      withDomain(bs, balances(s)) { domain =>
        assertion(domain, s)
      }
    }
}
