package com.wavesplatform.history

import com.wavesplatform.account.Address
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.*
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.history.Domain.BlockchainUpdaterExt
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
    masterToAlice: TransferTransaction <- wavesTransferGeneratorP(ts, master, alice.toAddress)
    aliceToBob  = createWavesTransfer(alice, bob.toAddress, masterToAlice.amount.value - fee - 1, fee, ts).explicitGet()
    aliceToBob2 = createWavesTransfer(alice, bob.toAddress, masterToAlice.amount.value - fee - 1, fee, ts + 1).explicitGet()
  } yield (master, masterToAlice, aliceToBob, aliceToBob2)

  private def fundMaster(s: Setup): Seq[AddrWithBalance] = Seq(AddrWithBalance(s._1.toAddress, ENOUGH_AMT))

  property("all txs in different blocks: B0 <- B1 <- B2 <- B3!") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments, DefaultWavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        val blocks = chainBlocksFrom(domain.lastBlockId, Seq(Seq(masterToAlice), Seq(aliceToBob), Seq(aliceToBob2)))
        blocks.init.foreach(block => domain.blockchainUpdater.processBlock(block) should beRight)
        domain.blockchainUpdater.processBlock(blocks.last) should produce("unavailable funds")

        effBalance(master.toAddress, domain) > 0 shouldBe true
        effBalance(masterToAlice.recipient, domain) shouldBe 0L
        effBalance(aliceToBob.recipient, domain) shouldBe 0L
    }
  }

  property("all txs in one block: B0 <- B0m1 <- B0m2 <- B0m3!") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        val (block, microBlocks) = chainBaseAndMicro(domain.lastBlockId, masterToAlice, Seq(aliceToBob, aliceToBob2).map(Seq(_)))
        domain.blockchainUpdater.processBlock(block) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks(0), None) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks(1), None) should produce("unavailable funds")

        effBalance(master.toAddress, domain) > 0 shouldBe true
        effBalance(masterToAlice.recipient, domain) > 0 shouldBe true
        effBalance(aliceToBob.recipient, domain) > 0 shouldBe true
    }
  }

  property("block references microBlock: B0 <- B1 <- B1m1 <- B2!") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        val (block, microBlocks) = chainBaseAndMicro(domain.lastBlockId, masterToAlice, Seq(aliceToBob, aliceToBob2).map(Seq(_)))
        domain.blockchainUpdater.processBlock(block) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks(0), None) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks(1), None) should produce("unavailable funds")

        effBalance(master.toAddress, domain) should be > 0L
        effBalance(masterToAlice.recipient, domain) should be > 0L
        effBalance(aliceToBob.recipient, domain) should be > 0L
    }
  }

  property("discards some of microBlocks: B0 <- B0m1 <- B0m2; B0m1 <- B1") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        val (block0, microBlocks0) = chainBaseAndMicro(domain.lastBlockId, masterToAlice, Seq(Seq(aliceToBob)))
        val block1                 = buildBlockOfTxs(microBlocks0.head.totalResBlockSig, Seq(aliceToBob2))
        domain.blockchainUpdater.processBlock(block0) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks0(0), None) should beRight
        domain.blockchainUpdater.processBlock(block1) should beRight

        effBalance(master.toAddress, domain) > 0 shouldBe true
        effBalance(masterToAlice.recipient, domain) > 0 shouldBe true
        effBalance(aliceToBob.recipient, domain) shouldBe aliceToBob.amount.value
    }
  }

  property("discards all microBlocks: B0 <- B1 <- B1m1; B1 <- B2") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        val (block1, microBlocks1) = chainBaseAndMicro(domain.lastBlockId, masterToAlice, Seq(Seq(aliceToBob)))
        val block2                 = buildBlockOfTxs(block1.id(), Seq(aliceToBob2))
        domain.blockchainUpdater.processBlock(block1) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks1.head, None) should beRight
        domain.blockchainUpdater.processBlock(block2) should beRight

        effBalance(master.toAddress, domain) > 0 shouldBe true
        effBalance(masterToAlice.recipient, domain) shouldBe 0
        effBalance(aliceToBob.recipient, domain) shouldBe 0
    }
  }

  property("doesn't discard liquid block if competitor is not better: B0 <- B1 <- B1m1; B0 <- B2!") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        val genesisId              = domain.lastBlockId
        val (block1, microBlocks1) = chainBaseAndMicro(genesisId, masterToAlice, Seq(Seq(aliceToBob)))
        val block2                 = buildBlockOfTxs(genesisId, Seq(aliceToBob2), masterToAlice.timestamp)
        domain.blockchainUpdater.processBlock(block1) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks1(0), None) should beRight
        domain.blockchainUpdater.processBlock(block2) should beRight // silently discards worse version

        effBalance(master.toAddress, domain) > 0 shouldBe true
        effBalance(masterToAlice.recipient, domain) shouldBe 1
        effBalance(aliceToBob.recipient, domain) shouldBe aliceToBob.amount.value
    }
  }

  property("discards liquid block if competitor is better: B0 <- B1 <- B1m1; B0 <- B2") {
    scenario(preconditionsAndPayments, MicroblocksActivatedAt0WavesSettings, fundMaster) {
      case (domain, (master, masterToAlice, aliceToBob, aliceToBob2)) =>
        val genesisId              = domain.lastBlockId
        val (block1, microBlocks1) = chainBaseAndMicro(genesisId, masterToAlice, Seq(Seq(aliceToBob)))
        val otherSigner            = SigningKey.fromSeed(Array.fill(KeyLength)(1: Byte))
        val block2 =
          customBuildBlockOfTxs(genesisId, Seq(masterToAlice, aliceToBob2), otherSigner, 1, block1.header.timestamp - 1)
        domain.blockchainUpdater.processBlock(block1) should beRight
        domain.blockchainUpdater.processMicroBlock(microBlocks1(0), None) should beRight
        domain.blockchainUpdater.processBlock(block2) should beRight

        effBalance(master.toAddress, domain) > 0 shouldBe true
        effBalance(masterToAlice.recipient, domain) shouldBe 1
        effBalance(aliceToBob.recipient, domain) shouldBe aliceToBob.amount.value
    }
  }

  property("discarding some of microBlocks doesn't affect resulting state") {
    forAll(preconditionsAndPayments, accountGen) { case (setup @ (_, masterToAlice, aliceToBob, aliceToBob2), miner) =>
      val ts = masterToAlice.timestamp

      val minerABalance = withDomain(MicroblocksActivatedAt0WavesSettings, fundMaster(setup)) { da =>
        val (block1a, microBlocks1a) = chainBaseAndMicro(da.lastBlockId, Seq(masterToAlice), Seq(Seq(aliceToBob)), miner, 3: Byte, ts)
        val block2a                  = customBuildBlockOfTxs(block1a.id(), Seq(aliceToBob2), miner, 3: Byte, ts)
        val block3a                  = customBuildBlockOfTxs(block2a.id(), Seq.empty, miner, 3: Byte, ts)
        da.blockchainUpdater.processBlock(block1a) should beRight
        da.blockchainUpdater.processMicroBlock(microBlocks1a(0), None) should beRight
        da.blockchainUpdater.processBlock(block2a) should beRight
        da.blockchainUpdater.processBlock(block3a) should beRight

        da.balance(miner.toAddress)
      }

      val minerBBalance = withDomain(MicroblocksActivatedAt0WavesSettings, fundMaster(setup)) { db =>
        val block1b = customBuildBlockOfTxs(db.lastBlockId, Seq(masterToAlice), miner, 3: Byte, ts)
        val block2b = customBuildBlockOfTxs(block1b.id(), Seq(aliceToBob2), miner, 3: Byte, ts)
        val block3b = customBuildBlockOfTxs(block2b.id(), Seq.empty, miner, 3: Byte, ts)
        db.blockchainUpdater.processBlock(block1b) should beRight
        db.blockchainUpdater.processBlock(block2b) should beRight
        db.blockchainUpdater.processBlock(block3b) should beRight

        db.balance(miner.toAddress)
      }

      minerABalance shouldBe minerBBalance
    }
  }

  private def effBalance(aa: Address, domain: Domain): Long = aa match {
    case address: Address => domain.effBalance(address)
    case _                => fail("Unexpected address object")
  }
}
