package com.wavesplatform.state.diffs

import com.wavesplatform.account.{Address, AddressScheme, PrivateKey, PublicKey}
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.history.defaultSigner
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.{Constants, FunctionalitySettings, TestFunctionalitySettings, WavesSettings}
import com.wavesplatform.state.*
import com.wavesplatform.state.diffs.ExchangeTransactionDiff.getOrderFeePortfolio
import com.wavesplatform.state.diffs.TransactionDiffer.TransactionValidationError
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxHelpers.defaultAddress
import com.wavesplatform.transaction.TxValidationError.{AccountBalanceError, GenericError}
import com.wavesplatform.transaction.assets.exchange.*
import com.wavesplatform.transaction.assets.exchange.OrderPriceMode.{AssetDecimals, FixedDecimals, Default as DefaultPriceMode}
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.{TestValues, TestWallet, crypto}
import org.scalatest.{EitherValues, Inside}
import tech.hearth.crypto.SigningKey

import scala.concurrent.duration.*
import scala.util.Random

class ExchangeTransactionDiffTest extends PropSpec with Inside with WithDomain with EitherValues with TestWallet {

  private def wavesPortfolio(amt: Long) = Portfolio.waves(amt)

  // Predefined assets will be implemented later; for now asset-pair IDs are hardcoded.
  private def iasset(n: Int): IssuedAsset = IssuedAsset(ByteStr(Array.fill(32)(n.toByte)))

  val fs: FunctionalitySettings = TestFunctionalitySettings.Enabled

  val fsWithOrderFeature: FunctionalitySettings =
    fs.copy(preActivatedFeatures = fs.preActivatedFeatures)

  val fsOrderMassTransfer: FunctionalitySettings =
    fsWithOrderFeature.copy(preActivatedFeatures = fsWithOrderFeature.preActivatedFeatures)

  val fsWithBlockV5: FunctionalitySettings =
    fsWithOrderFeature.copy(preActivatedFeatures = fsWithOrderFeature.preActivatedFeatures)

  val fsWithRideV6: FunctionalitySettings =
    fsWithBlockV5.copy(preActivatedFeatures = fsWithBlockV5.preActivatedFeatures)


  property("Preserves waves invariant, stores match info, rewards matcher") {

    val preconditionsAndExchange: Seq[(Seq[AddrWithBalance], ExchangeTransaction)] = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)

      for {
        maybeAsset1 <- Seq(Some(ByteStr(new Array[Byte](32))), None)
        maybeAsset2 <- Seq(Some(ByteStr(new Array[Byte](32))), None) if maybeAsset1 != maybeAsset2
        exchange <- Seq(
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(
              OrderType.BUY,
              Asset.fromCompatId(maybeAsset1),
              Asset.fromCompatId(maybeAsset2),
              sender = buyer,
              matcher = matcher,
              version = Order.V1
            ),
            TxHelpers.order(
              OrderType.SELL,
              Asset.fromCompatId(maybeAsset1),
              Asset.fromCompatId(maybeAsset2),
              sender = seller,
              matcher = matcher,
              version = Order.V1
            ),
            matcher,
            
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(
              OrderType.BUY,
              Asset.fromCompatId(maybeAsset1),
              Asset.fromCompatId(maybeAsset2),
              sender = buyer,
              matcher = matcher,
              version = Order.V1
            ),
            TxHelpers.order(
              OrderType.SELL,
              Asset.fromCompatId(maybeAsset1),
              Asset.fromCompatId(maybeAsset2),
              sender = seller,
              matcher = matcher,
              version = Order.V1
            ),
            matcher,
            
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(
              OrderType.BUY,
              Asset.fromCompatId(maybeAsset1),
              Asset.fromCompatId(maybeAsset2),
              sender = buyer,
              matcher = matcher,
              version = Order.V2
            ),
            TxHelpers.order(
              OrderType.SELL,
              Asset.fromCompatId(maybeAsset1),
              Asset.fromCompatId(maybeAsset2),
              sender = seller,
              matcher = matcher,
              version = Order.V2
            ),
            matcher,
            
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(
              OrderType.BUY,
              Asset.fromCompatId(maybeAsset1),
              Asset.fromCompatId(maybeAsset2),
              sender = buyer,
              matcher = matcher,
              version = Order.V3
            ),
            TxHelpers.order(
              OrderType.SELL,
              Asset.fromCompatId(maybeAsset1),
              Asset.fromCompatId(maybeAsset2),
              sender = seller,
              matcher = matcher,
              version = Order.V3
            ),
            matcher,
            
          )
        )
      } yield (genesis, exchange)
    }

    preconditionsAndExchange.foreach { case (genesis, exchange) =>
      withDomain(ScriptsAndSponsorship, genesis) { d =>
        d.appendBlock(exchange)
        d.liquidSnapshot.balances.toSeq
          .map {
            case ((`defaultAddress`, Waves), amount) =>
              val carryFee = -exchange.fee.value * 3 / 5
              Waves -> (amount - d.rocksDBWriter.balance(defaultAddress, Waves) - carryFee)
            case ((address, asset), amount) =>
              asset -> (amount - d.rocksDBWriter.balance(address, asset))
          }
          .groupMap(_._1)(_._2)
          .foreach { case (_, balances) => balances.sum shouldBe 0 }
        d.liquidSnapshot.balances((exchange.sender.toAddress, Waves)) shouldBe
          d.rocksDBWriter.balance(exchange.sender.toAddress) + exchange.buyMatcherFee + exchange.sellMatcherFee - exchange.fee.value
      }
    }
  }

  property("Preserves assets invariant (matcher's fee in one of the assets of the pair or in Waves), stores match info, rewards matcher") {
    val preconditionsAndExchange: Seq[(Seq[AddrWithBalance], ExchangeTransaction)] = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)
      val asset1  = iasset(1)
      val asset2  = iasset(2)

      (for {
        maybeAsset1           <- Seq(Some(asset1.id), None).map(Asset.fromCompatId)
        maybeAsset2           <- Seq(Some(asset2.id), None).map(Asset.fromCompatId) if maybeAsset1.compatId != maybeAsset2.compatId
        buyMatcherFeeAssetId  <- Seq(maybeAsset1, maybeAsset2)
        sellMatcherFeeAssetId <- Seq(maybeAsset1, maybeAsset2)
        exchange <- Seq(
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, maybeAsset2, maybeAsset1, amount = 100000000, sender = buyer, matcher = matcher, version = Order.V1),
            TxHelpers.order(OrderType.SELL, maybeAsset2, maybeAsset1, amount = 100000000, sender = seller, matcher = matcher, version = Order.V1),
            matcher,
            
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, maybeAsset2, maybeAsset1, amount = 100000000, sender = buyer, matcher = matcher, version = Order.V2),
            TxHelpers.order(OrderType.SELL, maybeAsset2, maybeAsset1, amount = 100000000, sender = seller, matcher = matcher, version = Order.V2),
            matcher,
            
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(
              OrderType.BUY,
              maybeAsset2,
              maybeAsset1,
              feeAsset = buyMatcherFeeAssetId,
              amount = 100000000,
              sender = buyer,
              matcher = matcher,
              version = Order.V3
            ),
            TxHelpers.order(
              OrderType.SELL,
              maybeAsset2,
              maybeAsset1,
              feeAsset = sellMatcherFeeAssetId,
              amount = 100000000,
              sender = seller,
              matcher = matcher,
              version = Order.V3
            ),
            matcher,
            
          )
        )
      } yield (genesis, exchange)).distinct
    }

    preconditionsAndExchange.foreach { case (genesis, exchange) =>
      withDomain(ScriptsAndSponsorship, genesis) { d =>
        d.appendBlock(exchange)
        d.liquidSnapshot.balances.toSeq
          .map {
            case ((`defaultAddress`, Waves), amount) =>
              val carryFee = -exchange.fee.value * 3 / 5
              Waves -> (amount - d.rocksDBWriter.balance(defaultAddress, Waves) - carryFee)
            case ((address, asset), amount) =>
              asset -> (amount - d.rocksDBWriter.balance(address, asset))
          }
          .groupMap(_._1)(_._2)
          .foreach { case (_, balances) => balances.sum shouldBe 0 }

        val sender = exchange.sender.toAddress
        val expectedMatcherPortfolio =
          Seq(
            ExchangeTransactionDiff.getOrderFeePortfolio(exchange.buyOrder, exchange.buyMatcherFee),
            ExchangeTransactionDiff.getOrderFeePortfolio(exchange.sellOrder, exchange.sellMatcherFee),
            wavesPortfolio(-exchange.fee.value)
          ).fold(Portfolio())(_.combine(_).explicitGet())

        d.liquidSnapshot.balances.collect {
          case ((`sender`, Waves), balance) =>
            balance - d.rocksDBWriter.balance(sender) shouldBe expectedMatcherPortfolio.balance
          case ((`sender`, asset: IssuedAsset), balance) =>
            balance - d.rocksDBWriter.balance(sender, asset) shouldBe expectedMatcherPortfolio.assets(asset)
        }
      }
    }
  }

  property("Validation fails when received amount of asset is less than fee in that asset (Orders V3 are used)") {
    val preconditionsAndExchange: (Seq[AddrWithBalance], ExchangeTransaction) = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis           = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)
      val buyerIssuedAsset  = iasset(1)
      val sellerIssuedAsset = iasset(2)

      val exchange = TxHelpers.exchangeFromOrders(
        TxHelpers.order(
          OrderType.BUY,
          sellerIssuedAsset,
          buyerIssuedAsset,
          feeAsset = sellerIssuedAsset,
          fee = 10,
          sender = buyer,
          matcher = matcher,
          version = Order.V3
        ),
        TxHelpers.order(
          OrderType.SELL,
          sellerIssuedAsset,
          buyerIssuedAsset,
          feeAsset = buyerIssuedAsset,
          sender = seller,
          matcher = matcher,
          version = Order.V3
        ),
        matcher,
        
      )

      (genesis, exchange)
    }

    val (genesis, exchange) = preconditionsAndExchange
    assertDiffEi(Seq(TestBlock.create(Seq())), TestBlock.create(Seq(exchange)), fsWithOrderFeature, genesis) { blockDiffEi =>
      blockDiffEi should produce("negative asset balance")
    }
  }

  property("Preserves assets invariant (matcher's fee in separately issued asset), stores match info, rewards matcher (Orders V3 are used)") {
    val preconditionsAndExchange = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)

      for {
        maybeAsset1 <- Seq(Some(iasset(1).id), None).map(Asset.fromCompatId)
        maybeAsset2 <- Seq(Some(iasset(2).id), None).map(Asset.fromCompatId) if maybeAsset1.compatId != maybeAsset2.compatId
      } yield {
        val buyMatcherFeeAssetId  = iasset(3)
        val sellMatcherFeeAssetId = iasset(4)

        val exchange = TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, maybeAsset2, maybeAsset1, feeAsset = buyMatcherFeeAssetId, sender = buyer, matcher = matcher, version = Order.V3),
          TxHelpers.order(
            OrderType.SELL,
            maybeAsset2,
            maybeAsset1,
            feeAsset = sellMatcherFeeAssetId,
            sender = seller,
            matcher = matcher,
            version = Order.V3
          ),
          matcher,
          
        )
        (genesis, exchange)
      }
    }

    preconditionsAndExchange.foreach { case (genesis, exchange) =>
      withDomain(ScriptsAndSponsorship, genesis) { d =>
        d.appendAndAssertSucceed(exchange)

        val carryFee = -exchange.fee.value * 3 / 5
        d.liquidSnapshot.balances.toSeq
          .map {
            case ((`defaultAddress`, Waves), amount) =>
              Waves -> (amount - d.rocksDBWriter.balance(defaultAddress, Waves) - carryFee)
            case ((address, asset), amount) =>
              asset -> (amount - d.rocksDBWriter.balance(address, asset))
          }
          .groupMap(_._1)(_._2)
          .foreach { case (_, balanceDiff) => balanceDiff.sum shouldBe 0 }

        val sender = exchange.sender.toAddress
        val expectedMatcherPortfolio =
          Seq(
            ExchangeTransactionDiff.getOrderFeePortfolio(exchange.buyOrder, exchange.buyMatcherFee),
            ExchangeTransactionDiff.getOrderFeePortfolio(exchange.sellOrder, exchange.sellMatcherFee),
            wavesPortfolio(-exchange.fee.value)
          ).fold(Portfolio())(_.combine(_).explicitGet())

        d.liquidSnapshot.balances.collect {
          case ((`sender`, Waves), balance) =>
            balance - d.rocksDBWriter.balance(sender) shouldBe expectedMatcherPortfolio.balance
          case ((`sender`, asset: IssuedAsset), balance) =>
            balance - d.rocksDBWriter.balance(sender, asset) shouldBe expectedMatcherPortfolio.assets(asset)
        }
      }
    }
  }

  property("Validation fails in case of attempt to pay fee in unissued asset (Orders V3 are used)") {

    val preconditionsAndExchange: Seq[(Seq[AddrWithBalance], ExchangeTransaction)] = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)

      for {
        maybeAsset1 <- Seq(Some(iasset(1).id), None).map(Asset.fromCompatId)
        maybeAsset2 <- Seq(Some(iasset(2).id), None).map(Asset.fromCompatId) if maybeAsset1.compatId != maybeAsset2.compatId
      } yield {
        val matcherFeeAssetId = IssuedAsset(ByteStr.fill(32)(3))

        val exchange = TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, maybeAsset2, maybeAsset1, feeAsset = matcherFeeAssetId, sender = buyer, matcher = matcher, version = Order.V3),
          TxHelpers
            .order(OrderType.SELL, maybeAsset2, maybeAsset1, feeAsset = matcherFeeAssetId, sender = seller, matcher = matcher, version = Order.V3),
          matcher,
          
        )

        (genesis, exchange)
      }
    }

    preconditionsAndExchange.foreach { case (genesis, exchange) =>
      assertDiffEi(Seq(TestBlock.create(Seq())), TestBlock.create(Seq(exchange)), fsWithOrderFeature, genesis) { blockDiffEi =>
        blockDiffEi should produce("AccountBalanceError")
      }
    }
  }

  property(
    "Validation fails when balance of asset issued separately (asset is not in the pair) is less than fee in that asset (Orders V3 are used)"
  ) {

    val preconditionsAndExchange = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis               = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)
      val buyerIssuedAsset      = iasset(1)
      val sellerIssuedAsset     = iasset(2)
      val buyMatcherFeeAssetId  = iasset(3)
      val sellMatcherFeeAssetId = iasset(4)
      val exchange = TxHelpers.exchangeFromOrders(
        TxHelpers.order(
          OrderType.BUY,
          sellerIssuedAsset,
          buyerIssuedAsset,
          feeAsset = buyMatcherFeeAssetId,
          fee = ENOUGH_AMT / 10,
          sender = buyer,
          matcher = matcher,
          version = Order.V3
        ),
        TxHelpers.order(
          OrderType.SELL,
          sellerIssuedAsset,
          buyerIssuedAsset,
          feeAsset = sellMatcherFeeAssetId,
          fee = ENOUGH_AMT / 10,
          sender = seller,
          matcher = matcher,
          version = Order.V3
        ),
        matcher,
        
      )

      (genesis, exchange)
    }

    val (genesis, exchange) = preconditionsAndExchange
    assertDiffEi(
      Seq(TestBlock.create(Seq())),
      TestBlock.create(Seq(exchange)),
      fsWithOrderFeature,
      genesis
    ) { blockDiffEi =>
      blockDiffEi should produce("negative asset balance")
    }
  }

  property("Total matcher's fee (sum of matcher's fees in exchange transactions) is less than or equal to order's matcher fee") {
    val preconditions =
      oneBuyFewSellsPreconditions(
        totalBuyMatcherFeeBoundaries = identity,
        sellersTotalAmount = identity
      )

    val (genesises, massTransfer, exchanges, bigBuyOrder) = preconditions
    withDomain(RideV3, genesises) { d =>
      d.appendBlock(massTransfer)
      d.appendBlock(exchanges*)
      val carryFee = (massTransfer.fee.value - exchanges.map(_.fee.value).sum) * 3 / 5
      d.liquidSnapshot.balances.toSeq
        .map {
          case ((`defaultAddress`, Waves), amount) => Waves -> (amount - d.rocksDBWriter.balance(defaultAddress, Waves) - carryFee)
          case ((address, asset), amount)          => asset -> (amount - d.rocksDBWriter.balance(address, asset))
        }
        .groupMap(_._1)(_._2)
        .foreach { case (_, balanceDiff) => balanceDiff.sum shouldBe 0 }

      val combinedPortfolio =
        exchanges.map(ex => getOrderFeePortfolio(bigBuyOrder, ex.buyMatcherFee)).fold(Portfolio())(_.combine(_).explicitGet())

      val feeSumPaidByBuyer =
        bigBuyOrder.matcherFeeAssetId
          .fold(combinedPortfolio.balance)(combinedPortfolio.assets)

      (feeSumPaidByBuyer <= exchanges.head.buyOrder.matcherFee.value) shouldBe true
    }
  }

  property("Validation fails when total matcher's fee (sum of matcher's fees in exchange transactions) is greater than order's matcher fee") {

    val preconditions =
      oneBuyFewSellsPreconditions(
        totalBuyMatcherFeeBoundaries =
          (bigBuyOrderMatcherFee: Long) => bigBuyOrderMatcherFee + 100000L, // sum of buyMatcherFee in ex trs > specified in bigBuyOrder
        sellersTotalAmount = identity
      )

    val (genesises, massTransfer, exchanges, _) = preconditions
    assertDiffEi(
      Seq(TestBlock.create(Seq()), TestBlock.create(Seq(massTransfer))),
      TestBlock.create(exchanges),
      fsOrderMassTransfer,
      genesises
    ) { blockDiffEi =>
      blockDiffEi should produce("Insufficient buy fee")
    }
  }

  property("Validation fails when total sell amount overfills buy order amount") {

    val preconditions =
      oneBuyFewSellsPreconditions(
        totalBuyMatcherFeeBoundaries = (bigBuyOrderMatcherFee: Long) => bigBuyOrderMatcherFee, // correct total buyMatcherFee in ex trs
        sellersTotalAmount = (bigBuyOrderAmount: Long) => bigBuyOrderAmount + 10000L           // sell orders overfill buy order
      )

    val (genesises, massTransfer, exchanges, _) = preconditions
    assertDiffEi(
      Seq(TestBlock.create(Seq()), TestBlock.create(Seq(massTransfer))),
      TestBlock.create(exchanges),
      fsOrderMassTransfer,
      genesises
    ) { blockDiffEi =>
      blockDiffEi should produce("Too much buy")
    }
  }

  property("buy waves without enough money for fee") {
    val preconditions: Seq[(Seq[AddrWithBalance], ExchangeTransaction)] = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis = Seq(
        AddrWithBalance(buyer.toAddress, 1 * Constants.UnitsInWave),
        AddrWithBalance(seller.toAddress)
      )
      val asset = iasset(1)

      Seq(
        TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, Waves, asset, amount = 100000000L, fee = 300000, sender = buyer, matcher = matcher, version = Order.V1),
          TxHelpers
            .order(OrderType.SELL, Waves, asset, amount = 100000000L, fee = 300000, sender = seller, matcher = matcher, version = Order.V1),
          matcher,
          fee = 300000,
          
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, Waves, asset, amount = 100000000L, fee = 300000, sender = buyer, matcher = matcher, version = Order.V1),
          TxHelpers
            .order(OrderType.SELL, Waves, asset, amount = 100000000L, fee = 300000, sender = seller, matcher = matcher, version = Order.V1),
          matcher,
          fee = 300000,
          
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, Waves, asset, amount = 100000000L, fee = 300000, sender = buyer, matcher = matcher, version = Order.V2),
          TxHelpers
            .order(OrderType.SELL, Waves, asset, amount = 100000000L, fee = 300000, sender = seller, matcher = matcher, version = Order.V2),
          matcher,
          fee = 300000,
          
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, Waves, asset, amount = 100000000L, fee = 300000, sender = buyer, matcher = matcher, version = Order.V3),
          TxHelpers
            .order(OrderType.SELL, Waves, asset, amount = 100000000L, fee = 300000, sender = seller, matcher = matcher, version = Order.V3),
          matcher,
          fee = 300000,
          
        )
      ).map { exchange =>
        (genesis, exchange)
      }
    }

    preconditions.foreach { case (genesis, exchange) =>
      withDomain(ScriptsAndSponsorship, genesis) { d =>
        d.appendBlock(exchange)
        d.liquidSnapshot.balances.toSeq
          .map {
            case ((`defaultAddress`, Waves), amount) =>
              val carryFee = -exchange.fee.value * 3 / 5
              Waves -> (amount - d.rocksDBWriter.balance(defaultAddress, Waves) - carryFee)
            case ((address, asset), amount) =>
              asset -> (amount - d.rocksDBWriter.balance(address, asset))
          }
          .groupMap(_._1)(_._2)
          .foreach { case (_, balanceDiff) => balanceDiff.sum shouldBe 0 }

        d.liquidSnapshot.balances((exchange.sender.toAddress, Waves)) shouldBe
          d.rocksDBWriter.balance(exchange.sender.toAddress, Waves) + exchange.buyMatcherFee + exchange.sellMatcherFee - exchange.fee.value
      }

      assertDiffEi(
        Seq(TestBlock.create(Seq())),
        TestBlock.create(Seq(exchange)),
        fsWithBlockV5,
        genesis
      ) { ei =>
        ei should produce("AccountBalanceError")
      }
    }
  }

  def createExTx(buy: Order, sell: Order, price: Long, matcher: SigningKey): ExchangeTransaction = {
    val mf     = buy.matcherFee.value
    val amount = math.min(buy.amount.value, sell.amount.value)
    TxHelpers.exchange(
      order1 = buy,
      order2 = sell,
      matcher = matcher,
      amount = amount,
      price = price,
      buyMatcherFee = (BigInt(mf) * amount / buy.amount.value).toLong,
      sellMatcherFee = (BigInt(mf) * amount / sell.amount.value).toLong,
      fee = buy.matcherFee.value
    )
  }

  property("small fee cases") {
    val MatcherFee = 1000000L

    val preconditions: (SigningKey, SigningKey, SigningKey, Seq[AddrWithBalance], IssuedAsset) = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller)
      val issue   = iasset(1)

      (buyer, seller, matcher, genesis, issue)
    }

    val (buyer, seller, matcher, genesis, issue) = preconditions
    val buy =
      TxHelpers.order(OrderType.BUY, issue, Waves, amount = 1000000L, fee = MatcherFee, sender = buyer, matcher = matcher, version = Order.V1)
    val sell = TxHelpers.order(OrderType.SELL, issue, Waves, fee = MatcherFee, sender = seller, matcher = matcher, version = Order.V1)
    val tx   = createExTx(buy, sell, buy.price.value, matcher)
    assertDiffAndState(Seq(TestBlock.create(Seq())), TestBlock.create(Seq(tx)), fs, genesis) { case (snapshot, state) =>
      snapshot.balances((tx.sender.toAddress, Waves)) shouldBe tx.buyMatcherFee + tx.sellMatcherFee - tx.fee.value
      state.balance(tx.sender.toAddress) shouldBe 1L
    }
  }

  property("Not enough balance") {
    val MatcherFee = 300000L

    val preconditions: (SigningKey, SigningKey, SigningKey, Seq[AddrWithBalance], IssuedAsset) = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller)
      val issue   = iasset(1)

      (buyer, seller, matcher, genesis, issue)
    }

    val (buyer, seller, matcher, genesis, issue) = preconditions
    val buy = TxHelpers.order(
      OrderType.BUY,
      issue,
      Waves,
      amount = 1000L + 1,
      fee = MatcherFee,
      sender = buyer,
      matcher = matcher,
      version = Order.V1
    )
    val sell = TxHelpers.order(
      OrderType.SELL,
      issue,
      Waves,
      amount = 1000L + 1,
      fee = MatcherFee,
      sender = seller,
      matcher = matcher,
      version = Order.V1
    )
    val tx = createExTx(buy, sell, buy.price.value, matcher)
    assertDiffEi(Seq(TestBlock.create(Seq())), TestBlock.create(Seq(tx)), fsWithOrderFeature, genesis) { snapshotEi =>
      inside(snapshotEi) { case Left(TransactionValidationError(AccountBalanceError(errs), _)) =>
        errs should contain key seller.toAddress
      }
    }
  }

  property("StateSnapshot for ExchangeTransaction works as expected and doesn't use rounding inside") {
    val MatcherFee = 300000L

    val preconditions: (SigningKey, SigningKey, SigningKey, Seq[AddrWithBalance], IssuedAsset) = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)
      val issue   = iasset(1)

      (buyer, seller, matcher, genesis, issue)
    }

    val (buyer, seller, matcher, genesis, issue) = preconditions

    val buy = TxHelpers.order(
      OrderType.BUY,
      Waves,
      issue,
      amount = 3100000000L,
      price = 238,
      fee = MatcherFee,
      sender = buyer,
      matcher = matcher,
      version = Order.V1
    )
    val sell = TxHelpers.order(
      OrderType.SELL,
      Waves,
      issue,
      amount = 425532L,
      price = 235,
      fee = MatcherFee,
      sender = seller,
      matcher = matcher,
      version = Order.V1
    )
    val tx = TxHelpers.exchange(
      order1 = buy,
      order2 = sell,
      matcher = matcher,
      amount = 425532,
      price = 238,
      buyMatcherFee = 41,
      sellMatcherFee = 300000,
      fee = buy.matcherFee.value,
      
    )
    withDomain(ScriptsAndSponsorship, genesis) { d =>
      d.appendBlock(tx)
      d.liquidSnapshot.balances((buyer.toAddress, Waves)) shouldBe d.rocksDBWriter.balance(buyer.toAddress) - 41 + 425532
      d.liquidSnapshot.balances((seller.toAddress, Waves)) shouldBe d.rocksDBWriter.balance(seller.toAddress) - 300000 - 425532
      d.liquidSnapshot.balances((matcher.toAddress, Waves)) shouldBe d.rocksDBWriter.balance(seller.toAddress) + 41 + 300000 - tx.fee.value
    }
  }

  property("ExchangeTransaction invalid if order signature invalid") {
    simpleTradePreconditions.foreach { case (genesis, exchange) =>
      val exchangeWithResignedOrder = (exchange: @unchecked) match {
        case e1 @ ExchangeTransaction(bo, so, _, _, _, _, _, _, _, _) =>
          val newSig = crypto.sign(PrivateKey(so.senderPublicKey.byteStr), bo.bodyBytes())
          e1.copy(order1 = bo.withProofs(Proofs(Seq(newSig))))
        case e2 @ ExchangeTransaction(bo, so, _, _, _, _, _, _, _, _) =>
          val newSig = crypto.sign(PrivateKey(bo.senderPublicKey.byteStr), so.bodyBytes())
          e2.copy(order2 = so.withProofs(Proofs(Seq(newSig))))
      }

      val preconBlocks = Seq(
        TestBlock.create(Seq()) // Height 1: carries the genesis snapshot
      )

      val blockWithExchange = TestBlock.create(Seq(exchangeWithResignedOrder))

      assertLeft(preconBlocks, blockWithExchange, fsWithOrderFeature, genesis)("Proof doesn't validate as signature")
    }
  }

  property("ExchangeTransaction invalid if exchange.price > buyOrder.price or exchange.price < sellOrder.price") {
    val buyer   = TxHelpers.signer(1)
    val seller  = TxHelpers.signer(2)
    val matcher = TxHelpers.signer(3)

    val genesis            = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)
    val baseBlocks         = Seq(TestBlock.create(Seq())) // Height 1: carries the genesis snapshot

    val amountAsset = iasset(2)
    val priceAsset  = iasset(1)

    def mkTestOrder(tpe: OrderType, price: Long, version: Int, priceMode: OrderPriceMode) = TxHelpers.order(
      tpe,
      amountAsset,
      priceAsset,
      price = price,
      sender = buyer,
      matcher = matcher,
      version = version.toByte,
      priceMode = priceMode
    )

    // decimalPrice = 12.5
    val cases = {
      val case1OldTxs = for {
        txVersion <- 1 to 2
        // See ExchangeTxValidator
        ordersVersions = txVersion match {
          case 1 => 1 to 1
          case 2 => 1 to 3
        }

        buyOrderVersion  <- ordersVersions
        sellOrderVersion <- ordersVersions

        r <- Seq(
          // normalizedPrice = decimalPrice * 10^(8 + priceAssetDecimals - amountAssetDecimals) = 12_500_000
          (
            txVersion,
            12_500_000L,
            mkTestOrder(OrderType.BUY, 12_400_000L, buyOrderVersion, OrderPriceMode.Default),
            mkTestOrder(OrderType.SELL, 12_500_000L, sellOrderVersion, OrderPriceMode.Default),
            "exchange.price = 12_500_000 should be <= buyOrder.price = 12_400_000"
          ),
          (
            txVersion,
            12_500_000L,
            mkTestOrder(OrderType.BUY, 12_500_000L, buyOrderVersion, OrderPriceMode.Default),
            mkTestOrder(OrderType.SELL, 12_600_000L, sellOrderVersion, OrderPriceMode.Default),
            "exchange.price = 12_500_000 should be >= sellOrder.price = 12_600_000"
          )
        )
      } yield r

      val case1NewTxs = Seq(
        // normalizedPrice = decimalPrice * 10^(priceAssetDecimals - amountAssetDecimals) = 15
        (
          3,
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 12_400_000L, 4, OrderPriceMode.AssetDecimals),
          mkTestOrder(OrderType.SELL, 12_500_000L, 4, OrderPriceMode.AssetDecimals),
          "exchange.price = 1_250_000_000 should be <= buyOrder.price = 1_240_000_000 (assetDecimals price = 12_400_000)"
        ),
        (
          3,
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 12_500_000L, 4, OrderPriceMode.AssetDecimals),
          mkTestOrder(OrderType.SELL, 12_600_000L, 4, OrderPriceMode.AssetDecimals),
          "exchange.price = 1_250_000_000 should be >= sellOrder.price = 1_260_000_000 (assetDecimals price = 12_600_000)"
        )
      )

      val case2 = Seq(
        // normalizedPrice = decimalPrice * 10^8 = 1_250_000_000
        (
          3,
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 1_240_000_000L, 4, OrderPriceMode.FixedDecimals),
          mkTestOrder(OrderType.SELL, 1_250_000_000L, 4, OrderPriceMode.FixedDecimals),
          "exchange.price = 1_250_000_000 should be <= buyOrder.price = 1_240_000_000"
        ),
        (
          3,
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 1_250_000_000L, 4, OrderPriceMode.FixedDecimals),
          mkTestOrder(OrderType.SELL, 1_260_000_000L, 4, OrderPriceMode.FixedDecimals),
          "exchange.price = 1_250_000_000 should be >= sellOrder.price = 1_260_000_000"
        )
      )

      val mixedCase = Seq(
        (
          3,
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 12_400_000L, 2, OrderPriceMode.Default),
          mkTestOrder(OrderType.SELL, 1_250_000_000L, 4, OrderPriceMode.FixedDecimals),
          "exchange.price = 1_250_000_000 should be <= buyOrder.price = 1_240_000_000 (assetDecimals price = 12_400_000)"
        ),
        (
          3,
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 1_250_000_000L, 4, OrderPriceMode.FixedDecimals),
          mkTestOrder(OrderType.SELL, 12_600_000L, 4, OrderPriceMode.AssetDecimals),
          "exchange.price = 1_250_000_000 should be >= sellOrder.price = 1_260_000_000 (assetDecimals price = 12_600_000)"
        )
      )

      Table(
        ("txVersion", "txPrice", "buyOrder", "sellOrder", "expectedError"),
        (case1OldTxs ++ case2 ++ case1NewTxs ++ mixedCase)*
      )
    }

    forAll(cases) { case (txVersion, txPrice, buyOrder, sellOrder, expectedError) =>
      val exchange = TxHelpers.exchangeFromOrders(
        order1 = buyOrder,
        order2 = sellOrder,
        price = txPrice,
        matcher = matcher,
        fee = TestValues.fee,
        chainId = AddressScheme.current.chainId
      )

      assertDiffEi(baseBlocks, TestBlock.create(Seq(exchange)), fsWithRideV6) { blockDiffEi =>
        blockDiffEi should produce(expectedError)
      }
    }
  }

  property("ExchangeTransaction invalid if order contains more than one proofs") {
    simpleTradePreconditions.foreach { case (genesis, exchange) =>
      val newProofs = Proofs(
        Seq(
          crypto.sign(PrivateKey(exchange.sender.byteStr), exchange.sellOrder.bodyBytes()),
          crypto.sign(PrivateKey(exchange.sellOrder.senderPublicKey.byteStr), exchange.sellOrder.bodyBytes())
        )
      )

      val exchangeWithResignedOrder = (exchange: @unchecked) match {
        case e1 @ ExchangeTransaction( _, so, _, _, _, _, _, _, _, _) =>
          e1.copy(order1 = so.withProofs(newProofs))
        case e2 @ ExchangeTransaction( _, so, _, _, _, _, _, _, _, _) =>
          e2.copy(order1 = so.withProofs(newProofs))
      }

      val preconBlocks = Seq(
        TestBlock.create(Seq()) // Height 1: carries the genesis snapshot
      )

      val blockWithExchange = TestBlock.create(Seq(exchangeWithResignedOrder))

      assertLeft(preconBlocks, blockWithExchange, fsWithOrderFeature, genesis)("Proof doesn't validate as signature")
    }
  }

  property("Legacy price mode is only allowed in Order V4 after RideV6") {
    val matcher = TxHelpers.secondSigner
    val asset   = iasset(1)

    def generateTx(version: TxVersion, mode: OrderPriceMode): ExchangeTransaction = {
      val pair = AssetPair(asset, Waves)

      val buyOrder =
        TxHelpers
          .buy(
            version,
            TxHelpers.secondSigner,
            PublicKey(matcher.publicKey()),
            pair,
            1L,
            1_0000_0000L,
            ntpTime.correctedTime(),
            ntpTime.correctedTime() + 1000,
            TestValues.fee,
            priceMode = mode
          )
          .explicitGet()
      val sellOrder =
        TxHelpers
          .sell(
            version,
            TxHelpers.defaultSigner,
            PublicKey(matcher.publicKey()),
            pair,
            1L,
            1L,
            ntpTime.correctedTime(),
            ntpTime.correctedTime() + 1000,
            TestValues.fee,
            priceMode = mode
          )
          .explicitGet()

      TxHelpers.exchange(
        buyOrder,
        sellOrder,
        matcher,
        1L,
        1L,
        TestValues.fee,
        TestValues.fee,
        TestValues.fee,
        ntpTime.correctedTime(),
        TxVersion.V3
      )
    }

    def generateAndAppendTx(orderVersion: TxVersion, mode: OrderPriceMode, settings: WavesSettings = DomainPresets.RideV5): Unit = {
      withDomain(settings, Seq(AddrWithBalance(TxHelpers.defaultAddress))) { d =>
        d.helpers.creditWavesFromDefaultSigner(TxHelpers.secondAddress)
        d.appendAndAssertSucceed(generateTx(orderVersion, mode))
      }
    }

    intercept[RuntimeException](generateAndAppendTx(Order.V1, FixedDecimals)).getMessage should include("price mode should be default")
    intercept[RuntimeException](generateAndAppendTx(Order.V2, FixedDecimals)).getMessage should include("price mode should be default")
    intercept[RuntimeException](generateAndAppendTx(Order.V3, FixedDecimals)).getMessage should include("price mode should be default")
    intercept[RuntimeException](generateAndAppendTx(Order.V4, AssetDecimals)).getMessage should include(
      "Legacy price mode is only available after RideV6 activation"
    )
    generateAndAppendTx(Order.V4, FixedDecimals, DomainPresets.RideV6)
    generateAndAppendTx(Order.V4, AssetDecimals, DomainPresets.RideV6)
  }

  property("ExchangeTransaction with Orders V4 uses asset decimals for price calculation") {
    val enoughFee = 100000000L
    val buyer     = TxHelpers.signer(1)
    val seller    = TxHelpers.signer(2)
    val matcher   = TxHelpers.signer(3)

    val genesisBalances = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)

    val (preconditions, usdn, tidex, liquid) = {
      val usdn   = iasset(1)
      val tidex  = iasset(2)
      val liquid = iasset(3)

      (Seq(TestBlock.create(Seq())), usdn, tidex, liquid) // Height 1: carries the genesis snapshot
    }

    def mkExchange(
        txv: Byte,
        bov: Byte,
        sov: Byte,
        amount: Long,
        txPrice: Long,
        boPrice: Long,
        boMode: OrderPriceMode,
        soPrice: Long,
        soMode: OrderPriceMode,
        pair: AssetPair
    ): ExchangeTransaction = {
      val buyOrder = TxHelpers.order(
        OrderType.BUY,
        pair.amountAsset,
        pair.priceAsset,
        amount = amount,
        price = boPrice,
        priceMode = boMode,
        sender = buyer,
        matcher = matcher,
        fee = enoughFee,
        version = bov
      )

      val sellOrder = TxHelpers.order(
        OrderType.SELL,
        pair.amountAsset,
        pair.priceAsset,
        amount = amount,
        price = soPrice,
        priceMode = soMode,
        sender = seller,
        matcher = matcher,
        fee = enoughFee,
        version = sov
      )

      TxHelpers.exchange(buyOrder, sellOrder, matcher, amount, txPrice, enoughFee, enoughFee, enoughFee)
    }

    val wavesUsdn   = AssetPair(Waves, usdn)
    val tidexWaves  = AssetPair(tidex, Waves)
    val liquidWaves = AssetPair(liquid, Waves)

    val scenarios = Table(
      (
        "transaction with orders v3",
        "transaction with orders v4 (v3 mode)",
        "transaction with orders v4",
        "transaction with orders v3 and v4 (v3 mode)",
        "transaction with orders v3 and v4",
        "transaction with orders v4 (v3 mode) and v3",
        "transaction with orders v4 and v3"
      ),
      (
        mkExchange(TxVersion.V2, Order.V3, Order.V3, 55768188998L, 592600L, 592600L, DefaultPriceMode, 592600L, DefaultPriceMode, wavesUsdn),
        mkExchange(TxVersion.V3, Order.V4, Order.V4, 55768188998L, 59260000L, 592600L, AssetDecimals, 592600L, AssetDecimals, wavesUsdn),
        mkExchange(TxVersion.V3, Order.V4, Order.V4, 55768188998L, 59260000L, 59260000L, DefaultPriceMode, 59260000L, DefaultPriceMode, wavesUsdn),
        mkExchange(TxVersion.V3, Order.V3, Order.V4, 55768188998L, 59260000L, 592600L, DefaultPriceMode, 592600L, AssetDecimals, wavesUsdn),
        mkExchange(TxVersion.V3, Order.V3, Order.V4, 55768188998L, 59260000L, 592600L, DefaultPriceMode, 59260000L, FixedDecimals, wavesUsdn),
        mkExchange(TxVersion.V3, Order.V4, Order.V3, 55768188998L, 59260000L, 592600L, AssetDecimals, 592600L, DefaultPriceMode, wavesUsdn),
        mkExchange(TxVersion.V3, Order.V4, Order.V3, 55768188998L, 59260000L, 59260000L, FixedDecimals, 592600L, DefaultPriceMode, wavesUsdn)
      ),
      (
        mkExchange(
          TxVersion.V2,
          Order.V3,
          Order.V3,
          213L,
          35016774000000L,
          35016774000000L,
          DefaultPriceMode,
          35016774000000L,
          DefaultPriceMode,
          tidexWaves
        ),
        mkExchange(TxVersion.V3, Order.V4, Order.V4, 213L, 35016774L, 35016774000000L, AssetDecimals, 35016774000000L, AssetDecimals, tidexWaves),
        mkExchange(TxVersion.V3, Order.V4, Order.V4, 213L, 35016774L, 35016774, FixedDecimals, 35016774L, FixedDecimals, tidexWaves),
        mkExchange(TxVersion.V3, Order.V3, Order.V4, 213L, 35016774L, 35016774000000L, DefaultPriceMode, 35016774000000L, AssetDecimals, tidexWaves),
        mkExchange(TxVersion.V3, Order.V3, Order.V4, 213L, 35016774L, 35016774000000L, DefaultPriceMode, 35016774L, FixedDecimals, tidexWaves),
        mkExchange(TxVersion.V3, Order.V4, Order.V3, 213L, 35016774L, 35016774000000L, AssetDecimals, 35016774000000L, DefaultPriceMode, tidexWaves),
        mkExchange(TxVersion.V3, Order.V4, Order.V3, 213L, 35016774L, 35016774L, FixedDecimals, 35016774000000L, DefaultPriceMode, tidexWaves)
      ),
      (
        mkExchange(TxVersion.V2, Order.V3, Order.V3, 2000000000L, 13898832L, 13898832L, DefaultPriceMode, 13898832L, DefaultPriceMode, liquidWaves),
        mkExchange(TxVersion.V3, Order.V4, Order.V4, 2000000000L, 13898832L, 13898832L, AssetDecimals, 13898832L, AssetDecimals, liquidWaves),
        mkExchange(TxVersion.V3, Order.V4, Order.V4, 2000000000L, 13898832L, 13898832L, FixedDecimals, 13898832L, FixedDecimals, liquidWaves),
        mkExchange(TxVersion.V3, Order.V3, Order.V4, 2000000000L, 13898832L, 13898832L, DefaultPriceMode, 13898832L, AssetDecimals, liquidWaves),
        mkExchange(TxVersion.V3, Order.V3, Order.V4, 2000000000L, 13898832L, 13898832L, DefaultPriceMode, 13898832L, FixedDecimals, liquidWaves),
        mkExchange(TxVersion.V3, Order.V4, Order.V3, 2000000000L, 13898832L, 13898832L, AssetDecimals, 13898832L, DefaultPriceMode, liquidWaves),
        mkExchange(TxVersion.V3, Order.V4, Order.V3, 2000000000L, 13898832L, 13898832L, FixedDecimals, 13898832L, DefaultPriceMode, liquidWaves)
      )
    )

    forAll(scenarios) { case (txWithV3, txWithV4AsV3, txWithV4, txWithV3V4AsV3, txWithV3V4, txWithV4AsV3V3, txWithV4V3) =>
      val balances = collection.mutable.ListBuffer[Map[(Address, Asset), Long]]()

      Seq(txWithV3, txWithV4AsV3, txWithV4, txWithV3V4AsV3, txWithV3V4, txWithV4AsV3V3, txWithV4V3)
        .foreach { tx =>
          assertDiffAndState(
            preconditions,
            TestBlock.create(Seq(tx)),
            DomainPresets.RideV6.blockchainSettings.functionalitySettings,
            genesisBalances
          ) { case (snapshot, _) =>
            balances += snapshot.balances
          }
        }

      // all portfolios built on the state and on the composite blockchain are equal
      balances.distinct.size shouldBe 1
    }
  }

  property("ExchangeTransaction V3 can have SELL order as order1 after BlockV5 activation") {
    val scenario = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)
      val asset1  = iasset(1)
      val asset2  = iasset(2)
      val ttx1    = TxHelpers.transfer(matcher, seller.toAddress, ENOUGH_AMT / 2, asset1)
      val ttx2    = TxHelpers.transfer(matcher, buyer.toAddress, ENOUGH_AMT / 2, asset1)
      val ttx3    = TxHelpers.transfer(matcher, seller.toAddress, ENOUGH_AMT / 2, asset2)
      val ttx4    = TxHelpers.transfer(matcher, buyer.toAddress, ENOUGH_AMT / 2, asset2)

      val assets = Seq(asset1, asset2)

      for {
        amountAsset <- assets
        priceAsset  <- assets if priceAsset != amountAsset
        tx <- Seq(
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, amountAsset, priceAsset, sender = buyer, matcher = matcher, version = Order.V1),
            TxHelpers.order(OrderType.SELL, amountAsset, priceAsset, sender = seller, matcher = matcher, version = Order.V1),
            matcher,
            
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, amountAsset, priceAsset, sender = buyer, matcher = matcher, version = Order.V1),
            TxHelpers.order(OrderType.SELL, amountAsset, priceAsset, sender = seller, matcher = matcher, version = Order.V1),
            matcher,
            
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, amountAsset, priceAsset, sender = buyer, matcher = matcher, version = Order.V2),
            TxHelpers.order(OrderType.SELL, amountAsset, priceAsset, sender = seller, matcher = matcher, version = Order.V2),
            matcher,
            
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, amountAsset, priceAsset, sender = buyer, matcher = matcher, version = Order.V3),
            TxHelpers.order(OrderType.SELL, amountAsset, priceAsset, sender = seller, matcher = matcher, version = Order.V3),
            matcher,
            
          )
        )
      } yield {
        val fee = 100000000L
        val fixed = tx
          .copy(
            buyMatcherFee = fee,
            sellMatcherFee = fee,
            fee = TxPositiveAmount.unsafeFrom(fee),
            order1 = { val o = tx.order1.copy(version = Order.V4, matcherFee = TxMatcherFee.unsafeFrom(fee)); o.withProofs(Proofs(ByteStr(buyer.sign(o.bodyBytes())))) },
            order2 = { val o = tx.order2.copy(version = Order.V4, matcherFee = TxMatcherFee.unsafeFrom(fee)); o.withProofs(Proofs(ByteStr(seller.sign(o.bodyBytes())))) },
            proofs = Proofs.empty
          )
          .signWith(matcher)
        val reversed = fixed
          .copy(
            order1 = fixed.order2,
            order2 = fixed.order1,
            proofs = Proofs.empty
          )
          .signWith(matcher)

        (Seq(TestBlock.create(Seq()), TestBlock.create(Seq(ttx1, ttx2, ttx3, ttx4))), fixed, reversed)
      }
    }

    scenario.foreach { case (preconditions, fixed, reversed) =>
      val portfolios = collection.mutable.ListBuffer[Map[(Address, Asset), Long]]()

      assertDiffAndState(preconditions, TestBlock.create(Seq(fixed)), fsWithBlockV5) { case (snapshot, _) =>
        portfolios += snapshot.balances
      }

      assertDiffAndState(preconditions, TestBlock.create(Seq(reversed)), fsWithBlockV5) { case (snapshot, _) =>
        portfolios += snapshot.balances
      }

      portfolios.tail.forall(_ == portfolios.head) shouldBe true
    }
  }

  property("buyMatcherFee/sellMatcherFee validation") {
    val issueFee            = 1.waves
    val exchangeFee         = 0.003.waves
    val matcherStartBalance = issueFee * 2 + exchangeFee
    val buyMatcherFee       = -1
    val sellMatcherFee      = Long.MinValue - buyMatcherFee + exchangeFee

    val sender      = testWallet.generateNewAccount().get
    val priceAsset  = iasset(1)
    val amountAsset = iasset(2)
    val assetPair   = AssetPair(priceAsset, amountAsset)

    def mkOrder(orderType: OrderType): Order =
      TxHelpers.order(
        orderType = orderType,
        amountAsset = assetPair.amountAsset,
        priceAsset = assetPair.priceAsset,
        amount = 1,
        price = 1,
        fee = 100,
        sender = sender,
        matcher = sender,
        timestamp = ntpTime.correctedTime(),
        expiration = ntpTime.correctedTime() + 100000,
        version = 3.toByte
      )

    def mkExchangeTx: ExchangeTransaction =
      TxHelpers.exchange(
        mkOrder(OrderType.BUY),
        mkOrder(OrderType.SELL),
        sender,
        1,
        1,
        buyMatcherFee,
        sellMatcherFee,
        exchangeFee,
      )

    withDomain(DomainPresets.RideV5, Seq(AddrWithBalance(TxHelpers.defaultSigner.toAddress), AddrWithBalance(sender.toAddress, matcherStartBalance))) { d =>
      d.appendBlockE(mkExchangeTx) should produce("Matcher fee can not be negative")
    }
  }

  property(s"NODE-970. Non-empty attachment field is allowed only after LightNode activation") {
    val matcher = TxHelpers.defaultSigner
    val issuer  = TxHelpers.secondSigner

    withDomain(
      ConsensusImprovements,
      AddrWithBalance.enoughBalances(matcher, issuer)
    ) { d =>
      val asset = iasset(1)
      val exchange = () =>
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(OrderType.BUY, Waves, asset, version = Order.V4, attachment = Some(ByteStr.fill(1)(1))),
          TxHelpers.order(OrderType.SELL, Waves, asset, version = Order.V4, sender = TxHelpers.secondSigner),
        )

      d.appendBlockE(exchange()) should produce("Attachment field for orders is not supported yet")
      d.appendBlock()
      d.appendBlockE(exchange()) should beRight
    }
  }

  def script(caseType: String, v: Boolean, complex: Boolean = false): Seq[String] = Seq(true, false).map { full =>
    val expr =
      s"""
         |  strict c = ${if (complex) (1 to 16).map(_ => "sigVerify(base58'', base58'', base58'')").mkString(" || ") else "true"}
         |  match tx {
         |   case _: $caseType => $v
         |   case _ => ${!v}
         |  }
     """.stripMargin
    lazy val contract = s"""
                           |
                           |{-# STDLIB_VERSION 3 #-}
                           |{-# CONTENT_TYPE DAPP #-}
                           |
                           | @Verifier(tx)
                           | func verify() = {
                           | $expr
                           |}
      """.stripMargin

    if (full) contract else expr
  }

  def simpleTradePreconditions: Seq[(Seq[AddrWithBalance], ExchangeTransaction)] = {
    val buyer   = TxHelpers.signer(1)
    val seller  = TxHelpers.signer(2)
    val matcher = TxHelpers.signer(3)

    val genesis = AddrWithBalance.enoughBalances(buyer, seller)

    for {
      maybeAsset1 <- Seq(Some(iasset(1).id), None).map(Asset.fromCompatId)
      maybeAsset2 <- Seq(Some(iasset(2).id), None).map(Asset.fromCompatId) if maybeAsset1.compatId != maybeAsset2.compatId
      exchange <- Seq(
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(OrderType.BUY, maybeAsset1, maybeAsset2, sender = buyer, matcher = matcher, version = Order.V1),
          TxHelpers.order(OrderType.SELL, maybeAsset1, maybeAsset2, sender = seller, matcher = matcher, version = Order.V1),
          matcher,
          
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(OrderType.BUY, maybeAsset1, maybeAsset2, sender = buyer, matcher = matcher, version = Order.V2),
          TxHelpers.order(OrderType.SELL, maybeAsset1, maybeAsset2, sender = seller, matcher = matcher, version = Order.V2),
          matcher,
          
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(OrderType.BUY, maybeAsset1, maybeAsset2, sender = buyer, matcher = matcher, version = Order.V3),
          TxHelpers.order(OrderType.SELL, maybeAsset1, maybeAsset2, sender = seller, matcher = matcher, version = Order.V3),
          matcher,
          
        )
      )
    } yield (genesis, exchange)
  }

  /** Checks whether generated ExchangeTransactionV2 is valid. In case of using orders of version 3 it is possible that matched amount of received
    * asset is less than matcher's fee in that asset. It leads to negative asset balance error
    */
  def transactionWithOrdersV3IsValid(ex: ExchangeTransaction): Boolean = {
    (ex.buyOrder, ex.sellOrder) match {
      case (_: Order, _: Order) =>
        val isBuyerReceiveAmountGreaterThanFee =
          if (ex.buyOrder.assetPair.amountAsset == ex.buyOrder.matcherFeeAssetId) {
            ExchangeTransactionDiff.getReceiveAmount(ex.buyOrder, 8, 8, ex.amount.value, ex.price.value).explicitGet() > ex.buyMatcherFee
          } else true

        val isSellerReceiveAmountGreaterThanFee =
          if (ex.sellOrder.assetPair.amountAsset == ex.sellOrder.matcherFeeAssetId) {
            ExchangeTransactionDiff.getReceiveAmount(ex.sellOrder, 8, 8, ex.amount.value, ex.price.value).explicitGet() > ex.sellMatcherFee
          } else true

        isBuyerReceiveAmountGreaterThanFee && isSellerReceiveAmountGreaterThanFee
    }
  }

  /** Generates sequence of Longs with predefined sum and size */
  def getSeqWithPredefinedSum(sum: Long, count: Int): Seq[Long] = {

    val (lastRemainder, values) = (1 until count)
      .foldLeft((sum, List.empty[Long])) { case ((remainder, result), index) =>
        val next = java.util.concurrent.ThreadLocalRandom.current.nextLong(1, remainder / (count - index))
        (remainder - next) -> (next :: result)
      }

    Random.shuffle(lastRemainder :: values)
  }

  /** Generates sequence of sell orders for one big buy order */
  def sellOrdersForBigBuyOrder(
      matcher: SigningKey,
      sellers: Seq[SigningKey],
      assetPair: AssetPair,
      price: Long,
      matcherFeeAssetId: Asset,
      totalAmount: Long,
      totalMatcherFee: Long
  ): Seq[Order] = {
    val randomAmountsAndFees =
      getSeqWithPredefinedSum(totalAmount, sellers.length) zip getSeqWithPredefinedSum(totalMatcherFee / 10, sellers.length).map(_ * 10)

    val sellers2AmountsAndFees = sellers zip randomAmountsAndFees

    sellers2AmountsAndFees.map { case (seller, (amount, fee)) =>
      TxHelpers.order(
        orderType = OrderType.SELL,
        amountAsset = assetPair.amountAsset,
        priceAsset = assetPair.priceAsset,
        feeAsset = matcherFeeAssetId,
        amount = amount,
        price = price,
        fee = fee,
        sender = seller,
        matcher = matcher
      )
    }
  }

  /** Returns preconditions for tests based on case when there is one big buy order and few small sell orders
    *
    * @param totalBuyMatcherFeeBoundaries
    *   function for manipulating of total matcher's fee paid by buyer in exchange transactions
    * @param sellersTotalAmount
    *   function for manipulating of total sell orders amount
    */
  def oneBuyFewSellsPreconditions(
      totalBuyMatcherFeeBoundaries: Long => Long,
      sellersTotalAmount: Long => Long
  ): (Seq[AddrWithBalance], MassTransferTransaction, Seq[ExchangeTransaction], Order) = {
    val matcher               = TxHelpers.signer(1)
    val sellOrdersCount       = 5
    val sellers               = (1 to 5).map(idx => TxHelpers.signer(idx + 1))
    val buyer                 = TxHelpers.signer(sellOrdersCount + 2)
    val bigBuyOrderAmount     = 3 * 100000L * 100000000L
    val price                 = 3 * 100000L
    val bigBuyOrderMatcherFee = 1000000L

    val asset1 = iasset(1)
    val asset2 = iasset(2)

    val totalBuyMatcherFeeForExchangeTransactions = totalBuyMatcherFeeBoundaries(bigBuyOrderMatcherFee)

    val bigBuyOrder = TxHelpers.order(
      orderType = OrderType.BUY,
      amountAsset = asset2,
      priceAsset = asset1,
      feeAsset = asset1,
      amount = bigBuyOrderAmount,
      price = price,
      fee = bigBuyOrderMatcherFee,
      sender = buyer,
      matcher = matcher
    )

    val sellOrders = sellOrdersForBigBuyOrder(
      matcher = matcher,
      assetPair = AssetPair(asset2, asset1),
      price = price,
      matcherFeeAssetId = asset2,
      sellers = sellers,
      totalAmount = sellersTotalAmount(bigBuyOrderAmount),
      totalMatcherFee = bigBuyOrderMatcherFee
    )

    val genesis = AddrWithBalance.enoughBalances((TxHelpers.defaultSigner +: matcher +: buyer +: sellers)*)

    val massTransfer = TxHelpers.massTransfer(
      from = buyer,
      to = sellers.map(seller => seller.toAddress -> ((Long.MaxValue - 1_000_000) / sellOrdersCount)),
      asset = asset2,
      fee = 1_000_000,
      
    )

    val buyMatcherFees = getSeqWithPredefinedSum(totalBuyMatcherFeeForExchangeTransactions / 10, sellOrdersCount).map(_ * 10)

    val exchanges = (sellOrders zip buyMatcherFees).map { case (sellOrder, buyMatcherFee) =>
      TxHelpers.exchange(
        order1 = bigBuyOrder,
        order2 = sellOrder,
        matcher = matcher,
        amount = sellOrder.amount.value,
        price = bigBuyOrder.price.value,
        buyMatcherFee = buyMatcherFee,
        sellMatcherFee = sellOrder.matcherFee.value,
        fee = (bigBuyOrder.matcherFee.value + sellOrder.matcherFee.value) / 2
      )
    }

    (genesis, massTransfer, exchanges, bigBuyOrder)
  }

}
