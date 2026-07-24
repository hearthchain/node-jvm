package com.wavesplatform.history

import com.wavesplatform.*
import com.wavesplatform.block.{Block, MicroBlock}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.Domain.BlockchainUpdaterExt
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.Transaction
import org.scalacheck.Gen
import org.scalatest.*
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterKeyAndMicroBlockConflictTest
    extends PropSpec
    with DomainScenarioDrivenPropertyCheck
    with OptionValues
    with BlocksTransactionsHelpers {

  property("new key block should be validated to previous") {
    forAll(Preconditions.conflictingTransfers()) { case (richAccount, secondAccount, balances, blockTime, transfer1, transfer2, transfer3) =>
      withDomain(MicroblocksActivatedAt0WavesSettings, balances) { d =>
        val (keyBlock, microBlocks, keyBlock1) =
          Preconditions.chainTransfers(d.lastBlockId, richAccount, secondAccount, blockTime, transfer1, transfer2, transfer3, refByTotalId = false)

        d.blockchainUpdater.processBlock(keyBlock) should beRight

        microBlocks.foreach(d.blockchainUpdater.processMicroBlock(_, None) should beRight)

        d.blockchainUpdater.processBlock(keyBlock1) should beRight
      }
    }

    forAll(Preconditions.conflictingTransfers()) { case (richAccount, secondAccount, balances, blockTime, transfer1, transfer2, transfer3) =>
      withDomain(MicroblocksActivatedAt0WavesSettings, balances) { d =>
        val (keyBlock, microBlocks, keyBlock1) =
          Preconditions.chainTransfers(d.lastBlockId, richAccount, secondAccount, blockTime, transfer1, transfer2, transfer3, refByTotalId = true)

        d.blockchainUpdater.processBlock(keyBlock) should beRight

        microBlocks.foreach(d.blockchainUpdater.processMicroBlock(_, None) should beRight)

        d.blockchainUpdater.processBlock(keyBlock1) should beRight
      }
    }

    forAll(Preconditions.leaseAndLeaseCancel()) {
      case (richAccount, secondAccount, balances, blockTime, lease, leaseCancel, transfer) =>
        withDomain(MicroblocksActivatedAt0WavesSettings, balances) { d =>
          val (leaseBlock, keyBlock, microBlocks, transferBlock) =
            Preconditions.chainLeases(d.lastBlockId, richAccount, secondAccount, blockTime, lease, leaseCancel, transfer)

          Seq(leaseBlock, keyBlock).foreach(d.blockchainUpdater.processBlock(_) should beRight)
          assert(d.blockchainUpdater.effectiveBalance(secondAccount.toAddress, 0) > 0)

          microBlocks.foreach(d.blockchainUpdater.processMicroBlock(_, None) should beRight)
          assert(d.blockchainUpdater.effectiveBalance(secondAccount.toAddress, 0, Some(leaseBlock.id())) > 0)

          assert(d.blockchainUpdater.processBlock(transferBlock).toString.contains("negative effective balance"))
        }
    }
  }

  // The rich account is credited by the genesis snapshot, so the blocks below are chained onto the domain's genesis
  // block. They can only be built once the domain exists, which is why the generators stop at the transactions.
  private object Preconditions {
    import QuickTX.*
    import UnsafeBlocks.*

    type TransfersSetup = (SigningKey, SigningKey, Seq[AddrWithBalance], Long, Transaction, Transaction, Transaction)

    def conflictingTransfers(): Gen[TransfersSetup] = {
      for {
        richAccount   <- accountGen
        secondAccount <- accountGen

        tsAmount = FeeAmount * 10

        blockTime = ntpNow
        transfer1 <- transfer(richAccount, secondAccount.toAddress, tsAmount, validTimestampGen(blockTime))
        transfer2 <- transfer(secondAccount, richAccount.toAddress, tsAmount - FeeAmount, validTimestampGen(blockTime))
        transfer3 <- transfer(secondAccount, richAccount.toAddress, tsAmount - FeeAmount, validTimestampGen(blockTime))
      } yield (
        richAccount,
        secondAccount,
        Seq(AddrWithBalance(richAccount.toAddress, tsAmount + FeeAmount)),
        blockTime,
        transfer1,
        transfer2,
        transfer3
      )
    }

    /** @param refByTotalId
      *   Whether the competing key block references the key block by its total id rather than by its signature
      */
    def chainTransfers(
        genesisId: ByteStr,
        richAccount: SigningKey,
        secondAccount: SigningKey,
        blockTime: Long,
        transfer1: Transaction,
        transfer2: Transaction,
        transfer3: Transaction,
        refByTotalId: Boolean
    ): (Block, Seq[MicroBlock], Block) = {
      val (keyBlock, microBlocks) = unsafeChainBaseAndMicro(
        totalRefTo = genesisId,
        base = Seq(transfer1),
        micros = Seq(Seq(transfer2)),
        signer = richAccount,
        timestamp = blockTime
      )

      val (keyBlock1, _) = unsafeChainBaseAndMicro(
        totalRefTo = if (refByTotalId) keyBlock.id() else keyBlock.signature,
        base = Seq(transfer3),
        micros = Nil,
        signer = secondAccount,
        timestamp = blockTime
      )

      (keyBlock, microBlocks, keyBlock1)
    }

    type LeaseSetup = (SigningKey, SigningKey, Seq[AddrWithBalance], Long, Transaction, Transaction, Transaction)

    def leaseAndLeaseCancel(): Gen[LeaseSetup] = {
      for {
        richAccount   <- accountGen
        secondAccount <- accountGen
        randomAccount <- accountGen

        tsAmount  = FeeAmount * 10
        blockTime = ntpNow
        lease       <- lease(richAccount, secondAccount.toAddress, tsAmount, validTimestampGen(blockTime))
        leaseCancel <- leaseCancel(richAccount, lease.id(), validTimestampGen(blockTime))
        transfer    <- transfer(richAccount, randomAccount.toAddress, tsAmount, validTimestampGen(blockTime))
      } yield (
        richAccount,
        secondAccount,
        Seq(AddrWithBalance(richAccount.toAddress, tsAmount + FeeAmount * 3)),
        blockTime,
        lease,
        leaseCancel,
        transfer
      )
    }

    def chainLeases(
        genesisId: ByteStr,
        richAccount: SigningKey,
        secondAccount: SigningKey,
        blockTime: Long,
        lease: Transaction,
        leaseCancel: Transaction,
        transfer: Transaction
    ): (Block, Block, Seq[MicroBlock], Block) = {
      val leaseBlock = unsafeBlock(
        genesisId,
        Seq(lease),
        richAccount,
        3,
        blockTime
      )

      val (keyBlock, microBlocks) = unsafeChainBaseAndMicro(
        totalRefTo = leaseBlock.signature,
        base = Nil,
        micros = Seq(Seq(leaseCancel)),
        signer = richAccount,
        timestamp = blockTime
      )

      val transferBlock = unsafeBlock(
        keyBlock.signature,
        Seq(transfer),
        secondAccount,
        3,
        blockTime
      )

      (leaseBlock, keyBlock, microBlocks, transferBlock)
    }
  }
}
