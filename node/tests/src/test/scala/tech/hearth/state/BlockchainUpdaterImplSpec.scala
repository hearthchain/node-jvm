package tech.hearth.state

import tech.hearth.history.withFlatReward
import tech.hearth.account.Address
import tech.hearth.block.Block
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.db.{DBCacheSettings, WithDomain}
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.settings.HearthSettings
import tech.hearth.state.appender.BlockAppender
import tech.hearth.state.diffs.ENOUGH_AMT
import tech.hearth.test.*
import tech.hearth.transaction.TxValidationError.BlockAppendError
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.{Transaction, TxHelpers}
import tech.hearth.utils.{Schedulers, SystemTime, Time}
import tech.hearth.{EitherMatchers, NTPTime}
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import tech.hearth.crypto.SigningKey

import scala.concurrent.duration.DurationInt

class BlockchainUpdaterImplSpec extends FreeSpec, EitherMatchers, WithDomain, NTPTime, DBCacheSettings, MockFactory {
  import DomainPresets.*

  private val FEE_AMT = 1000000L

  /** These assertions are about how transaction fees reach the miner, so the block reward is zeroed out to keep it from
    * showing up in the same balances.
    */
  private def withoutReward(ws: HearthSettings): HearthSettings =
    ws.copy(blockchainSettings = ws.blockchainSettings.copy(rewardsSettings = withFlatReward(ws.blockchainSettings.rewardsSettings, 0)))

  /** `setup` yields the transactions of each block rather than the blocks themselves: only the domain can build one
    * that references the chain head *and* carries a VRF proof made with the generator's committed key, so the blocks
    * are built here by `d.appendBlock` instead of by the caller.
    */
  def baseTest(
      setup: Time => (SigningKey, Seq[Seq[Transaction]]),
      enableNg: Boolean = false,
      triggers: BlockchainUpdateTriggers = BlockchainUpdateTriggers.noop
  )(
      f: (CompleteBlockchainUpdater, SigningKey) => Unit
  ): Unit = {
    val master = TxHelpers.signer(1)
    withDomain(withoutReward(if (enableNg) NG else SettingsFromDefaultConfig), AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, master)) { d =>
      // withDomain has already appended the genesis block by now, so the triggers below only see the blocks from `setup`
      d.triggers = d.triggers :+ triggers

      val (account, blocks) = setup(ntpTime)

      blocks.foreach { txs =>
        d.appendBlock(txs*)
      }

      f(d.blockchainUpdater, account)
    }
  }

  def createTransfer(master: SigningKey, recipient: Address, ts: Long): TransferTransaction =
    TxHelpers.transfer(master, recipient, ENOUGH_AMT / 5, fee = 1000000, timestamp = ts)

  def commonPreconditions(ts: Long): (SigningKey, List[Seq[Transaction]]) = {
    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    val b1 = Seq(
      createTransfer(master, recipient.toAddress, ts + 1),
      createTransfer(master, recipient.toAddress, ts + 2),
      createTransfer(recipient, master.toAddress, ts + 3),
      createTransfer(master, recipient.toAddress, ts + 4),
      createTransfer(master, recipient.toAddress, ts + 5)
    )
    val b2 = Seq(
      createTransfer(master, recipient.toAddress, ts + 11),
      createTransfer(recipient, master.toAddress, ts + 12),
      createTransfer(recipient, master.toAddress, ts + 13),
      createTransfer(recipient, master.toAddress, ts + 14)
    )

    (master, List(b1, b2))
  }

  "blockchain update events sending" - {
    "with NG" - {
      "genesis block and two transfers blocks" in {
        val triggersMock = mock[BlockchainUpdateTriggers]

        inSequence {
          (triggersMock.onProcessBlock)
            .expects(where { (block, snapshot, _, _, bc) =>
              bc.height == 1 &&
              block.transactionData.length == 5 &&
              snapshot.balances.isEmpty // no txs with fee in previous block
            })
            .once()

          (triggersMock.onProcessBlock)
            .expects(where { (block, snapshot, _, _, bc) =>
              bc.height == 2 &&
              block.transactionData.length == 4 &&
              snapshot.balances.size == 1 &&
              // The miner now holds all of the previous block's fees: 40% credited per transaction as that block was
              // applied, the remaining 60% as the carry in this block's initial snapshot. Balances are absolute, and
              // defaultSigner mines every block here, so this is its genesis credit plus those fees.
              snapshot.balances.head._2 == ENOUGH_AMT + FEE_AMT * 5
            })
            .once()
        }

        baseTest(time => commonPreconditions(time.correctedTime()), enableNg = true, triggersMock)((_, _) => ())
      }

      "block, then 2 microblocks, then block referencing previous microblock" in
        withDomain(withoutReward(NG), AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, TxHelpers.signer(1))) { d =>
          def preconditions(ts: Long): Seq[Transaction] = {
            val master    = TxHelpers.signer(1)
            val recipient = TxHelpers.signer(2)

            Seq(
              createTransfer(master, recipient.toAddress, ts + 1),
              createTransfer(master, recipient.toAddress, ts + 2),
              createTransfer(master, recipient.toAddress, ts + 3),
              createTransfer(recipient, master.toAddress, ts + 4),
              createTransfer(master, recipient.toAddress, ts + 5)
            )
          }

          // Real timestamps now that the domain builds the blocks: the appender rejects transactions dated far in the
          // past relative to the previous block.
          val transfers = preconditions(ntpTime.correctedTime())

          // The key block goes in before the mock is attached, so the expectations below start at the first microblock.
          d.appendBlock(transfers.head)
          val microBlock1 = d.createMicroBlock()(transfers(1))

          val triggersMock = mock[BlockchainUpdateTriggers]
          d.triggers = d.triggers :+ triggersMock

          // A reference is a 32-byte block id, not the 64-byte total signature the microblock carries, and the id of
          // the liquid state only exists once the microblock has been applied - so the expectations below read it from
          // here, which is set by the time the calls that use it happen.
          var microBlock1TotalId: ByteStr = ByteStr.empty
          var block2Id: ByteStr           = ByteStr.empty

          inSequence {
            // microblock 1
            (triggersMock.onProcessMicroBlock)
              .expects(where { (microBlock, snapshot, bc, _, _) =>
                bc.height == 2 &&
                microBlock.transactionData.length == 1 &&
                snapshot.balances.isEmpty // no txs with fee in previous block
              })
              .once()

            // microblock 2
            (triggersMock.onProcessMicroBlock)
              .expects(where { (microBlock, snapshot, bc, _, _) =>
                bc.height == 2 &&
                microBlock.transactionData.length == 1 &&
                snapshot.balances.isEmpty // no txs with fee in previous block
              })
              .once()

            // rollback microblock
            (triggersMock.onMicroBlockRollback)
              .expects(where { (_, toId) =>
                toId == microBlock1TotalId
              })
              .once()

            // next keyblock
            (triggersMock.onProcessBlock)
              .expects(where { (block, _, _, _, bc) =>
                bc.height == 2 &&
                block.header.reference == microBlock1TotalId
              })
              .once()

            // microblock 3
            (triggersMock.onProcessMicroBlock)
              .expects(where { (microBlock, _, bc, _, _) =>
                bc.height == 3 && microBlock.reference == block2Id
              })
              .once()
          }

          microBlock1TotalId = d.appendMicroBlock(microBlock1)

          // Built while microBlock1 is still the chain head, so it references that liquid state - appending it after
          // the second microblock is what makes the updater roll that one back.
          val block2 = d.createBlock(Seq(transfers(3)))
          block2Id = block2.id()

          d.appendMicroBlock(d.createMicroBlock()(transfers(2)))
          d.appendBlockE(block2) should beRight // this should remove the second microblock

          d.appendMicroBlock(d.createMicroBlock()(transfers(4)))
          d.blockchainUpdater.shutdown()
        }
    }
  }

  "BlockchainUpdater should replace current liquid block with better one" in {
    val currentBlockSender = TxHelpers.signer(1)
    val anotherBlockSender = TxHelpers.signer(2)

    withDomain(
      ConsensusImprovements,
      AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, currentBlockSender, anotherBlockSender),
      generators = Seq(TxHelpers.defaultSigner, currentBlockSender, anotherBlockSender)
    ) { d =>
      val parent = d.appendBlock()

      val betterBlock  = d.createBlock(generator = anotherBlockSender, ref = Some(parent.id()))
      val currentBlock = d.createBlock(generator = currentBlockSender, ref = Some(parent.id()))
      val worseBlock   = d.createBlock(generator = anotherBlockSender, ref = Some(parent.id()))

      betterBlock.header.timestamp < currentBlock.header.timestamp shouldBe true
      currentBlock.header.timestamp < worseBlock.header.timestamp shouldBe true

      d.appendBlockE(currentBlock) should beRight

      val scheduler = Schedulers.singleThread("appender")
      val appender =
        BlockAppender(d.blockchainUpdater, SystemTime, d.utxPool, d.posSelector, BlockEndorser.Disabled, scheduler, verify = false)(_, None)

      appender(worseBlock).runSyncUnsafe(1.minute) shouldBe Left(
        BlockAppendError(
          s"Competitors liquid block $worseBlock(timestamp=${worseBlock.header.timestamp}) is not better than existing (ng.base $currentBlock(timestamp=${currentBlock.header.timestamp}))",
          worseBlock
        )
      )

      appender(betterBlock).runSyncUnsafe(1.minute) should beRight
      d.lastBlock shouldBe betterBlock
      scheduler.shutdown()
    }
  }
}
