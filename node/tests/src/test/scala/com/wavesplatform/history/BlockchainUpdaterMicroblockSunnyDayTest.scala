package com.wavesplatform.history

import com.wavesplatform.account.Address
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.diffs.*
import com.wavesplatform.test.*
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.transfer.*
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterMicroblockSunnyDayTest extends PropSpec with DomainScenarioDrivenPropertyCheck {

  type Setup = (SigningKey, TransferTransaction, TransferTransaction, TransferTransaction)

  // The master is credited by the genesis snapshot, which the domain applies as its own block at height 1,
  // so the chains below start from that block instead of a fabricated genesis one.
  val preconditionsAndPayments: Gen[Setup] = for {
    master <- accountGen
    alice  <- accountGen
    bob    <- accountGen
    ts     <- positiveIntGen
    fee    <- smallFeeGen
    // More than the fee, or the transfers below would be for a negative amount: Alice has to afford exactly one of them
    amount <- Gen.choose(fee + 2, ENOUGH_AMT / 100)
    masterToAlice: TransferTransaction = createWavesTransfer(master, alice.toAddress, amount, fee, ts).explicitGet()
    aliceToBob  = createWavesTransfer(alice, bob.toAddress, masterToAlice.amount.value - fee - 1, fee, ts).explicitGet()
    aliceToBob2 = createWavesTransfer(alice, bob.toAddress, masterToAlice.amount.value - fee - 1, fee, ts + 1).explicitGet()
  } yield (master, masterToAlice, aliceToBob, aliceToBob2)

  private def fundMaster(s: Setup): Seq[AddrWithBalance] = Seq(AddrWithBalance(s._1.toAddress, ENOUGH_AMT))

  /** A generator of the competing branch. It has to be committed like any other, and it must not be the one the domain
    * mines with, or its block would be a version of the same block rather than a competitor.
    */
  private val competitor: SigningKey = TxHelpers.signer(1001)

  // Its deposit is locked, so a generating balance has to come on top of it - a generator with nothing to generate on
  // is not among the ones a block is allowed to come from
  private def fundMasterAndCompetitor(s: Setup): Seq[AddrWithBalance] =
    fundMaster(s) :+ AddrWithBalance(competitor.toAddress, CommitToGenerationTransaction.DepositInWavelets + 1000.waves)

  private val withoutReward: WavesSettings = {
    val bs = MicroblocksActivatedAt0WavesSettings.blockchainSettings
    MicroblocksActivatedAt0WavesSettings.copy(blockchainSettings = bs.copy(rewardsSettings = bs.rewardsSettings.copy(initial = 0)))
  }

  property("all txs in different blocks: B0 <- B1 <- B2 <- B3!") {
    scenario(preconditionsAndPayments, DefaultWavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        domain.appendBlockAt(masterToAlice.timestamp)(masterToAlice)
        domain.appendBlockAt(aliceToBob.timestamp)(aliceToBob)
        domain.appendBlockAtE(aliceToBob2.timestamp)(aliceToBob2) should produce("negative waves balance")

        effBalance(master.toAddress, domain) > 0 shouldBe true
        effBalance(masterToAlice.recipient, domain) shouldBe 0L
        effBalance(aliceToBob.recipient, domain) shouldBe 0L
    }
  }

  /* The second half of this pair - "block references microBlock: B0 <- B1 <- B1m1 <- B2!" - was a byte-identical copy
   * of the property below and is gone.
   */
  property("all txs in one block: B0 <- B0m1 <- B0m2 <- B0m3!") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        domain.appendBlockAt(masterToAlice.timestamp)(masterToAlice)
        domain.appendMicroBlock(aliceToBob)
        domain.appendMicroBlockE(aliceToBob2) should produce("negative waves balance")

        // effBalance is the minimum over the generating window, so an account credited in the liquid block is still
        // at zero there; what the micro block did shows up in the balances
        effBalance(master.toAddress, domain) > 0 shouldBe true
        domain.balance(masterToAlice.recipient) shouldBe 1L
        domain.balance(aliceToBob.recipient) shouldBe aliceToBob.amount.value
    }
  }

  property("discards some of microBlocks: B0 <- B0m1 <- B0m2; B0m1 <- B1") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        domain.appendBlockAt(masterToAlice.timestamp)(masterToAlice)
        domain.appendMicroBlock(aliceToBob)
        // On top of the micro block, so what it carries stays and the competing transfer cannot be paid for
        domain.appendBlockAtE(aliceToBob2.timestamp)(aliceToBob2) should produce("negative waves balance")

        effBalance(master.toAddress, domain) > 0 shouldBe true
        domain.balance(masterToAlice.recipient) shouldBe 1L
        domain.balance(aliceToBob.recipient) shouldBe aliceToBob.amount.value
    }
  }

  property("discards all microBlocks: B0 <- B1 <- B1m1; B1 <- B2") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        val block1 = domain.appendBlockAt(masterToAlice.timestamp)(masterToAlice)
        // Built while the key block is still the head, so its state hash is the one that branch leads to
        val competing = domain.createBlock(Seq(aliceToBob2), ref = Some(block1.id()), strictTime = true, timestamp = Some(aliceToBob2.timestamp))

        domain.appendMicroBlock(aliceToBob)

        // Referencing the key block discards the micro block, and with it the transfer that would have left Alice
        // unable to pay for this one
        domain.appendBlockE(competing) should beRight

        effBalance(master.toAddress, domain) > 0 shouldBe true
        domain.balance(aliceToBob.recipient) shouldBe aliceToBob2.amount.value
    }
  }

  property("doesn't discard liquid block if competitor is not better: B0 <- B1 <- B1m1; B0 <- B2!") {
    scenario(preconditionsAndPayments, withoutReward, fundMasterAndCompetitor, _ => Seq(defaultSigner, competitor)) {
      case (domain, (_, masterToAlice, aliceToBob, aliceToBob2)) =>
        // Built on the genesis block, while that is still the head: this branch never sees the block below
        val worse = domain.createBlock(
          Seq(masterToAlice, aliceToBob2),
          ref = Some(domain.lastBlockId),
          generator = competitor,
          strictTime = true,
          timestamp = Some(masterToAlice.timestamp + 1)
        )

        domain.appendBlockAt(masterToAlice.timestamp)(masterToAlice)
        domain.appendMicroBlock(aliceToBob)

        // Same parent, later timestamp: not better than the liquid block, so it is turned away
        domain.appendBlockE(worse) should produce("is not better than existing")

        domain.balance(aliceToBob.recipient) shouldBe aliceToBob.amount.value
    }
  }

  property("discards liquid block if competitor is better: B0 <- B1 <- B1m1; B0 <- B2") {
    scenario(preconditionsAndPayments, withoutReward, fundMasterAndCompetitor, _ => Seq(defaultSigner, competitor)) {
      case (domain, (_, masterToAlice, aliceToBob, aliceToBob2)) =>
        val better = domain.createBlock(
          Seq(masterToAlice, aliceToBob2),
          ref = Some(domain.lastBlockId),
          generator = competitor,
          strictTime = true,
          timestamp = Some(masterToAlice.timestamp - 1)
        )

        domain.appendBlockAt(masterToAlice.timestamp)(masterToAlice)
        domain.appendMicroBlock(aliceToBob)

        // Same parent, earlier timestamp: better, so it replaces the liquid block and everything it carried
        domain.appendBlockE(better) should beRight

        domain.balance(aliceToBob.recipient) shouldBe aliceToBob2.amount.value
    }
  }

  property("discarding some of microBlocks doesn't affect resulting state") {
    forAll(preconditionsAndPayments) { case setup @ (_, masterToAlice, aliceToBob, aliceToBob2) =>
      val ts = masterToAlice.timestamp

      // The same transactions, once split across a block and a micro block and once in a single block: what the miner
      // ends up holding must not depend on where they sat
      val minerABalance = withDomain(withoutReward, fundMaster(setup)) { da =>
        da.appendBlockAt(ts)(masterToAlice)
        da.appendMicroBlock(aliceToBob)
        da.appendBlockAt(aliceToBob2.timestamp + 1)()
        da.balance(defaultSigner.toAddress)
      }

      val minerBBalance = withDomain(withoutReward, fundMaster(setup)) { db =>
        db.appendBlockAt(ts)(masterToAlice, aliceToBob)
        db.appendBlockAt(aliceToBob2.timestamp + 1)()
        db.balance(defaultSigner.toAddress)
      }

      minerABalance shouldBe minerBBalance
    }
  }

  private def effBalance(aa: Address, domain: Domain): Long = domain.effBalance(aa)
}
