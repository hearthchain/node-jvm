package tech.hearth.api.grpc.test

import com.google.protobuf.ByteString
import tech.hearth.TestValues
import tech.hearth.account.Address
import tech.hearth.api.grpc.*
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.DigestLength
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.protobuf.Amount
import tech.hearth.protobuf.transaction.PBRecipients
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.{BlockRewardCalculator, Height}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.{CommitToGenerationTransaction, TxHelpers}
import tech.hearth.utils.{DiffMatchers, Schedulers}
import monix.execution.ExecutionModel.SynchronousExecution
import monix.execution.Scheduler
import org.scalatest.{Assertion, BeforeAndAfterAll}
import tech.hearth.crypto.SigningKey

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class AccountsApiGrpcSpec extends FreeSpec with BeforeAndAfterAll with DiffMatchers with WithDomain with GrpcApiHelpers {
  private given scheduler: Scheduler = Schedulers.singleThread("grpc", executionModel = SynchronousExecution)

  val sender: SigningKey      = TxHelpers.signer(1)
  val recipient: SigningKey   = TxHelpers.signer(2)
  val timeout: FiniteDuration = 2.minutes

  "GetBalances should work" in {
    val assetTransferAmount   = 123
    val hearthTransferAmount  = 456 + TestValues.fee
    val reverseTransferAmount = 1
    val asset                 = IssuedAsset(ByteStr.fill(32)(1))

    withDomain(
      DomainPresets.RideV6,
      balances = Seq(AddrWithBalance(sender.toAddress, assets = Map(asset -> assetTransferAmount.toLong))),
      assets = Seq(GenesisAssetSettings(asset.id, "asset", 0, assetTransferAmount, TestValues.fee))
    ) { d =>
      val grpcApi = getGrpcApi(d)

      val transfer1 = TxHelpers.transfer(sender, recipient.toAddress, assetTransferAmount, asset)
      val transfer2 = TxHelpers.transfer(sender, recipient.toAddress, hearthTransferAmount, Hearth)
      val transfer3 = TxHelpers.transfer(recipient, sender.toAddress, reverseTransferAmount, Hearth)

      d.appendBlock(transfer1, transfer2, transfer3)

      d.liquidAndSolidAssert { () =>
        val expectedHearthBalance = hearthTransferAmount - TestValues.fee - reverseTransferAmount
        val expectedResult = List(
          BalanceResponse.of(
            BalanceResponse.Balance.Waves(BalanceResponse.WavesBalances(expectedHearthBalance, 0, expectedHearthBalance, expectedHearthBalance))
          ),
          BalanceResponse.of(BalanceResponse.Balance.Asset(Amount(ByteString.copyFrom(asset.id.arr), assetTransferAmount)))
        )

        val (observer1, result1) = createObserver[BalanceResponse]
        grpcApi.getBalances(
          BalancesRequest.of(ByteString.copyFrom(recipient.toAddress.toBytes()), Seq(ByteString.EMPTY, ByteString.copyFrom(asset.id.arr))),
          observer1
        )
        result1.runSyncUnsafe() shouldBe expectedResult

        val (observer2, result2) = createObserver[BalanceResponse]
        grpcApi.getBalances(
          BalancesRequest.of(ByteString.copyFrom(recipient.toAddress.toBytes()), Seq.empty),
          observer2
        )
        result2.runSyncUnsafe() shouldBe expectedResult
      }
    }
  }

  "GetActiveLeases should work" in withDomain(DomainPresets.RideV6, AddrWithBalance.enoughBalances(sender)) { d =>
    val grpcApi = getGrpcApi(d)

    val lease1      = TxHelpers.lease(sender, recipient.toAddress, 123)
    val lease2      = TxHelpers.lease(sender, recipient.toAddress, 456)
    val lease3      = TxHelpers.lease(sender, recipient.toAddress, 789)
    val leaseCancel = TxHelpers.leaseCancel(lease1.id(), sender)

    d.appendBlock(lease1, lease2, lease3)
    d.appendBlock(leaseCancel)

    d.liquidAndSolidAssert { () =>
      val (observer, result) = createObserver[LeaseResponse]

      grpcApi.getActiveLeases(AccountRequest.of(ByteString.copyFrom(recipient.toAddress.toBytes())), observer)

      result.runSyncUnsafe() should contain theSameElementsAs List(
        LeaseResponse.of(
          ByteString.copyFrom(lease3.id().arr),
          ByteString.copyFrom(lease3.id().arr),
          ByteString.copyFrom(sender.toAddress.toBytes()),
          Some(PBRecipients.create(recipient.toAddress)),
          lease3.amount.value,
          2
        ),
        LeaseResponse.of(
          ByteString.copyFrom(lease2.id().arr),
          ByteString.copyFrom(lease2.id().arr),
          ByteString.copyFrom(sender.toAddress.toBytes()),
          Some(PBRecipients.create(recipient.toAddress)),
          lease2.amount.value,
          2
        )
      )
    }
  }

  "NODE-922. GetBalances should return correct balances for challenged and challenging miners" in {
    def checkBalances(
        address: Address,
        expectedRegular: Long,
        expectedAvailable: Long,
        expectedEffective: Long,
        expectedGenerating: Long,
        grpcApi: AccountsApiGrpcImpl
    ): Assertion = {
      val expectedResult = List(
        BalanceResponse.of(
          BalanceResponse.Balance.Waves(BalanceResponse.WavesBalances(expectedRegular, expectedGenerating, expectedAvailable, expectedEffective))
        )
      )

      val (observer, result) = createObserver[BalanceResponse]
      grpcApi.getBalances(
        BalancesRequest.of(ByteString.copyFrom(address.toBytes()), Seq.empty),
        observer
      )
      result.runSyncUnsafe() shouldBe expectedResult
    }

    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val challengingMiner = TxHelpers.signer(3)
    val deposit          = CommitToGenerationTransaction.DepositInEmbers
    withDomain(
      TransactionStateSnapshot,
      generators = Seq(TxHelpers.defaultSigner, challengedMiner, challengingMiner),
      balances = AddrWithBalance.enoughBalances(sender) ++ Seq(
        AddrWithBalance(challengingMiner.toAddress, deposit),
        AddrWithBalance(challengedMiner.toAddress, deposit)
      )
    ) { d =>
      val grpcApi = getGrpcApi(d)

      // net of the deposit, still has to clear GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2 (1000 hearth)
      val initChallengingBalance = 1200.hearth
      val initChallengedBalance  = 2000.hearth

      d.appendBlock(
        TxHelpers.transfer(sender, challengingMiner.toAddress, initChallengingBalance - deposit),
        TxHelpers.transfer(sender, challengedMiner.toAddress, initChallengedBalance - deposit)
      )

      (1 to 999).foreach(_ => d.appendBlock())

      val invalidStateHash = ByteStr.fill(DigestLength)(1)
      val originalBlock = d.createBlock(
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner, originalBlock)

      // regular includes the generation deposit; available/effective/generating don't (see CLAUDE.md "Balance snapshots").
      // A ban zeroes effective/generating (consensus eligibility) but not available (still-spendable balance).
      checkBalances(
        challengingMiner.toAddress,
        initChallengingBalance,
        initChallengingBalance - deposit,
        initChallengingBalance - deposit,
        initChallengingBalance - deposit,
        grpcApi
      )
      checkBalances(
        challengedMiner.toAddress,
        initChallengedBalance,
        initChallengedBalance - deposit,
        initChallengedBalance - deposit,
        initChallengedBalance - deposit,
        grpcApi
      )

      d.appendBlockE(challengingBlock) should beRight

      checkBalances(
        challengingMiner.toAddress,
        initChallengingBalance + getLastBlockMinerReward(d),
        initChallengingBalance + getLastBlockMinerReward(d) - deposit,
        initChallengingBalance + getLastBlockMinerReward(d) - deposit,
        initChallengingBalance - deposit,
        grpcApi
      )
      checkBalances(challengedMiner.toAddress, initChallengedBalance, initChallengedBalance - deposit, 0, 0, grpcApi)

      d.appendBlock()

      checkBalances(
        challengingMiner.toAddress,
        initChallengingBalance + getLastBlockMinerReward(d),
        initChallengingBalance + getLastBlockMinerReward(d) - deposit,
        initChallengingBalance + getLastBlockMinerReward(d) - deposit,
        initChallengingBalance - deposit,
        grpcApi
      )
      checkBalances(challengedMiner.toAddress, initChallengedBalance, initChallengedBalance - deposit, initChallengedBalance - deposit, 0, grpcApi)

    }
  }

  private def getGrpcApi(d: Domain) =
    new AccountsApiGrpcImpl(d.accountsApi)

  private def getLastBlockMinerReward(d: Domain): Long =
    BlockRewardCalculator
      .rewardSharesAt(
        Height(d.blockchain.height),
        d.blockchain.settings.rewardsSettings.initialReward,
        d.blockchain.settings.functionalitySettings.daoAddressParsed.toOption.flatten
      )
      .miner
}
