package com.wavesplatform.state.diffs

import com.wavesplatform.TestValues
import com.wavesplatform.block.BlockSnapshot
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.DigestLength
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.lagonaki.mocks.TestBlock.BlockWithSigner
import com.wavesplatform.mining.MiningConstraint
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state.diffs.BlockDiffer.Result
import com.wavesplatform.state.{Blockchain, SnapshotBlockchain, StateSnapshot, TxStateSnapshotHashBuilder}
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.test.DomainPresets.{TransactionStateSnapshot}
import com.wavesplatform.test.node.*
import com.wavesplatform.transaction.TxValidationError.InvalidStateHash
import com.wavesplatform.transaction.{Transaction, TxHelpers, TxVersion}
import tech.hearth.crypto.SigningKey

class BlockDifferTest extends FreeSpec with WithDomain {
  private val TransactionFee = 10

  private val signerA, signerB = randomKeyPair()

  private val master, recipient = randomKeyPair()

  // The master is credited by the genesis snapshot, which is applied to the block at height 1
  private val masterBalance: Seq[AddrWithBalance] = Seq(AddrWithBalance(master.toAddress, Long.MaxValue - 1))

  private val testChain: Seq[BlockWithSigner] = getTwoMinersBlockChain(master, recipient, 9)

  "BlockDiffer" - {
    "enableMicroblocksAfterHeight" - {
      /*
      | N | fee | signer | A receive | A balance | B receive | B balance |
      |--:|:---:|:------:|----------:|----------:|----------:|-----------|
      |1  |0    |A       |0          |0          |0          |0          | <- genesis
      |2  |10   |B       |0          |0          |10         |+10        |
      |3  |10   |A       |10         |+10        |0          |0          |
      |4  |10   |B       |0          |10         |+10        |10+10=20   |
      |5  |10   |A       |10         |10+10=20   |0          |20         |
      |6  |10   |B       |0          |20         |+10        |20+10=30   |
      |7  |10   |A       |10         |20+10=30   |0          |30         |
      |8  |10   |B       |0          |30         |+10        |30+10=40   |
      |9  |10   |A       |10         |30+10=40   |0          |40         | <- 1st check
      |10 |10   |B       |0          |40         |+10        |40+10=50   | <- 2nd check
       */
      "height < enableMicroblocksAfterHeight - a miner should receive 100% of the current block's fee" in {
        assertDiff(testChain.init, 1000) { case (_, s) =>
          s.balance(signerA.toAddress) shouldBe 40
        }

        assertDiff(testChain, 1000) { case (_, s) =>
          s.balance(signerB.toAddress) shouldBe 50
        }
      }

      /*
      | N | fee | signer | A receive | A balance | B receive | B balance |
      |--:|:---:|:------:|----------:|----------:|----------:|-----------|
      |1  |0    |A       |0          |0          |0          |0          | <- genesis
      |2  |10   |B       |0          |0          |10         |+10        |
      |3  |10   |A       |10         |+10        |0          |0          |
      |4  |10   |B       |0          |10         |+10        |10+10=20   |
      |5  |10   |A       |10         |10+10=20   |0          |20         |
      |6  |10   |B       |0          |20         |+10        |20+10=30   |
      |7  |10   |A       |10         |20+10=30   |0          |30         |
      |8  |10   |B       |0          |30         |+10        |30+10=40   |
      |9  |10   |A       |10         |30+10=40   |0          |40         |
      |-------------------------- Enable NG -----------------------------|
      |10 |10   |B       |0          |40         |+4         |40+4=44    | <- check
       */
      "height = enableMicroblocksAfterHeight - a miner should receive 40% of the current block's fee only" in {
        assertDiff(testChain, 9) { case (_, s) =>
          s.balance(signerB.toAddress) shouldBe 44
        }
      }

      /*
      | N | fee | signer | A receive | A balance | B receive | B balance |
      |--:|:---:|:------:|----------:|----------:|----------:|-----------|
      |1  |0    |A       |0          |0          |0          |0          | <- genesis
      |2  |10   |B       |0          |0          |10         |+10        |
      |3  |10   |A       |10         |+10        |0          |0          |
      |4  |10   |B       |0          |10         |+10        |10+10=20   |
      |-------------------------- Enable NG -----------------------------|
      |5  |10   |A       |4          |10+4=14    |0          |20         |
      |6  |10   |B       |0          |14         |+4+6=10    |20+10=30   |
      |7  |10   |A       |4+6=10     |14+10=24   |0          |30         |
      |8  |10   |B       |0          |24         |+4+6=10    |30+10=40   |
      |9  |10   |A       |4+6=10     |24+10=34   |0          |40         | <- 1st check
      |10 |10   |B       |0          |34         |+4+6=10    |40+10=50   | <- 2nd check
       */
      "height > enableMicroblocksAfterHeight - a miner should receive 60% of previous block's fee and 40% of the current one" in {
        assertDiff(testChain.init, 4) { case (_, s) =>
          s.balance(signerA.toAddress) shouldBe 34
        }

        assertDiff(testChain, 4) { case (_, s) =>
          s.balance(signerB.toAddress) shouldBe 50
        }
      }
    }

    "correctly computes state hash" - {
      // The genesis block has no transactions, so its state hash covers the predefined snapshot built from the settings.
      // withDomain appends it, which only succeeds if BlockDiffer agrees with the state hash the block carries.
      "genesis block" in {
        val balances = (1 to 10).map(idx => AddrWithBalance(TxHelpers.address(idx), 100.waves))

        withDomain(TransactionStateSnapshot.configure(_.copy(lightNodeBlockFieldsAbsenceInterval = 0)), balances) { d =>
          d.lastBlock.header.stateHash shouldBe defined
          d.lastBlock.transactionData shouldBe empty
          d.blockchain.height shouldBe 1
          balances.foreach { case AddrWithBalance(address, amount, _) => d.blockchain.balance(address) shouldBe amount }
        }

        withDomain(DomainPresets.RideV6, balances) { d =>
          d.lastBlock.header.stateHash shouldBe None
          d.blockchain.height shouldBe 1
          balances.foreach { case AddrWithBalance(address, amount, _) => d.blockchain.balance(address) shouldBe amount }
        }
      }

      "arbitrary block/microblock" in
        withDomain(
          TransactionStateSnapshot.configure(_.copy(lightNodeBlockFieldsAbsenceInterval = 0)),
          Seq(AddrWithBalance(TxHelpers.address(1)))
        ) { d =>
          val genesis = d.lastBlock

          val txs = (1 to 10).map(idx => TxHelpers.transfer(TxHelpers.signer(idx), TxHelpers.address(idx + 1), (100 - idx).waves))

          val blockTs    = txs.map(_.timestamp).max
          val signer     = TxHelpers.signer(2)
          val blockchain = SnapshotBlockchain(d.blockchain, Some(d.settings.blockchainSettings.rewardsSettings.initial))
          val initSnapshot = BlockDiffer
            .createInitialBlockSnapshot(d.blockchain, d.lastBlock.id(), signer.toAddress)
            .explicitGet()
          val initStateHash = TxStateSnapshotHashBuilder.createHashFromSnapshot(initSnapshot, None).createHash(genesis.header.stateHash.get)
          val blockStateHash = TxStateSnapshotHashBuilder
            .computeStateHash(
              txs,
              initStateHash,
              initSnapshot,
              signer,
              d.blockchain.lastBlockTimestamp,
              blockTs,
              isChallenging = false,
              blockchain
            )
            .resultE
            .explicitGet()

          val correctBlock = TestBlock.create(blockTs, genesis.id(), txs, signer, stateHash = Some(blockStateHash))
          BlockDiffer
            .fromBlock(
              blockchain,
              Some(genesis),
              correctBlock.block,
              None,
              MiningConstraint.Unlimited,
              correctBlock.block.header.generationSignature
            ) should beRight

          val incorrectBlock = TestBlock
            .create(blockTs, genesis.id(), txs, signer, stateHash = Some(ByteStr.fill(DigestLength)(1)))
            .block
          BlockDiffer.fromBlock(
            blockchain,
            Some(genesis),
            incorrectBlock,
            None,
            MiningConstraint.Unlimited,
            incorrectBlock.header.generationSignature
          ) shouldBe an[Left[InvalidStateHash, Result]]

          d.appendKeyBlock(signer = signer)
          val correctMicroblock =
            d.createMicroBlock(
              Some(
                TxStateSnapshotHashBuilder
                  .computeStateHash(
                    txs,
                    genesis.header.stateHash.get,
                    StateSnapshot.empty,
                    signer,
                    d.blockchain.lastBlockTimestamp,
                    blockTs,
                    isChallenging = false,
                    blockchain
                  )
                  .resultE
                  .explicitGet()
              )
            )(
              txs*
            )
          BlockDiffer.fromMicroBlock(
            blockchain,
            blockchain.lastBlockTimestamp,
            genesis.header.stateHash.get,
            correctMicroblock,
            None,
            MiningConstraint.Unlimited
          ) should beRight

          val incorrectMicroblock = d.createMicroBlock(Some(ByteStr.fill(DigestLength)(1)))(txs*)
          BlockDiffer.fromMicroBlock(
            blockchain,
            blockchain.lastBlockTimestamp,
            genesis.header.stateHash.get,
            incorrectMicroblock,
            None,
            MiningConstraint.Unlimited
          ) shouldBe an[Left[InvalidStateHash, Result]]
        }
    }

    "result of txs validation should be equal the result of snapshot apply" in {
      val sender = TxHelpers.signer(1)
      withDomain(DomainPresets.TransactionStateSnapshot, AddrWithBalance.enoughBalances(sender)) { d =>
        (1 to 5).map { idx =>
          val liquid = d.liquidState.get.liquidBlockOf(d.lastBlock.id()).get
          val refBlockchain = SnapshotBlockchain(
            d.rocksDBWriter,
            liquid.data.snapshot,
            liquid.block,
            d.liquidState.get.hitSource,
            liquid.data.carryFee,
            d.blockchain.computeNextReward,
            Some(liquid.data.liquidStateHash)
          )

          val block              = d.createBlock(Seq(TxHelpers.transfer(sender, amount = idx.waves, fee = TestValues.fee * idx)))
          val hs                 = d.posSelector.validateGenerationSignature(block).explicitGet()
          val txValidationResult = BlockDiffer.fromBlock(refBlockchain, Some(liquid.block), block, None, MiningConstraint.Unlimited, hs)

          val txInfo        = txValidationResult.explicitGet().snapshot.transactions.head._2
          val blockSnapshot = BlockSnapshot(block.id(), Seq(txInfo.snapshot -> txInfo.status))

          val snapshotApplyResult =
            BlockDiffer.fromBlock(refBlockchain, Some(liquid.block), block, Some(blockSnapshot), MiningConstraint.Unlimited, hs)

          // TODO: remove after NODE-2610 fix
          def clearAffected(r: Result): Result = {
            r.copy(
              snapshot = r.snapshot.copy(transactions = r.snapshot.transactions.map { case (id, info) => id -> info.copy(affected = Set.empty) }),
              keyBlockSnapshot = r.keyBlockSnapshot.copy(transactions = r.keyBlockSnapshot.transactions.map { case (id, info) =>
                id -> info.copy(affected = Set.empty)
              })
            )

          }

          val snapshotApplyResultWithoutAffected = snapshotApplyResult.map(clearAffected)
          val txValidationResultWithoutAffected  = txValidationResult.map(clearAffected)

          snapshotApplyResultWithoutAffected shouldBe txValidationResultWithoutAffected
        }
      }
    }

    "should be possible to append key block that references non-last microblock (NODE-1172)" in {
      val sender   = TxHelpers.signer(1)
      val minerAcc = TxHelpers.signer(2)
      val settings = DomainPresets.TransactionStateSnapshot
      withDomain(
        settings.copy(minerSettings = settings.minerSettings.copy(quorum = 0)),
        AddrWithBalance.enoughBalances(sender, minerAcc),
      ) { d =>
        d.appendBlock()
        d.testTime.setTime(d.lastBlock.header.timestamp)

        d.testTime.advance(d.settings.minerSettings.minMicroBlockAge)
        val refId = d.appendMicroBlock(TxHelpers.transfer(sender, amount = 1))

        d.testTime.advance(d.settings.minerSettings.minMicroBlockAge)
        d.appendMicroBlock(TxHelpers.transfer(sender, amount = 2))

        d.appender.appendBlock(
          d.createBlock(ref = Some(refId), strictTime = true, generator = minerAcc)
        )
      }
    }
  }

  private def assertDiff(blocks: Seq[BlockWithSigner], ngAtHeight: Int)(assertion: (StateSnapshot, Blockchain) => Unit): Unit = {
    val fs = FunctionalitySettings(
      featureCheckBlocksPeriod = ngAtHeight / 2,
      blocksForFeatureActivation = 1,
      preActivatedFeatures = Map[Short, Int]((2, ngAtHeight)),
    )
    assertNgDiffState(blocks.init, blocks.last, fs, masterBalance)(assertion)
  }

  private def getTwoMinersBlockChain(from: SigningKey, to: SigningKey, numPayments: Int): Seq[BlockWithSigner] = {
    val features: Seq[Short] = Seq[Short](2)

    val paymentTxs = (1 to numPayments).map { _ =>
      TxHelpers.transfer(from, to.toAddress, 10000, fee = TransactionFee, version = TxVersion.V1)
    }

    // The block at height 1 is empty: it used to hold the genesis transaction, which the genesis snapshot replaces
    (Seq.empty[Transaction] +: paymentTxs.map(Seq(_))).zipWithIndex.map { case (txs, i) =>
      val signer = if (i % 2 == 0) signerA else signerB
      TestBlock.create(signer, txs, features)
    }
  }
}
