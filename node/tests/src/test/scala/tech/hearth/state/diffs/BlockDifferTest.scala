package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.block.BlockSnapshot
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.DigestLength
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.lagonaki.mocks.TestBlock
import tech.hearth.mining.MiningConstraint
import tech.hearth.history.{DefaultBlockchainSettings, settings, withFlatReward}
import tech.hearth.state.diffs.BlockDiffer.Result
import tech.hearth.state.{Blockchain, SnapshotBlockchain, StateSnapshot, TxStateSnapshotHashBuilder}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.{TransactionStateSnapshot}
import tech.hearth.test.node.*
import tech.hearth.transaction.TxValidationError.InvalidStateHash
import tech.hearth.transaction.TxHelpers

class BlockDifferTest extends FreeSpec with WithDomain {
  private val TransactionFee = 10

  private val signerA, signerB = randomKeyPair()

  private val master, recipient = randomKeyPair()

  private val InitialMinerBalance = 10000.hearth

  // Zero block reward, so that the only thing moving the miners' balances is the fee distribution under test
  private val noRewardSettings = settings.copy(
    blockchainSettings = DefaultBlockchainSettings.copy(rewardsSettings = withFlatReward(DefaultBlockchainSettings.rewardsSettings, 0))
  )

  private val minerBalances = Seq(
    AddrWithBalance(master.toAddress, 1000000.hearth),
    AddrWithBalance(signerA.toAddress, InitialMinerBalance),
    AddrWithBalance(signerB.toAddress, InitialMinerBalance)
  )

  "BlockDiffer" - {
    "NG fee distribution" - {
      /*
      | N | fee | miner | A receives | A balance | B receives | B balance |
      |--:|:---:|:-----:|-----------:|----------:|-----------:|-----------|
      |1  |0    |-      |0           |0          |0           |0          | <- genesis, no transactions
      |2  |10   |B      |0           |0          |4           |4          |
      |3  |10   |A      |6+4=10      |10         |0           |4          |
      |4  |10   |B      |0           |10         |6+4=10      |14         |
      |5  |10   |A      |6+4=10      |20         |0           |14         |
      |6  |10   |B      |0           |20         |6+4=10      |24         |
      |7  |10   |A      |6+4=10      |30         |0           |24         |
      |8  |10   |B      |0           |30         |6+4=10      |34         |
      |9  |10   |A      |6+4=10      |40         |0           |34         | <- 1st check
      |10 |10   |B      |0           |40         |6+4=10      |44         | <- 2nd check
       */
      "a miner receives 40% of the current block's fees and 60% of the previous block's" in
        withDomain(noRewardSettings, minerBalances, generators = Seq(signerA, signerB)) { d =>
          // Odd heights are mined by A, even ones by B. Every block past the genesis carries a single payment,
          // so at each height the miner collects 40% of its own block and 60% of the one it references.
          def appendPayment(height: Int): Unit = {
            val signer = if (height % 2 == 0) signerB else signerA
            d.appendBlock(d.createBlock(Seq(TxHelpers.transfer(master, recipient.toAddress, 10000, fee = TransactionFee)), generator = signer))
          }

          (2 to 9).foreach(appendPayment)
          d.blockchain.height shouldBe 9
          d.blockchain.balance(signerA.toAddress) shouldBe InitialMinerBalance + 40
          d.blockchain.balance(signerB.toAddress) shouldBe InitialMinerBalance + 34

          appendPayment(10)
          d.blockchain.height shouldBe 10
          d.blockchain.balance(signerA.toAddress) shouldBe InitialMinerBalance + 40
          d.blockchain.balance(signerB.toAddress) shouldBe InitialMinerBalance + 44
        }
    }

    "correctly computes state hash" - {
      // The genesis block has no transactions, so its state hash covers the predefined snapshot built from the settings.
      // withDomain appends it, which only succeeds if BlockDiffer agrees with the state hash the block carries.
      "genesis block" in {
        val balances = (1 to 10).map(idx => AddrWithBalance(TxHelpers.address(idx), 100.hearth))

        val withMiner = AddrWithBalance(TxHelpers.defaultSigner.toAddress) +: balances

        withDomain(TransactionStateSnapshot, withMiner) { d =>
          d.lastBlock.header.stateHash shouldBe defined
          d.lastBlock.transactionData shouldBe empty
          d.blockchain.height shouldBe 1
          balances.foreach { case AddrWithBalance(address, amount, _) => d.blockchain.balance(address) shouldBe amount }
        }

        withDomain(DomainPresets.RideV6, withMiner) { d =>
          d.lastBlock.header.stateHash shouldBe ByteStr.decodeBase16("ab99bdbad4c40a72f782dff360ec7b2c8be19104c15c8b87bf7ae1b62b0b33fe").toOption
          d.blockchain.height shouldBe 1
          balances.foreach { case AddrWithBalance(address, amount, _) => d.blockchain.balance(address) shouldBe amount }
        }
      }

      "arbitrary block/microblock" in
        withDomain(
          TransactionStateSnapshot,
          Seq(AddrWithBalance(TxHelpers.signer(2).toAddress), AddrWithBalance(TxHelpers.address(1))),
          generators = Seq(TxHelpers.signer(2))
        ) { d =>
          val genesis = d.lastBlock

          val txs = (1 to 10).map(idx => TxHelpers.transfer(TxHelpers.signer(idx), TxHelpers.address(idx + 1), (100 - idx).hearth))

          val blockTs    = txs.map(_.timestamp).max
          val signer     = TxHelpers.signer(2)
          val blockchain = SnapshotBlockchain(d.blockchain, Some(d.settings.blockchainSettings.rewardsSettings.initialReward))
          val initSnapshot = BlockDiffer
            .createInitialBlockSnapshot(d.blockchain, d.lastBlock.id(), signer.toAddress, Some(d.lastBlock.signedHeader))
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
              Some(genesis.signedHeader),
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
            Some(genesis.signedHeader),
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
      withDomain(DomainPresets.TransactionStateSnapshot, AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, sender)) { d =>
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

          val block              = d.createBlock(Seq(TxHelpers.transfer(sender, amount = idx.hearth, fee = TestValues.fee * idx)))
          val hs                 = d.posSelector.validateGenerationSignature(block).explicitGet()
          val txValidationResult = BlockDiffer.fromBlock(refBlockchain, Some(liquid.block.signedHeader), block, None, MiningConstraint.Unlimited, hs)

          val txInfo        = txValidationResult.explicitGet().snapshot.transactions.head._2
          val blockSnapshot = BlockSnapshot(block.id(), Seq(txInfo.snapshot -> txInfo.status))

          val snapshotApplyResult =
            BlockDiffer.fromBlock(refBlockchain, Some(liquid.block.signedHeader), block, Some(blockSnapshot), MiningConstraint.Unlimited, hs)

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
        AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, sender, minerAcc),
        generators = Seq(TxHelpers.defaultSigner, minerAcc)
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

}
