package tech.hearth.state.appender

import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.SigningKey
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.state.BlockchainUpdaterImpl.BlockApplyResult.Applied
import tech.hearth.state.{Height, ImplicitGenerator}
import tech.hearth.test.DomainPresets.*
import tech.hearth.test.{FreeSpec, NumericExt}
import tech.hearth.transaction.{CommitToGenerationTransaction, TxHelpers}

class ImplicitGeneratorSpec extends FreeSpec with WithDomain {
  private val committed      = TxHelpers.defaultSigner
  private val outsider       = TxHelpers.signer(1)
  private val outsiderVrfKey = ByteStr(TxHelpers.vrfKeyOf(outsider).publicKey())
  private val secondOutsider = TxHelpers.signer(2)

  private val settings = DomainPresets.DeterministicFinality.configure(_.copy(generationPeriodLength = 10))

  private def balances = AddrWithBalance.enoughBalances(committed, outsider)

  /** Leaves `generator` with its deposit only, so from the next block on it can not generate */
  private def spendAll(d: Domain, generator: SigningKey): Unit = {
    val fee      = 1.hearth
    val amount   = d.balance(generator.toAddress) - CommitToGenerationTransaction.DepositInEmbers - fee
    val transfer = TxHelpers.transfer(generator, TxHelpers.signer(9).toAddress, amount, fee = fee)
    d.appender.appendBlock(d.createBlock(Seq(transfer), generator = generator, strictTime = true))
  }

  "an uncommitted generator mines once no committed generator can, committing the VRF key its block carries" in
    withDomain(settings, balances) { d =>
      spendAll(d, committed)

      val block = d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey))
      d.appender.appendBlockWithoutFallback(block).explicitGet() shouldBe a[Applied]

      d.blockchain.implicitGenerators(currentPeriod(d)) shouldBe Seq(ImplicitGenerator(outsider.toAddress, outsiderVrfKey))
    }

  "an uncommitted generator can't mine while a committed generator can" in withDomain(settings, balances) { d =>
    val block = d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey))
    d.appender.appendBlockWithoutFallback(block).left.value.toString should include(s"${outsider.toAddress} is not allowed to generate a block")
  }

  "a committed generator that can't generate can't mine, even when no committed generator can" in withDomain(settings, balances) { d =>
    spendAll(d, committed)
    d.appender.appendBlockWithoutFallback(d.createBlock(generator = committed, strictTime = true)).left.value.toString should include(
      s"${committed.toAddress} is not allowed to generate a block"
    )
  }

  "the first block of an uncommitted generator in a period has to carry its VRF key" in withDomain(settings, balances) { d =>
    spendAll(d, committed)
    d.appender.appendBlockWithoutFallback(d.createBlock(generator = outsider, strictTime = true)).left.value.toString should include(
      "has to carry the VRF public key it commits with"
    )
  }

  "the VRF key an uncommitted generator declares" - {
    "has to be 32 bytes" in withDomain(settings, balances) { d =>
      spendAll(d, committed)
      Seq(31, 33).foreach { length =>
        val declared = ByteStr(outsiderVrfKey.arr.padTo(length, 1.toByte).take(length))
        val block    = d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(declared))
        d.appender.appendBlockWithoutFallback(block).left.value.toString should include(s"VRF public key of $length bytes, expected 32")
      }
    }

    "can't be registered in the period already" in withDomain(settings, AddrWithBalance.enoughBalances(committed, outsider, secondOutsider)) { d =>
      spendAll(d, committed)
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey)))

      val committedVrfKey = ByteStr(TxHelpers.vrfKeyOf(committed).publicKey())
      Seq(outsiderVrfKey, committedVrfKey).foreach { taken =>
        val block = d.createBlock(generator = secondOutsider, strictTime = true, vrfPublicKey = Some(taken))
        d.appender.appendBlockWithoutFallback(block).left.value.toString should include(s"VRF public key $taken is already registered")
      }
    }
  }

  "an uncommitted challenger has to be able to generate on its own balance to declare a VRF key" in withDomain(settings, balances) { d =>
    spendAll(d, committed)
    val original = d.createBlock(
      generator = outsider,
      strictTime = true,
      stateHash = Some(Some(ByteStr.fill(32)(1))),
      vrfPublicKey = Some(outsiderVrfKey)
    )
    val poorChallenger = TxHelpers.signer(3)
    val challenging = d.createChallengingBlock(
      poorChallenger,
      original,
      // Late enough for its delay, so that only who it is decides
      vrfPublicKey = Some(ByteStr(TxHelpers.vrfKeyOf(poorChallenger).publicKey()))
    )
    d.appender.appendBlockWithoutFallback(challenging).left.value.toString should include(
      s"${poorChallenger.toAddress} can't generate on its own balance"
    )
  }

  "an implicit generator" - {
    "mines its later blocks of the period with the key it committed, after it is persisted" in withDomain(settings, balances) { d =>
      spendAll(d, committed)
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey)))
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true))
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true))

      d.blockchain.implicitGenerators(currentPeriod(d)) shouldBe Seq(ImplicitGenerator(outsider.toAddress, outsiderVrfKey))
    }

    "can't carry a VRF key again in the same period" in withDomain(settings, balances) { d =>
      spendAll(d, committed)
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey)))
      d.appender
        .appendBlockWithoutFallback(d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey)))
        .left
        .value
        .toString should include(
        "already has a VRF public key registered"
      )
    }

    "doesn't keep out another uncommitted generator while no committed one can generate" in withDomain(
      settings,
      AddrWithBalance.enoughBalances(committed, outsider, secondOutsider)
    ) { d =>
      spendAll(d, committed)
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey)))
      val secondVrfKey = ByteStr(TxHelpers.vrfKeyOf(secondOutsider).publicKey())
      d.appender.appendBlock(d.createBlock(generator = secondOutsider, strictTime = true, vrfPublicKey = Some(secondVrfKey)))

      d.blockchain.implicitGenerators(currentPeriod(d)) shouldBe Seq(
        ImplicitGenerator(outsider.toAddress, outsiderVrfKey),
        ImplicitGenerator(secondOutsider.toAddress, secondVrfKey)
      )
    }

    "is committed for its period only" in withDomain(settings, balances) { d =>
      spendAll(d, committed)
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey)))
      val firstPeriod = currentPeriod(d)
      while (d.blockchain.height < firstPeriod.end.toInt) d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true))

      d.appender.appendBlockWithoutFallback(d.createBlock(generator = outsider, strictTime = true)).left.value.toString should include(
        "has to carry the VRF public key it commits with"
      )
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey)))
      currentPeriod(d) should not be firstPeriod
      d.blockchain.implicitGenerators(currentPeriod(d)) shouldBe Seq(ImplicitGenerator(outsider.toAddress, outsiderVrfKey))
    }

    "is gone after a rollback of the block that committed it" in withDomain(settings, balances) { d =>
      spendAll(d, committed)
      val height = d.blockchain.height
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true, vrfPublicKey = Some(outsiderVrfKey)))
      d.appender.appendBlock(d.createBlock(generator = outsider, strictTime = true))

      d.rollbackTo(height)
      d.blockchain.implicitGenerators(currentPeriod(d)) shouldBe empty
    }
  }

  private def currentPeriod(d: Domain) = d.blockchain.generationPeriodOf(Height(d.blockchain.height)).get
}
