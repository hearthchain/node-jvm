package com.wavesplatform.finalization

import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.Domain
import com.wavesplatform.state.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.test.{NumericExt, produce}
import com.wavesplatform.transaction.{CommitToGenerationTransaction, TxHelpers}

class BlockAppenderAfterFinalizationSpec extends BaseFinalizationSpec {
  protected val committedGenerator1     = TxHelpers.signer(0)
  protected val committedGenerator1Addr = committedGenerator1.toAddress
  protected val committedGenerator1Idx  = GeneratorIndex(0)

  protected val committedGenerator2     = TxHelpers.signer(1)
  protected val committedGenerator2Addr = committedGenerator2.toAddress
  protected val committedGenerator2Idx  = GeneratorIndex(1)

  protected val notCommittedGenerator     = TxHelpers.signer(2)
  protected val notCommittedGeneratorAddr = notCommittedGenerator.toAddress

  private val defaultSettings = DomainPresets.DeterministicFinality

    .configure(
      _.copy(
        generationPeriodLength = 2,
      )
    )

  "should append a block" - {
    "if committed" in new BaseTest {
      override def continue(d: Domain): Unit = {
        log.debug(s"Append block 3 of committed generator")
        val block = d.createBlock(generator = committedGenerator1, strictTime = true)
        d.appender.appendBlock(block)
      }
    }.run()

    "on new period if was conflict on previous" in new BaseTest {
      override def continue(d: Domain): Unit = {
        log.debug(s"Append block 3 with votes")
        val block3WithVotes = d.createBlock(
          generator = committedGenerator2,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().withConflict(committedGenerator1, committedGenerator1Idx, d.lastBlock.id()))
        )
        d.appender.appendBlock(block3WithVotes)

        // Blocks 5 and 6 fall in the next period, and a generator can only mine a period it has committed to, so the
        // commitments have to land while the chain is still in this one.
        log.debug(s"Append block 4 with commitments for the next period")
        d.appender.appendBlock(
          d.createBlock(
            committedGenerators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(5), x)),
            generator = committedGenerator2,
            strictTime = true
          )
        )

        log.debug(s"Append block 5")
        d.appender.appendBlock(d.createBlock(generator = committedGenerator2, strictTime = true))

        log.debug(s"Append new period block of the generator that was in conflict on the previous period")
        val block = d.createBlock(generator = committedGenerator1, strictTime = true)
        d.appender.appendBlock(block)
      }
    }.run()

    "if sent conflicting endorsement in the last microblock, that removed" - {
      "can append a keyblock referencing keyblock" in {
        val committedGenerators = Seq(committedGenerator1, committedGenerator2)

        withDomain(
          defaultSettings.configure(_.copy(generationPeriodLength = 3)),
          AddrWithBalance.enoughBalances(committedGenerators*)
        ) { d =>
          log.debug(s"Append block 2 with commitments")
          val txs = committedGenerators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(4), x))
          d.appender.appendBlock(d.createBlock(txs, generator = committedGenerator1, strictTime = true))

          log.debug(s"Append block 3")
          d.appender.appendBlock(d.createBlock(generator = committedGenerator1, strictTime = true))

          log.debug(s"Append block 4 of new epoch with conflicting endorsement in the last microblock")
          val block4 = d.createBlock(generator = committedGenerator1, strictTime = true)
          d.appender.appendBlock(block4)
          d.appendMicroBlock(
            d.createMicroBlock(
              signer = Some(committedGenerator1),
              finalizationVoting = Some(mkFinalizationVoting().withConflict(committedGenerator2, committedGenerator2Idx, d.lastBlockId))
            )(TxHelpers.transfer(committedGenerator1))
          )

          log.debug(s"Append block 5 of conflicting generator")
          d.appender.appendBlock(
            d.createBlock(ref = Some(block4.id()), generator = committedGenerator2, strictTime = true)
          )

          withClue("Not finalized: ") {
            d.finalizedHeightAtPrevIs(1)
            d.finalizedHeightIs(1)
          }
        }
      }

      "can append a keyblock referencing microblock" in {
        val committedGenerators = Seq(committedGenerator1, committedGenerator2)

        withDomain(
          defaultSettings.configure(_.copy(generationPeriodLength = 3)),
          AddrWithBalance.enoughBalances(committedGenerators*)
        ) { d =>
          log.debug(s"Append block 2 with commitments")
          val txs = committedGenerators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(4), x))
          d.appender.appendBlock(d.createBlock(txs, generator = committedGenerator1, strictTime = true))

          log.debug(s"Append block 3")
          d.appender.appendBlock(d.createBlock(generator = committedGenerator1, strictTime = true))

          log.debug(s"Append block 4 of new epoch with conflicting endorsement in the last microblock")
          d.appender.appendBlock(d.createBlock(generator = committedGenerator1, strictTime = true))
          val block4 = d.appendMicroBlock(d.createMicroBlock(signer = Some(committedGenerator1))(TxHelpers.transfer(committedGenerator1)))
          d.appendMicroBlock(
            d.createMicroBlock(
              signer = Some(committedGenerator1),
              finalizationVoting = Some(mkFinalizationVoting().withConflict(committedGenerator2, committedGenerator2Idx, d.lastBlockId))
            )(TxHelpers.transfer(committedGenerator1))
          )

          log.debug(s"Append block 5 of conflicting generator")
          d.appender.appendBlock(
            d.createBlock(ref = Some(block4), generator = committedGenerator2, strictTime = true)
          )

          withClue("Not finalized: ") {
            d.finalizedHeightAtPrevIs(1)
            d.finalizedHeightIs(1)
          }
        }
      }

      "referencing microblock and forging by another" in {
        val committedGenerators = Seq(committedGenerator1, committedGenerator2)

        withDomain(
          defaultSettings.configure(_.copy(generationPeriodLength = 3)),
          AddrWithBalance.enoughBalances(committedGenerators*)
        ) { d =>
          log.debug(s"Append block 2 with commitments")
          val txs = committedGenerators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(4), x))
          d.appender.appendBlock(d.createBlock(txs, generator = committedGenerator1, strictTime = true))

          log.debug(s"Append block 3")
          d.appender.appendBlock(d.createBlock(generator = committedGenerator1, strictTime = true))

          log.debug(s"Append block 4 of new epoch with conflicting endorsement in the last microblock")
          d.appender.appendBlock(d.createBlock(generator = committedGenerator1, strictTime = true))
          val parentBlockId = d.appendMicroBlock(d.createMicroBlock(signer = Some(committedGenerator1))(TxHelpers.transfer(committedGenerator1)))
          d.appendMicroBlock(
            d.createMicroBlock( // Finalization reached
              signer = Some(committedGenerator1),
              finalizationVoting = Some(mkFinalizationVoting().withConflict(committedGenerator2, committedGenerator2Idx, d.lastBlockId))
            )(TxHelpers.transfer(committedGenerator1))
          )

          log.debug(s"Append block 5 of valid generator")
          d.appender.appendBlock( // Finalization reset
            d.createBlock(ref = Some(parentBlockId), generator = committedGenerator1, strictTime = true)
          )

          withClue("Not finalized: ") {
            d.finalizedHeightAtPrevIs(1)
            d.finalizedHeightIs(1) // Because we need committedGenerator2 balance for finalization
          }
        }
      }
    }

    "if sent LeaseCancel in the last microblock, that removed" in {
      val committedGenerators = Seq(committedGenerator1, committedGenerator2)
      withDomain(
        defaultSettings.configure(
          _.copy(
            generationPeriodLength = 51,
          )
        ),
        AddrWithBalance.enoughBalances(committedGenerator1) :+ AddrWithBalance(
          committedGenerator2Addr,
          CommitToGenerationTransaction.DepositInWavelets + 1.waves
        )
      ) { d =>
        log.debug(s"Append block 2 with leasing")
        val leasingTxn = TxHelpers.lease(committedGenerator1, committedGenerator2Addr, amount = 20_000.waves)
        d.appender.appendBlock(
          d.createBlock(
            txs = Seq(leasingTxn),
            generator = committedGenerator1,
            strictTime = true
          )
        )

        log.debug("Appending [3; 51] blocks")
        (3 to 50).foreach { _ =>
          d.appendBlock(d.createBlock(generator = committedGenerator1, strictTime = true))
        }
        d.appender.appendBlock(
          d.createBlock(generator = committedGenerator1, strictTime = true)
        )

        log.debug("Commit to generation")
        d.appendMicroBlock(
          d.createMicroBlock(signer = Some(committedGenerator1))(
            committedGenerators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(52), x))*
          )
        )
        val block51Id = d.lastBlockId

        log.debug("Cancel leasing for committedGenerator2 in microblock")
        d.appendMicroBlock(d.createMicroBlock(signer = Some(committedGenerator1))(TxHelpers.leaseCancel(leasingTxn.id(), committedGenerator1)))

        log.debug(s"Append block 52 referencing keyblock")
        d.appender.appendBlock(
          d.createBlock(ref = Some(block51Id), generator = committedGenerator2, strictTime = true)
        )
      }
    }
  }

  "should reject a block of generator" - {
    "if not committed" in new BaseTest {
      override def continue(d: Domain): Unit = {
        log.debug(s"Append block 3 of not committed generator")
        val block = d.createBlock(generator = notCommittedGenerator, strictTime = true)
        d.appender.appendBlock(block, requireAppended = false)

        d.blockchain.isLastBlockId(block.id()) shouldBe false
      }
    }.run()

    "if committed in the last microblock, that removed" in {
      val committedGenerators = Seq(committedGenerator1, committedGenerator2)
      val allGenerators       = notCommittedGenerator +: committedGenerators

      withDomain(
        defaultSettings.configure(_.copy(generationPeriodLength = 2)),
        AddrWithBalance.enoughBalances(allGenerators*),
        generators = allGenerators
      ) { d =>
        log.debug(s"Append block 2 with commitments")
        val txs    = committedGenerators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
        val block2 = d.createBlock(txs, generator = notCommittedGenerator, strictTime = true)
        d.appender.appendBlock(block2)
        d.appendMicroBlock(
          d.createMicroBlock(
            signer = Some(notCommittedGenerator)
          )(TxHelpers.commitToGeneration(generationPeriodStart = Height(3), notCommittedGenerator))
        )

        log.debug(s"Append block 3 of not committed generator")
        val newBlock =
          d.createBlock(ref = Some(block2.id()), generator = notCommittedGenerator, strictTime = true)
        d.appender.appendBlock(newBlock, requireAppended = false)
        d.blockchain.isLastBlockId(newBlock.id()) shouldBe false
      }
    }

    "if conflict" in new BaseTest {
      override def continue(d: Domain): Unit = {
        log.debug(s"Append block 3 with votes")
        val block3WithVotes = d.createBlock(
          generator = committedGenerator2,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().withConflict(committedGenerator1, committedGenerator1Idx, d.lastBlock.id()))
        )
        d.appender.appendBlock(block3WithVotes)

        log.debug(s"Append block 4")
        val block = d.createBlock(generator = committedGenerator1, strictTime = true)
        d.appender.appendBlock(block, requireAppended = false)

        d.blockchain.isLastBlockId(block.id()) shouldBe false
      }
    }.run()

    "spent all WAVES" in withDomain(
      defaultSettings,
      AddrWithBalance.enoughBalances(committedGenerator1)
    ) { d =>
      log.debug(s"Append block 2 with commitments")
      val block2 = d.createBlock(
        Seq(TxHelpers.commitToGeneration(Height(3), committedGenerator1)),
        generator = committedGenerator1
      )
      d.appendBlock(block2)

      log.debug(s"Append key block 3")
      d.appender.appendBlock(d.createBlock(generator = committedGenerator1, strictTime = true))

      log.debug(s"Append micro block with spending")
      d.appendMicroBlock(
        d.createMicroBlock(signer = Some(committedGenerator1))(
          TxHelpers.transfer(
            from = committedGenerator1,
            to = notCommittedGeneratorAddr,
            amount = d.blockchain.balance(committedGenerator1Addr) - CommitToGenerationTransaction.DepositInWavelets - 1.waves,
            fee = 1.waves
          )
        )
      )

      log.debug("Append block 4")
      d.appender.appendBlockWithoutFallback(
        d.createBlock(generator = committedGenerator1, strictTime = true)
      ) should produce("less than required for generation")
    }

    "that not committed, if generator set is empty in the removed micro block" in withDomain(
      defaultSettings,
      AddrWithBalance.enoughBalances(committedGenerator1, notCommittedGenerator)
    ) { d =>
      log.debug(s"Append block 2 with commitments")
      val block2 = d.createBlock(
        Seq(TxHelpers.commitToGeneration(Height(3), committedGenerator1)),
        generator = committedGenerator1
      )
      d.appendBlock(block2)

      log.debug(s"Append key block 3")
      d.appender.appendBlock(d.createBlock(generator = committedGenerator1, strictTime = true))
      val keyBlockId = d.lastBlockId

      log.debug(s"Append micro block with spending")
      d.appendMicroBlock(
        d.createMicroBlock(signer = Some(committedGenerator1))(
          TxHelpers.transfer(
            from = committedGenerator1,
            to = notCommittedGeneratorAddr,
            amount = d.blockchain.balance(committedGenerator1Addr) - CommitToGenerationTransaction.DepositInWavelets - 1.waves,
            fee = 1.waves
          )
        )
      )

      log.debug("Append block 4")
      d.appender.appendBlockWithoutFallback(
        d.createBlock(ref = Some(keyBlockId), generator = notCommittedGenerator, strictTime = true)
      ) should produce("is not allowed to generate a block")
    }
  }

  private trait BaseTest {
    protected val committedGenerators = Seq(committedGenerator1, committedGenerator2)
    protected val allGenerators       = notCommittedGenerator +: committedGenerators

    def continue(d: Domain): Unit

    // Genesis commits every generator for period [1, 2]: notCommittedGenerator mines block 2, and the committed ones
    // mine block 3 — the first block of period [3, 4] — whose VRF key PoSSelector resolves at the parent's height.
    def run(): Unit = withDomain(defaultSettings, AddrWithBalance.enoughBalances(allGenerators*), generators = allGenerators) { d =>
      log.debug(s"Append block 2 with commitments")
      val txs                   = committedGenerators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
      val block2WithCommitments = d.createBlock(txs, generator = notCommittedGenerator, strictTime = true)
      d.appender.appendBlock(block2WithCommitments)

      continue(d)
    }
  }
}
