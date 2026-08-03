package tech.hearth.api.grpc.test

import com.google.protobuf.ByteString
import tech.hearth.api.grpc.{
  ApplicationStatus,
  TransactionResponse,
  TransactionSnapshotResponse,
  TransactionSnapshotsRequest,
  TransactionsApiGrpcImpl,
  TransactionsRequest
}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base58
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.DigestLength
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.protobuf.transaction.{PBRecipients, PBTransactions}
import tech.hearth.protobuf.{PBSnapshots, toByteString}
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.diffs.ENOUGH_AMT
import tech.hearth.state.{Height, StateSnapshot, TxMeta}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxHelpers.*
import tech.hearth.transaction.assets.exchange.{ExchangeTransaction, Order, OrderType}
import tech.hearth.transaction.{CommitToGenerationTransaction, TxHelpers}
import tech.hearth.utils.{DiffMatchers, Schedulers}
import monix.execution.ExecutionModel.SynchronousExecution
import monix.execution.Scheduler
import org.scalatest.{Assertion, BeforeAndAfterAll}
import tech.hearth.crypto.SigningKey

import scala.collection.immutable.VectorMap

class TransactionsApiGrpcSpec extends FreeSpec with BeforeAndAfterAll with DiffMatchers with WithDomain with GrpcApiHelpers {
  private given scheduler: Scheduler = Schedulers.singleThread("grpc", executionModel = SynchronousExecution)

  val sender: SigningKey    = TxHelpers.signer(1)
  val recipient: SigningKey = TxHelpers.signer(2)

  "GetTransactions should work" in withDomain(DomainPresets.RideV6, AddrWithBalance.enoughBalances(sender)) { d =>
    val grpcApi = getGrpcApi(d)

    val txsWithHeight = (1 to 3).flatMap { idx =>
      val txs = (1 to 10).map { _ =>
        TxHelpers.transfer(sender, recipient.toAddress, 1)
      }
      d.appendBlock(txs*)
      txs.map(_ -> (idx + 1))
    }

    val expectedTxs = txsWithHeight.reverse.map { case (tx, h) =>
      TransactionResponse.of(ByteString.copyFrom(tx.id().arr), h, Some(PBTransactions.protobuf(tx)), ApplicationStatus.SUCCEEDED)
    }

    d.liquidAndSolidAssert { () =>
      val (observer1, result1) = createObserver[TransactionResponse]
      grpcApi.getTransactions(
        TransactionsRequest.of(ByteString.copyFrom(sender.toAddress.toBytes()), None, Seq.empty),
        observer1
      )
      result1.runSyncUnsafe() shouldBe expectedTxs

      val (observer2, result2) = createObserver[TransactionResponse]
      grpcApi.getTransactions(
        TransactionsRequest.of(
          ByteString.EMPTY,
          Some(PBRecipients.create(recipient.toAddress)),
          Seq.empty
        ),
        observer2
      )
      result2.runSyncUnsafe() shouldBe expectedTxs

      val (observer3, result3) = createObserver[TransactionResponse]
      grpcApi.getTransactions(
        TransactionsRequest.of(ByteString.EMPTY, None, txsWithHeight.map { case (tx, _) => ByteString.copyFrom(tx.id().arr) }),
        observer3
      )
      result3.runSyncUnsafe() shouldBe expectedTxs.reverse
    }
  }

  "GetTransactionSnapshots" in {
    // an explicit balance wins over withDomain's auto-fund-the-committed-generator default (ENOUGH_AMT), so
    // defaultSigner's balance below stays exactly (this deposit-sized baseline) + reward + fee, not ENOUGH_AMT + that
    val defaultSignerBalance = 1200.waves
    withDomain(
      TransactionStateSnapshot,
      AddrWithBalance.enoughBalances(secondSigner) :+ AddrWithBalance(defaultAddress, defaultSignerBalance)
    ) { d =>
      val recipient = signer(2).toAddress
      val txs       = Seq.fill(5)(transfer(amount = 1, fee = 100_000, from = secondSigner, to = recipient))

      val firstThreeSnapshots = Seq(
        StateSnapshot(balances =
          VectorMap(
            (secondAddress, Waves)  -> (ENOUGH_AMT - 100_001),
            (recipient, Waves)      -> 1,
            (defaultAddress, Waves) -> (defaultSignerBalance + 400_040_000) // reward (4 waves) and 40% fee
          )
        ),
        StateSnapshot(balances =
          VectorMap(
            (secondAddress, Waves)  -> (ENOUGH_AMT - 200_002),
            (recipient, Waves)      -> 2,
            (defaultAddress, Waves) -> (defaultSignerBalance + 400_080_000)
          )
        ),
        StateSnapshot(balances =
          VectorMap(
            (secondAddress, Waves)  -> (ENOUGH_AMT - 300_003),
            (recipient, Waves)      -> 3,
            (defaultAddress, Waves) -> (defaultSignerBalance + 400_120_000)
          )
        )
      )

      def getSnapshots() = {
        val request              = TransactionSnapshotsRequest.of(txs.map(_.id().toByteString))
        val (observer, response) = createObserver[TransactionSnapshotResponse]
        getGrpcApi(d).getTransactionSnapshots(request, observer)
        response.runSyncUnsafe().flatMap(_.snapshot).map(PBSnapshots.fromProtobuf(_, ByteStr.empty, Height(0))._1)
      }

      d.appendBlock(txs(0), txs(1))
      d.appendMicroBlock(txs(2))

      // both liquid and solid state
      getSnapshots() shouldBe firstThreeSnapshots

      // hardened state
      d.appendBlock(txs(3), txs(4))
      getSnapshots() shouldBe firstThreeSnapshots ++ Seq(
        StateSnapshot(balances =
          VectorMap(
            (secondAddress, Waves) -> (ENOUGH_AMT - 400_004),
            (recipient, Waves)     -> 4,
            (
              defaultAddress,
              Waves
            ) -> (defaultSignerBalance + 800_340_000) // 2 blocks reward (4 waves each), 100% fee from previous block and 40% fee from current
          )
        ),
        StateSnapshot(balances =
          VectorMap(
            (secondAddress, Waves)  -> (ENOUGH_AMT - 500_005),
            (recipient, Waves)      -> 5,
            (defaultAddress, Waves) -> (defaultSignerBalance + 800_380_000)
          )
        )
      )
    }
  }

  "NODE-973. GetTransactions should return correct data for orders with attachment" in {
    def checkOrderAttachment(txResponse: TransactionResponse, expectedAttachment: ByteStr): Assertion = {
      PBTransactions
        .vanilla(txResponse.getTransaction)
        .explicitGet()
        .asInstanceOf[ExchangeTransaction]
        .order1
        .attachment shouldBe Some(expectedAttachment)
    }

    val matcher = TxHelpers.signer(1)
    val issuer  = TxHelpers.signer(2)
    val buyer   = TxHelpers.signer(3)
    val asset   = IssuedAsset(ByteStr.fill(32)(1))
    withDomain(
      DomainPresets.TransactionStateSnapshot,
      balances = AddrWithBalance.enoughBalances(matcher, buyer) :+ AddrWithBalance(issuer.toAddress, assets = Map(asset -> 2L)),
      assets = Seq(GenesisAssetSettings(asset.id, Base58.encode(issuer.publicKey()), "asset", 0, 2L))
    ) { d =>
      val grpcApi = getGrpcApi(d)

      val attachment = ByteStr.fill(32)(1)
      val exchange =
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(
            OrderType.BUY,
            Waves,
            asset,
            amount = 2,
            version = Order.V4,
            sender = buyer,
            matcher = matcher,
            attachment = Some(attachment)
          ),
          TxHelpers.order(OrderType.SELL, Waves, asset, amount = 2, version = Order.V4, sender = issuer, matcher = matcher),
          matcher = matcher
        )

      d.appendBlock(exchange)

      d.liquidAndSolidAssert { () =>
        val (observer1, result1) = createObserver[TransactionResponse]
        grpcApi.getTransactions(
          TransactionsRequest.of(ByteString.copyFrom(exchange.sender.toAddress.toBytes()), None, Seq.empty),
          observer1
        )

        checkOrderAttachment(result1.runSyncUnsafe().head, attachment)

        val (observer2, result2) = createObserver[TransactionResponse]
        grpcApi.getTransactions(
          TransactionsRequest.of(
            ByteString.EMPTY,
            Some(PBRecipients.create(buyer.toAddress)),
            Seq.empty
          ),
          observer2
        )

        checkOrderAttachment(result2.runSyncUnsafe().head, attachment)

        val (observer3, result3) = createObserver[TransactionResponse]
        grpcApi.getTransactions(
          TransactionsRequest.of(ByteString.EMPTY, None, Seq(ByteString.copyFrom(exchange.id().arr))),
          observer3
        )
        checkOrderAttachment(result3.runSyncUnsafe().head, attachment)
      }
    }
  }

  "NODE-922. GetTransactions should return elided transactions" in {
    val sender           = TxHelpers.signer(1)
    val challengedMiner  = TxHelpers.signer(2)
    val resender         = TxHelpers.signer(3)
    val recipient        = TxHelpers.signer(4)
    val challengingMiner = TxHelpers.signer(5)
    val deposit          = CommitToGenerationTransaction.DepositInWavelets
    // enough for a committed generator to still clear GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2
    // (1000 waves) net of its deposit; matches the convention in node/tests' BlockChallengeTest
    val challengerBalance = 1000.waves + deposit
    withDomain(
      TransactionStateSnapshot,
      generators = Seq(TxHelpers.defaultSigner, challengedMiner, challengingMiner),
      balances = AddrWithBalance.enoughBalances(sender) :+ AddrWithBalance(challengingMiner.toAddress, challengerBalance) :+ AddrWithBalance(
        challengedMiner.toAddress,
        challengerBalance
      )
    ) { d =>
      val grpcApi = getGrpcApi(d)

      (1 to 1000).foreach(_ => d.appendBlock())

      val invalidStateHash = ByteStr.fill(DigestLength)(1)
      val resenderTxs = Seq(TxHelpers.transfer(resender, recipient.toAddress, 1.waves), TxHelpers.transfer(resender, recipient.toAddress, 2.waves))
      // affordable when challengedMiner mines it for real (block reward covers the gap) but not once the challenge
      // redirects that reward to challengingMiner instead - which is what makes it (and the resenderTxs relying on
      // its proceeds) become elided rather than making the challenging block unappendable. See BlockChallengeTest's
      // "NODE-899" for the same recipe: build+append a validly-hashed version first, roll back, then challenge it.
      val challengedBlockTx = TxHelpers.transfer(challengedMiner, resender.toAddress, 1001.waves)
      val validOriginalBlock = d.createBlock(
        challengedBlockTx +: resenderTxs,
        strictTime = true,
        generator = challengedMiner
      )
      val invalidOriginalBlock = d.createBlock(
        challengedBlockTx +: resenderTxs,
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner, invalidOriginalBlock)

      d.appendBlockE(validOriginalBlock) should beRight
      d.rollbackTo(validOriginalBlock.header.reference)

      d.appendBlockE(challengingBlock) should beRight
      resenderTxs.foreach { tx =>
        d.transactionsApi.transactionById(tx.id()).map(_.status).contains(TxMeta.Status.Elided) shouldBe true
      }

      val expectedTxs = resenderTxs.reverse.map { tx =>
        TransactionResponse.of(ByteString.copyFrom(tx.id().arr), 1002, Some(PBTransactions.protobuf(tx)), ApplicationStatus.ELIDED)
      }

      d.liquidAndSolidAssert { () =>
        val (observer1, result1) = createObserver[TransactionResponse]
        grpcApi.getTransactions(
          TransactionsRequest.of(ByteString.copyFrom(resender.toAddress.toBytes()), None, Seq.empty),
          observer1
        )
        result1.runSyncUnsafe() shouldBe expectedTxs

        val (observer2, result2) = createObserver[TransactionResponse]
        grpcApi.getTransactions(
          TransactionsRequest.of(
            ByteString.EMPTY,
            Some(PBRecipients.create(recipient.toAddress)),
            Seq.empty
          ),
          observer2
        )
        result2.runSyncUnsafe() shouldBe expectedTxs

        val (observer3, result3) = createObserver[TransactionResponse]
        grpcApi.getTransactions(
          TransactionsRequest.of(ByteString.EMPTY, None, resenderTxs.map { tx => ByteString.copyFrom(tx.id().arr) }),
          observer3
        )
        result3.runSyncUnsafe() shouldBe expectedTxs.reverse
      }
    }
  }

  private def getGrpcApi(d: Domain) =
    new TransactionsApiGrpcImpl(d.blockchain, d.transactionsApi)
}
