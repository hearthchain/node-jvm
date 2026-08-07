package tech.hearth.state.diffs

import tech.hearth.account.{Address, AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.lagonaki.mocks.TestBlock
import tech.hearth.lang.ValidationError
import tech.hearth.protobuf.transaction.{PBTransactions, SignedTransaction as PBSignedTransaction}
import tech.hearth.settings.{Constants, FunctionalitySettings, GenesisAssetSettings, TestFunctionalitySettings, WavesSettings}
import tech.hearth.state.*
import tech.hearth.state.diffs.ExchangeTransactionDiff.getOrderFeePortfolio
import tech.hearth.state.diffs.TransactionDiffer.TransactionValidationError
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxHelpers.defaultAddress
import tech.hearth.transaction.TxValidationError.AccountBalanceError
import tech.hearth.transaction.assets.exchange.*
import tech.hearth.transaction.assets.exchange.OrderPriceMode.{AssetDecimals, FixedDecimals, Default as DefaultPriceMode}
import tech.hearth.transaction.transfer.MassTransferTransaction
import tech.hearth.{TestValues, TestWallet}
import org.scalatest.{EitherValues, Inside}
import tech.hearth.crypto.SigningKey

import scala.util.Random

class ExchangeTransactionDiffTest extends PropSpec with Inside with WithDomain with EitherValues with TestWallet {

  private def wavesPortfolio(amt: Long) = Portfolio.waves(amt)

  /** Assertions about how fees move between the traders and the matcher only balance if the block reward is not also
    * landing in the miner's account.
    */
  private def withoutReward(ws: WavesSettings): WavesSettings =
    ws.copy(blockchainSettings = ws.blockchainSettings.copy(rewardsSettings = ws.blockchainSettings.rewardsSettings.copy(initial = 0)))

  // Predefined assets will be implemented later; for now asset-pair IDs are hardcoded.
  private def iasset(n: Int): IssuedAsset = IssuedAsset(ByteStr(Array.fill(32)(n.toByte)))

  // A hardcoded id names an asset that no transaction ever issues, so the genesis snapshot has to issue it - otherwise
  // the real append path rejects the trade with "Assets should be issued before they can be traded". The issuer is only
  // recorded in the asset's static info, so any 32-byte public key does.
  private val assetIssuer = ByteStr.fill(32)(3).toString

  private val AssetQuantity = 100_000_000_00L

  /** Note that `decimals` is not cosmetic: an order's price is normalized by the difference between the decimals of the
    * two assets of its pair, so the properties below that pin normalized prices depend on it.
    */
  private def genesisAsset(asset: IssuedAsset, quantity: Long, decimals: Int = 8): GenesisAssetSettings =
    GenesisAssetSettings(asset.id, assetIssuer, s"asset-${asset.id.arr.head}", decimals, quantity, TestValues.fee)

  /** Declares `assets` in the genesis snapshot, splitting each one's quantity evenly between `holders`.
    *
    * A genesis asset has to be fully distributed - the snapshot is rejected otherwise - and both sides of a trade have
    * to hold what they are selling, so the split is what lets these exchanges go through. Returns the balance entries
    * to prepend to the test's own: duplicates are deduped by address keeping the first, so these win.
    */
  private def genesisAssets(assets: Seq[IssuedAsset], holders: Seq[SigningKey]): (Seq[GenesisAssetSettings], Seq[AddrWithBalance]) =
    if (assets.isEmpty || holders.isEmpty) (Seq.empty, Seq.empty)
    else {
      val perHolder = AssetQuantity / holders.size
      val settings  = assets.distinct.map(genesisAsset(_, perHolder * holders.size))
      val balances  = holders.map(h => AddrWithBalance(h.toAddress, ENOUGH_AMT, assets.distinct.map(_ -> perHolder).toMap))
      (settings, balances)
    }

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

    val preconditionsAndExchange: Seq[(Seq[AddrWithBalance], ExchangeTransaction, Seq[IssuedAsset])] = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)
      val assetId = ByteStr(new Array[Byte](32))

      val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller, matcher)

      for {
        maybeAsset1 <- Seq(Some(assetId), None)
        maybeAsset2 <- Seq(Some(assetId), None) if maybeAsset1 != maybeAsset2
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
            matcher
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
            matcher
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
            matcher
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
            matcher
          )
        )
      } yield (genesis, exchange, Seq(maybeAsset1, maybeAsset2) collect { case Some(id) => IssuedAsset(id) })
    }

    preconditionsAndExchange.foreach { case (genesis, exchange, assets) =>
      val (assetSettings, assetBalances) = genesisAssets(assets, Seq(TxHelpers.signer(1), TxHelpers.signer(2)))
      withDomain(withoutReward(ScriptsAndSponsorship), assetBalances ++ genesis, assets = assetSettings) { d =>
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
          d.rocksDBWriter.balance(exchange.sender.toAddress) + exchange.buyMatcherFee.value + exchange.sellMatcherFee.value - exchange.fee.value
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
            matcher
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, maybeAsset2, maybeAsset1, amount = 100000000, sender = buyer, matcher = matcher, version = Order.V2),
            TxHelpers.order(OrderType.SELL, maybeAsset2, maybeAsset1, amount = 100000000, sender = seller, matcher = matcher, version = Order.V2),
            matcher
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
            matcher
          )
        )
      } yield (genesis, exchange)).distinct
    }

    preconditionsAndExchange.foreach { case (genesis, exchange) =>
      val (assetSettings, assetBalances) = genesisAssets(Seq(iasset(1), iasset(2)), Seq(TxHelpers.signer(1), TxHelpers.signer(2)))
      withDomain(withoutReward(ScriptsAndSponsorship), assetBalances ++ genesis, assets = assetSettings) { d =>
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
            ExchangeTransactionDiff.getOrderFeePortfolio(exchange.buyOrder, exchange.buyMatcherFee.value),
            ExchangeTransactionDiff.getOrderFeePortfolio(exchange.sellOrder, exchange.sellMatcherFee.value),
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

      val buyerIssuedAsset  = iasset(1)
      val sellerIssuedAsset = iasset(2)
      // Neither trader holds any of the pair - that is what drives the balance negative - so the whole issued quantity
      // sits with defaultSigner, since a genesis asset has to be fully distributed.
      val genesis =
        AddrWithBalance(defaultAddress, ENOUGH_AMT, Map(buyerIssuedAsset -> AssetQuantity, sellerIssuedAsset -> AssetQuantity)) +:
          AddrWithBalance.enoughBalances(buyer, seller, matcher)

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
        matcher
      )

      (genesis, exchange)
    }

    val (genesis, exchange) = preconditionsAndExchange
    withDomain(
      domainSettingsWithFS(fsWithOrderFeature),
      genesis,
      assets = Seq(genesisAsset(iasset(1), AssetQuantity), genesisAsset(iasset(2), AssetQuantity))
    ) { d =>
      d.appendBlockE(exchange) should produce("negative asset balance")
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
          matcher
        )
        (genesis, exchange)
      }
    }

    preconditionsAndExchange.foreach { case (genesis, exchange) =>
      // The pair (1, 2) plus the two separately issued fee assets (3, 4), all held by both traders
      val (assetSettings, assetBalances) =
        genesisAssets((1 to 4).map(iasset), Seq(TxHelpers.signer(1), TxHelpers.signer(2)))
      withDomain(withoutReward(ScriptsAndSponsorship), assetBalances ++ genesis, assets = assetSettings) { d =>
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
            ExchangeTransactionDiff.getOrderFeePortfolio(exchange.buyOrder, exchange.buyMatcherFee.value),
            ExchangeTransactionDiff.getOrderFeePortfolio(exchange.sellOrder, exchange.sellMatcherFee.value),
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
          matcher
        )

        (genesis, exchange)
      }
    }

    preconditionsAndExchange.foreach { case (genesis, exchange) =>
      // The pair is issued and held, so the trade gets past the issued-asset check; the fee asset is issued but held
      // by nobody involved, so paying the fee in it overdraws - which is the AccountBalanceError under test.
      val feeAsset                     = iasset(3)
      val (pairSettings, pairBalances) = genesisAssets(Seq(iasset(1), iasset(2)), Seq(TxHelpers.signer(1), TxHelpers.signer(2)))
      val feeAssetHolder               = AddrWithBalance(defaultAddress, ENOUGH_AMT, Map(feeAsset -> AssetQuantity))
      withDomain(
        domainSettingsWithFS(fsWithOrderFeature),
        pairBalances ++ Seq(feeAssetHolder) ++ genesis,
        assets = pairSettings :+ genesisAsset(feeAsset, AssetQuantity)
      ) { d =>
        d.appendBlockE(exchange) should produce("AccountBalanceError")
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
        matcher
      )

      (genesis, exchange)
    }

    val (genesis, exchange) = preconditionsAndExchange
    // All four assets exist and are split between the traders, so the pair trades fine but the matcher fee - which is
    // ENOUGH_AMT/10, far more than either holds - overdraws the separately issued fee asset.
    val (assetSettings, assetBalances) = genesisAssets((1 to 4).map(iasset), Seq(TxHelpers.signer(1), TxHelpers.signer(2)))
    withDomain(domainSettingsWithFS(fsWithOrderFeature), assetBalances ++ genesis, assets = assetSettings) { d =>
      d.appendBlockE(exchange) should produce("negative asset balance")
    }
  }

  property("Total matcher's fee (sum of matcher's fees in exchange transactions) is less than or equal to order's matcher fee") {
    val preconditions =
      oneBuyFewSellsPreconditions(
        totalBuyMatcherFeeBoundaries = identity,
        sellersTotalAmount = identity
      )

    val (genesises, assetSettings, massTransfer, exchanges, bigBuyOrder) = preconditions
    withDomain(withoutReward(RideV3), genesises, assets = assetSettings) { d =>
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
        exchanges.map(ex => getOrderFeePortfolio(bigBuyOrder, ex.buyMatcherFee.value)).fold(Portfolio())(_.combine(_).explicitGet())

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

    val (genesises, assetSettings, massTransfer, exchanges, _) = preconditions
    withDomain(domainSettingsWithFS(fsOrderMassTransfer), genesises, assets = assetSettings) { d =>
      d.appendBlock(massTransfer)
      d.appendBlockE(exchanges*) should produce("Insufficient buy fee")
    }
  }

  property("Validation fails when total sell amount overfills buy order amount") {

    val preconditions =
      oneBuyFewSellsPreconditions(
        totalBuyMatcherFeeBoundaries = (bigBuyOrderMatcherFee: Long) => bigBuyOrderMatcherFee, // correct total buyMatcherFee in ex trs
        sellersTotalAmount = (bigBuyOrderAmount: Long) => bigBuyOrderAmount + 10000L           // sell orders overfill buy order
      )

    val (genesises, assetSettings, massTransfer, exchanges, _) = preconditions
    withDomain(domainSettingsWithFS(fsOrderMassTransfer), genesises, assets = assetSettings) { d =>
      d.appendBlock(massTransfer)
      d.appendBlockE(exchanges*) should produce("Too much buy")
    }
  }

  property("buy waves without enough money for fee") {
    val buyer   = TxHelpers.signer(1)
    val seller  = TxHelpers.signer(2)
    val matcher = TxHelpers.signer(3)

    val asset      = iasset(1)
    val matcherFee = 300000L

    // The buyer buys Waves and pays in `asset`, so it is the buyer that has to hold the whole issued quantity. The
    // matcher is funded because it pays the transaction fee up front, before the matcher fees it collects are credited.
    def genesisWithBuyerBalance(buyerBalance: Long): Seq[AddrWithBalance] = Seq(
      AddrWithBalance(buyer.toAddress, buyerBalance, Map(asset -> AssetQuantity)),
      AddrWithBalance(seller.toAddress),
      AddrWithBalance(matcher.toAddress)
    )

    val exchanges: Seq[ExchangeTransaction] = {
      Seq(
        TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, Waves, asset, amount = 100000000L, fee = 300000, sender = buyer, matcher = matcher, version = Order.V1),
          TxHelpers
            .order(OrderType.SELL, Waves, asset, amount = 100000000L, fee = 300000, sender = seller, matcher = matcher, version = Order.V1),
          matcher,
          fee = 300000
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, Waves, asset, amount = 100000000L, fee = 300000, sender = buyer, matcher = matcher, version = Order.V1),
          TxHelpers
            .order(OrderType.SELL, Waves, asset, amount = 100000000L, fee = 300000, sender = seller, matcher = matcher, version = Order.V1),
          matcher,
          fee = 300000
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, Waves, asset, amount = 100000000L, fee = 300000, sender = buyer, matcher = matcher, version = Order.V2),
          TxHelpers
            .order(OrderType.SELL, Waves, asset, amount = 100000000L, fee = 300000, sender = seller, matcher = matcher, version = Order.V2),
          matcher,
          fee = 300000
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers
            .order(OrderType.BUY, Waves, asset, amount = 100000000L, fee = 300000, sender = buyer, matcher = matcher, version = Order.V3),
          TxHelpers
            .order(OrderType.SELL, Waves, asset, amount = 100000000L, fee = 300000, sender = seller, matcher = matcher, version = Order.V3),
          matcher,
          fee = 300000
        )
      )
    }

    val assets = Seq(genesisAsset(asset, AssetQuantity))

    exchanges.foreach { exchange =>
      withDomain(withoutReward(ScriptsAndSponsorship), genesisWithBuyerBalance(1 * Constants.UnitsInWave), assets = assets) { d =>
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
          d.rocksDBWriter.balance(
            exchange.sender.toAddress,
            Waves
          ) + exchange.buyMatcherFee.value + exchange.sellMatcherFee.value - exchange.fee.value
      }

      // The point of the property: the buyer pays the matcher fee out of what it already has, before the Waves it buys
      // are credited, so a balance below that fee is not enough - however much the trade is about to bring in
      withDomain(domainSettingsWithFS(fsWithBlockV5), genesisWithBuyerBalance(matcherFee - 1), assets = assets) { d =>
        d.appendBlockE(exchange) should produce("AccountBalanceError")
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

    val issue = iasset(1)

    val preconditions: (SigningKey, SigningKey, SigningKey, Seq[AddrWithBalance], IssuedAsset) = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      // The seller sells `issue`, so it holds the whole issued quantity. The matcher gets exactly the transaction fee:
      // it pays that before the matcher fees it collects are credited, and starting it at a known amount is what makes
      // the balance assertions below readable.
      val genesis =
        Seq(
          AddrWithBalance(seller.toAddress, ENOUGH_AMT, Map(issue -> AssetQuantity)),
          AddrWithBalance(matcher.toAddress, MatcherFee)
        ) ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer)

      (buyer, seller, matcher, genesis, issue)
    }

    val (buyer, seller, matcher, genesis, _) = preconditions
    val buy =
      TxHelpers.order(OrderType.BUY, issue, Waves, amount = 1000000L, fee = MatcherFee, sender = buyer, matcher = matcher, version = Order.V1)
    val sell = TxHelpers.order(OrderType.SELL, issue, Waves, fee = MatcherFee, sender = seller, matcher = matcher, version = Order.V1)
    val tx   = createExTx(buy, sell, buy.price.value, matcher)
    assertDiffAndState(Seq(TestBlock.create(Seq())), TestBlock.create(Seq(tx)), fs, genesis, Seq(genesisAsset(issue, AssetQuantity))) {
      case (snapshot, state) =>
        // The buy order is filled for a fraction of its amount, so its share of the matcher fee rounds down to a single
        // wavelet - which is what the matcher is left with on top of what it started with
        snapshot.balances((tx.sender.toAddress, Waves)) shouldBe
          MatcherFee + tx.buyMatcherFee.value + tx.sellMatcherFee.value - tx.fee.value
        state.balance(tx.sender.toAddress) shouldBe MatcherFee + 1L
    }
  }

  property("Not enough balance") {
    val MatcherFee = 300000L

    val preconditions: (SigningKey, SigningKey, SigningKey, Seq[AddrWithBalance], IssuedAsset) = {
      val buyer   = TxHelpers.signer(1)
      val seller  = TxHelpers.signer(2)
      val matcher = TxHelpers.signer(3)

      val issue = iasset(1)
      // The asset is issued, but to nobody involved in the trade: the seller has to reach the balance check under test
      // rather than fail earlier with "Assets should be issued before they can be traded". The matcher is funded so
      // that the balance error is the seller's, not the matcher's unpaid transaction fee.
      val genesis =
        AddrWithBalance(defaultAddress, ENOUGH_AMT, Map(issue -> AssetQuantity)) +:
          AddrWithBalance.enoughBalances(buyer, seller, matcher)

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
    withDomain(domainSettingsWithFS(fsWithOrderFeature), genesis, assets = Seq(genesisAsset(issue, AssetQuantity))) { d =>
      inside(d.appendBlockE(tx)) { case Left(TransactionValidationError(AccountBalanceError(errs), _)) =>
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

      val issue = iasset(1)
      // The buyer buys Waves and pays in `issue`, so it holds the whole issued quantity
      val genesis =
        AddrWithBalance(buyer.toAddress, ENOUGH_AMT, Map(issue -> AssetQuantity)) +:
          AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, seller, matcher)

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
      fee = buy.matcherFee.value
    )
    withDomain(ScriptsAndSponsorship, genesis, assets = Seq(genesisAsset(issue, AssetQuantity))) { d =>
      d.appendBlock(tx)
      d.liquidSnapshot.balances((buyer.toAddress, Waves)) shouldBe d.rocksDBWriter.balance(buyer.toAddress) - 41 + 425532
      d.liquidSnapshot.balances((seller.toAddress, Waves)) shouldBe d.rocksDBWriter.balance(seller.toAddress) - 300000 - 425532
      d.liquidSnapshot.balances((matcher.toAddress, Waves)) shouldBe d.rocksDBWriter.balance(seller.toAddress) + 41 + 300000 - tx.fee.value
    }
  }

  property("ExchangeTransaction invalid if order signature invalid") {
    simpleTradePreconditions.foreach { case (genesis, exchange, assetSettings) =>
      val exchangeWithResignedOrder = (exchange: @unchecked) match {
        // Signed by the seller instead of the order's own sender, so the proof is well formed but does not validate.
        // (It used to be signed with `PrivateKey(publicKey)`, which the Ed25519 key types no longer accept.)
        case e1 @ ExchangeTransaction(order1 = bo) =>
          val newSig = ByteStr(TxHelpers.signer(2).sign(bo.bodyBytes()))
          e1.copy(order1 = bo.withProofs(Proofs(Seq(newSig))))
        case e2 @ ExchangeTransaction(order2 = so) =>
          val newSig = ByteStr(TxHelpers.signer(1).sign(so.bodyBytes()))
          e2.copy(order2 = so.withProofs(Proofs(Seq(newSig))))
      }

      // Through the real append path: BlockDiffer alone does not verify order signatures, so assertDiffEi accepted the
      // re-signed order and this assertion never fired.
      withDomain(domainSettingsWithFS(fsWithOrderFeature), genesis, assets = assetSettings) { d =>
        d.appendBlockE(exchangeWithResignedOrder) should produce("Proof doesn't validate as signature")
      }
    }
  }

  property("ExchangeTransaction invalid if exchange.price > buyOrder.price or exchange.price < sellOrder.price") {
    val buyer   = TxHelpers.signer(1)
    val seller  = TxHelpers.signer(2)
    val matcher = TxHelpers.signer(3)

    val amountAsset = iasset(2)
    val priceAsset  = iasset(1)

    // Both orders are the buyer's, so it is the only trader that needs to hold the pair. The 8/6 decimals are what the
    // normalized prices in the expected errors below are computed from.
    val assetSettings = Seq(genesisAsset(amountAsset, AssetQuantity, decimals = 8), genesisAsset(priceAsset, AssetQuantity, decimals = 6))
    val genesis = AddrWithBalance(buyer.toAddress, ENOUGH_AMT, Map(amountAsset -> AssetQuantity, priceAsset -> AssetQuantity)) +:
      AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, seller, matcher)

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

    // decimalPrice = 12.5. An ExchangeTransaction has no version any more, so there is no longer a variant that
    // compares the prices as they are written: an order in Default mode is always normalized by the difference between
    // the decimals of its pair, exactly like one in AssetDecimals mode.
    val cases = {
      val defaultModeOrders = for {
        buyOrderVersion  <- 1 to 3
        sellOrderVersion <- 1 to 3

        r <- Seq(
          // normalizedPrice = decimalPrice * 10^(8 + priceAssetDecimals - amountAssetDecimals) = 1_250_000_000
          (
            1_250_000_000L,
            mkTestOrder(OrderType.BUY, 12_400_000L, buyOrderVersion, OrderPriceMode.Default),
            mkTestOrder(OrderType.SELL, 12_500_000L, sellOrderVersion, OrderPriceMode.Default),
            "exchange.price = 1_250_000_000 should be <= buyOrder.price = 1_240_000_000 (assetDecimals price = 12_400_000)"
          ),
          (
            1_250_000_000L,
            mkTestOrder(OrderType.BUY, 12_500_000L, buyOrderVersion, OrderPriceMode.Default),
            mkTestOrder(OrderType.SELL, 12_600_000L, sellOrderVersion, OrderPriceMode.Default),
            "exchange.price = 1_250_000_000 should be >= sellOrder.price = 1_260_000_000 (assetDecimals price = 12_600_000)"
          )
        )
      } yield r

      val case1NewTxs = Seq(
        // normalizedPrice = decimalPrice * 10^(priceAssetDecimals - amountAssetDecimals) = 15
        (
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 12_400_000L, 4, OrderPriceMode.AssetDecimals),
          mkTestOrder(OrderType.SELL, 12_500_000L, 4, OrderPriceMode.AssetDecimals),
          "exchange.price = 1_250_000_000 should be <= buyOrder.price = 1_240_000_000 (assetDecimals price = 12_400_000)"
        ),
        (
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 12_500_000L, 4, OrderPriceMode.AssetDecimals),
          mkTestOrder(OrderType.SELL, 12_600_000L, 4, OrderPriceMode.AssetDecimals),
          "exchange.price = 1_250_000_000 should be >= sellOrder.price = 1_260_000_000 (assetDecimals price = 12_600_000)"
        )
      )

      val case2 = Seq(
        // normalizedPrice = decimalPrice * 10^8 = 1_250_000_000
        (
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 1_240_000_000L, 4, OrderPriceMode.FixedDecimals),
          mkTestOrder(OrderType.SELL, 1_250_000_000L, 4, OrderPriceMode.FixedDecimals),
          "exchange.price = 1_250_000_000 should be <= buyOrder.price = 1_240_000_000"
        ),
        (
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 1_250_000_000L, 4, OrderPriceMode.FixedDecimals),
          mkTestOrder(OrderType.SELL, 1_260_000_000L, 4, OrderPriceMode.FixedDecimals),
          "exchange.price = 1_250_000_000 should be >= sellOrder.price = 1_260_000_000"
        )
      )

      val mixedCase = Seq(
        (
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 12_400_000L, 2, OrderPriceMode.Default),
          mkTestOrder(OrderType.SELL, 1_250_000_000L, 4, OrderPriceMode.FixedDecimals),
          "exchange.price = 1_250_000_000 should be <= buyOrder.price = 1_240_000_000 (assetDecimals price = 12_400_000)"
        ),
        (
          1_250_000_000L,
          mkTestOrder(OrderType.BUY, 1_250_000_000L, 4, OrderPriceMode.FixedDecimals),
          mkTestOrder(OrderType.SELL, 12_600_000L, 4, OrderPriceMode.AssetDecimals),
          "exchange.price = 1_250_000_000 should be >= sellOrder.price = 1_260_000_000 (assetDecimals price = 12_600_000)"
        )
      )

      Table(
        ("txPrice", "buyOrder", "sellOrder", "expectedError"),
        (defaultModeOrders ++ case2 ++ case1NewTxs ++ mixedCase)*
      )
    }

    forAll(cases) { case (txPrice, buyOrder, sellOrder, expectedError) =>
      val exchange = TxHelpers.exchangeFromOrders(
        order1 = buyOrder,
        order2 = sellOrder,
        price = txPrice,
        matcher = matcher,
        fee = TestValues.fee,
        chainId = AddressScheme.current.chainId
      )

      withDomain(domainSettingsWithFS(fsWithRideV6), genesis, assets = assetSettings) { d =>
        d.appendBlockE(exchange) should produce(expectedError)
      }
    }
  }

  property("ExchangeTransaction invalid if order contains more than one proofs") {
    simpleTradePreconditions.foreach { case (genesis, exchange, assetSettings) =>
      // Two proofs where one is allowed - what they contain does not matter, only that there is more than one
      val newProofs = Proofs(
        Seq(
          ByteStr(TxHelpers.signer(3).sign(exchange.sellOrder.bodyBytes())),
          ByteStr(TxHelpers.signer(2).sign(exchange.sellOrder.bodyBytes()))
        )
      )

      val exchangeWithResignedOrder = (exchange: @unchecked) match {
        case e1 @ ExchangeTransaction(_, so, _, _, _, _, _, _, _, _) =>
          e1.copy(order1 = so.withProofs(newProofs))
        case e2 @ ExchangeTransaction(_, so, _, _, _, _, _, _, _, _) =>
          e2.copy(order1 = so.withProofs(newProofs))
      }

      // Through the real append path: BlockDiffer alone does not verify order signatures, so assertDiffEi accepted the
      // re-signed order and this assertion never fired.
      withDomain(domainSettingsWithFS(fsWithOrderFeature), genesis, assets = assetSettings) { d =>
        d.appendBlockE(exchangeWithResignedOrder) should produce("Proof doesn't validate as signature")
      }
    }
  }

  property("A price mode other than the default is only allowed in Order V4") {
    val matcher = TxHelpers.secondSigner
    val asset   = iasset(1)

    def generateTx(version: Byte, mode: OrderPriceMode): ExchangeTransaction = {
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
        ntpTime.correctedTime()
      )
    }

    def generateAndAppendTx(orderVersion: Byte, mode: OrderPriceMode, settings: WavesSettings = DomainPresets.RideV5): Unit = {
      // defaultSigner is the seller, so it holds the whole issued quantity of `asset`
      withDomain(
        settings,
        Seq(AddrWithBalance(TxHelpers.defaultAddress, ENOUGH_AMT, Map(asset -> AssetQuantity))),
        assets = Seq(genesisAsset(asset, AssetQuantity))
      ) { d =>
        d.helpers.creditWavesFromDefaultSigner(TxHelpers.secondAddress)
        d.appendAndAssertSucceed(generateTx(orderVersion, mode))
      }
    }

    intercept[RuntimeException](generateAndAppendTx(Order.V1, FixedDecimals)).getMessage should include("price mode should be default")
    intercept[RuntimeException](generateAndAppendTx(Order.V2, FixedDecimals)).getMessage should include("price mode should be default")
    intercept[RuntimeException](generateAndAppendTx(Order.V3, FixedDecimals)).getMessage should include("price mode should be default")
    // A V4 order takes either mode. AssetDecimals used to be rejected until RideV6 activated, but nothing gates it any
    // more - `Order.validate` only checks the mode against the order version
    generateAndAppendTx(Order.V4, AssetDecimals)
    generateAndAppendTx(Order.V4, FixedDecimals, DomainPresets.RideV6)
    generateAndAppendTx(Order.V4, AssetDecimals, DomainPresets.RideV6)
  }

  property("ExchangeTransaction with Orders V4 uses asset decimals for price calculation") {
    val enoughFee = 100000000L
    val buyer     = TxHelpers.signer(1)
    val seller    = TxHelpers.signer(2)
    val matcher   = TxHelpers.signer(3)

    val (preconditions, usdn, tidex, liquid) = {
      val usdn   = iasset(1)
      val tidex  = iasset(2)
      val liquid = iasset(3)

      (Seq(TestBlock.create(Seq())), usdn, tidex, liquid) // Height 1: carries the genesis snapshot
    }

    // Every pair below trades one of these three against Waves, in both directions across the scenarios, so both
    // traders hold all of them. The decimals are the point of the property - they are what the two price modes
    // normalize by - so they keep the values the real assets these are named after have.
    val assetSettings = Seq(
      genesisAsset(usdn, AssetQuantity, decimals = 6),
      genesisAsset(tidex, AssetQuantity, decimals = 2),
      genesisAsset(liquid, AssetQuantity, decimals = 8)
    )
    val perHolder       = AssetQuantity / 2
    val assetBalances   = Seq(buyer, seller).map(h => AddrWithBalance(h.toAddress, ENOUGH_AMT, Seq(usdn, tidex, liquid).map(_ -> perHolder).toMap))
    val genesisBalances = assetBalances ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, matcher)

    def mkExchange(
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
        // The transaction price is always in the normalized scale now - there is no transaction version left that would
        // read it as written, so this scenario carries the same price as the others
        mkExchange(Order.V3, Order.V3, 55768188998L, 59260000L, 592600L, DefaultPriceMode, 592600L, DefaultPriceMode, wavesUsdn),
        mkExchange(Order.V4, Order.V4, 55768188998L, 59260000L, 592600L, AssetDecimals, 592600L, AssetDecimals, wavesUsdn),
        mkExchange(Order.V4, Order.V4, 55768188998L, 59260000L, 59260000L, DefaultPriceMode, 59260000L, DefaultPriceMode, wavesUsdn),
        mkExchange(Order.V3, Order.V4, 55768188998L, 59260000L, 592600L, DefaultPriceMode, 592600L, AssetDecimals, wavesUsdn),
        mkExchange(Order.V3, Order.V4, 55768188998L, 59260000L, 592600L, DefaultPriceMode, 59260000L, FixedDecimals, wavesUsdn),
        mkExchange(Order.V4, Order.V3, 55768188998L, 59260000L, 592600L, AssetDecimals, 592600L, DefaultPriceMode, wavesUsdn),
        mkExchange(Order.V4, Order.V3, 55768188998L, 59260000L, 59260000L, FixedDecimals, 592600L, DefaultPriceMode, wavesUsdn)
      ),
      (
        mkExchange(
          Order.V3,
          Order.V3,
          213L,
          35016774L,
          35016774000000L,
          DefaultPriceMode,
          35016774000000L,
          DefaultPriceMode,
          tidexWaves
        ),
        mkExchange(Order.V4, Order.V4, 213L, 35016774L, 35016774000000L, AssetDecimals, 35016774000000L, AssetDecimals, tidexWaves),
        mkExchange(Order.V4, Order.V4, 213L, 35016774L, 35016774, FixedDecimals, 35016774L, FixedDecimals, tidexWaves),
        mkExchange(Order.V3, Order.V4, 213L, 35016774L, 35016774000000L, DefaultPriceMode, 35016774000000L, AssetDecimals, tidexWaves),
        mkExchange(Order.V3, Order.V4, 213L, 35016774L, 35016774000000L, DefaultPriceMode, 35016774L, FixedDecimals, tidexWaves),
        mkExchange(Order.V4, Order.V3, 213L, 35016774L, 35016774000000L, AssetDecimals, 35016774000000L, DefaultPriceMode, tidexWaves),
        mkExchange(Order.V4, Order.V3, 213L, 35016774L, 35016774L, FixedDecimals, 35016774000000L, DefaultPriceMode, tidexWaves)
      ),
      (
        mkExchange(Order.V3, Order.V3, 2000000000L, 13898832L, 13898832L, DefaultPriceMode, 13898832L, DefaultPriceMode, liquidWaves),
        mkExchange(Order.V4, Order.V4, 2000000000L, 13898832L, 13898832L, AssetDecimals, 13898832L, AssetDecimals, liquidWaves),
        mkExchange(Order.V4, Order.V4, 2000000000L, 13898832L, 13898832L, FixedDecimals, 13898832L, FixedDecimals, liquidWaves),
        mkExchange(Order.V3, Order.V4, 2000000000L, 13898832L, 13898832L, DefaultPriceMode, 13898832L, AssetDecimals, liquidWaves),
        mkExchange(Order.V3, Order.V4, 2000000000L, 13898832L, 13898832L, DefaultPriceMode, 13898832L, FixedDecimals, liquidWaves),
        mkExchange(Order.V4, Order.V3, 2000000000L, 13898832L, 13898832L, AssetDecimals, 13898832L, DefaultPriceMode, liquidWaves),
        mkExchange(Order.V4, Order.V3, 2000000000L, 13898832L, 13898832L, FixedDecimals, 13898832L, DefaultPriceMode, liquidWaves)
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
            genesisBalances,
            assetSettings
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

      val asset1 = iasset(1)
      val asset2 = iasset(2)
      val ttx1   = TxHelpers.transfer(matcher, seller.toAddress, ENOUGH_AMT / 2, asset1)
      val ttx2   = TxHelpers.transfer(matcher, buyer.toAddress, ENOUGH_AMT / 2, asset1)
      val ttx3   = TxHelpers.transfer(matcher, seller.toAddress, ENOUGH_AMT / 2, asset2)
      val ttx4   = TxHelpers.transfer(matcher, buyer.toAddress, ENOUGH_AMT / 2, asset2)

      val assets = Seq(asset1, asset2)

      // The traders are funded by the transfers above, so the matcher starts out holding every asset in full - which is
      // also what a genesis asset requires, being fully distributed
      val assetSettings = assets.map(genesisAsset(_, ENOUGH_AMT))
      val genesisBalances = AddrWithBalance(matcher.toAddress, ENOUGH_AMT, assets.map(_ -> ENOUGH_AMT).toMap) +:
        AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, buyer, seller)

      for {
        amountAsset <- assets
        priceAsset  <- assets if priceAsset != amountAsset
        tx <- Seq(
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, amountAsset, priceAsset, sender = buyer, matcher = matcher, version = Order.V1),
            TxHelpers.order(OrderType.SELL, amountAsset, priceAsset, sender = seller, matcher = matcher, version = Order.V1),
            matcher
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, amountAsset, priceAsset, sender = buyer, matcher = matcher, version = Order.V1),
            TxHelpers.order(OrderType.SELL, amountAsset, priceAsset, sender = seller, matcher = matcher, version = Order.V1),
            matcher
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, amountAsset, priceAsset, sender = buyer, matcher = matcher, version = Order.V2),
            TxHelpers.order(OrderType.SELL, amountAsset, priceAsset, sender = seller, matcher = matcher, version = Order.V2),
            matcher
          ),
          TxHelpers.exchangeFromOrders(
            TxHelpers.order(OrderType.BUY, amountAsset, priceAsset, sender = buyer, matcher = matcher, version = Order.V3),
            TxHelpers.order(OrderType.SELL, amountAsset, priceAsset, sender = seller, matcher = matcher, version = Order.V3),
            matcher
          )
        )
      } yield {
        val fee = 100000000L
        val fixed = tx
          .copy(
            buyMatcherFee = TxMatcherFee.unsafeFrom(fee),
            sellMatcherFee = TxMatcherFee.unsafeFrom(fee),
            fee = TxPositiveAmount.unsafeFrom(fee),
            order1 = {
              val o = tx.order1.copy(version = Order.V4, matcherFee = TxMatcherFee.unsafeFrom(fee));
              o.withProofs(Proofs(ByteStr(buyer.sign(o.bodyBytes()))))
            },
            order2 = {
              val o = tx.order2.copy(version = Order.V4, matcherFee = TxMatcherFee.unsafeFrom(fee));
              o.withProofs(Proofs(ByteStr(seller.sign(o.bodyBytes()))))
            },
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

        (Seq(TestBlock.create(Seq()), TestBlock.create(Seq(ttx1, ttx2, ttx3, ttx4))), fixed, reversed, genesisBalances, assetSettings)
      }
    }

    scenario.foreach { case (preconditions, fixed, reversed, genesisBalances, assetSettings) =>
      val portfolios = collection.mutable.ListBuffer[Map[(Address, Asset), Long]]()

      assertDiffAndState(preconditions, TestBlock.create(Seq(fixed)), fsWithBlockV5, genesisBalances, assetSettings) { case (snapshot, _) =>
        portfolios += snapshot.balances
      }

      assertDiffAndState(preconditions, TestBlock.create(Seq(reversed)), fsWithBlockV5, genesisBalances, assetSettings) { case (snapshot, _) =>
        portfolios += snapshot.balances
      }

      portfolios.tail.forall(_ == portfolios.head) shouldBe true
    }
  }

  property("buyMatcherFee/sellMatcherFee validation") {
    val exchangeFee = 0.003.waves

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

    val validExchange = TxHelpers.exchange(mkOrder(OrderType.BUY), mkOrder(OrderType.SELL), sender, 1, 1, 100, 100, exchangeFee)

    /** A matcher fee that is not in (0; [[Order.MaxAmount]]) cannot be put into an [[ExchangeTransaction]] here at all -
      * `TxMatcherFee` refuses it - so such a transaction can only arrive the way a peer would send one: as protobuf
      * bytes, where the fees are plain longs. Parsing them back is what puts the smart constructors on the validating
      * side, and is the only place this check can be reached from.
      */
    def parseWithMatcherFees(buyMatcherFee: Long, sellMatcherFee: Long): Either[ValidationError, Transaction] = {
      val signed = PBTransactions.protobuf(validExchange)
      val tx     = signed.getWavesTransaction
      val patched = signed.withWavesTransaction(
        tx.withExchange(tx.getExchange.copy(buyMatcherFee = buyMatcherFee, sellMatcherFee = sellMatcherFee))
      )
      PBTransactions.vanilla(PBSignedTransaction.parseFrom(patched.toByteArray))
    }

    // The control: the same round trip with fees the constructors accept
    parseWithMatcherFees(100, 100) shouldBe Right(validExchange)

    parseWithMatcherFees(-1, 100) should produce(TxMatcherFee.errMsg)
    parseWithMatcherFees(100, Long.MinValue) should produce(TxMatcherFee.errMsg)
    parseWithMatcherFees(0, 100) should produce(TxMatcherFee.errMsg)
    parseWithMatcherFees(100, Order.MaxAmount) should produce(TxMatcherFee.errMsg)
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

  def simpleTradePreconditions: Seq[(Seq[AddrWithBalance], ExchangeTransaction, Seq[GenesisAssetSettings])] = {
    val buyer   = TxHelpers.signer(1)
    val seller  = TxHelpers.signer(2)
    val matcher = TxHelpers.signer(3)

    // The matcher pays the transaction fee and defaultSigner mines, so both need funding too; the pair assets are
    // issued and split between the traders so the exchange reaches signature validation rather than failing earlier.
    val (assetSettings, assetBalances) = genesisAssets(Seq(iasset(1), iasset(2)), Seq(buyer, seller))
    val genesis                        = assetBalances ++ AddrWithBalance.enoughBalances(buyer, seller, matcher, TxHelpers.defaultSigner)

    for {
      maybeAsset1 <- Seq(Some(iasset(1).id), None).map(Asset.fromCompatId)
      maybeAsset2 <- Seq(Some(iasset(2).id), None).map(Asset.fromCompatId) if maybeAsset1.compatId != maybeAsset2.compatId
      exchange <- Seq(
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(OrderType.BUY, maybeAsset1, maybeAsset2, sender = buyer, matcher = matcher, version = Order.V1),
          TxHelpers.order(OrderType.SELL, maybeAsset1, maybeAsset2, sender = seller, matcher = matcher, version = Order.V1),
          matcher
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(OrderType.BUY, maybeAsset1, maybeAsset2, sender = buyer, matcher = matcher, version = Order.V2),
          TxHelpers.order(OrderType.SELL, maybeAsset1, maybeAsset2, sender = seller, matcher = matcher, version = Order.V2),
          matcher
        ),
        TxHelpers.exchangeFromOrders(
          TxHelpers.order(OrderType.BUY, maybeAsset1, maybeAsset2, sender = buyer, matcher = matcher, version = Order.V3),
          TxHelpers.order(OrderType.SELL, maybeAsset1, maybeAsset2, sender = seller, matcher = matcher, version = Order.V3),
          matcher
        )
      )
    } yield (genesis, exchange, assetSettings)
  }

  /** Checks whether generated ExchangeTransactionV2 is valid. In case of using orders of version 3 it is possible that matched amount of received
    * asset is less than matcher's fee in that asset. It leads to negative asset balance error
    */
  def transactionWithOrdersV3IsValid(ex: ExchangeTransaction): Boolean = {
    (ex.buyOrder, ex.sellOrder) match {
      case (_: Order, _: Order) =>
        val isBuyerReceiveAmountGreaterThanFee =
          if (ex.buyOrder.assetPair.amountAsset == ex.buyOrder.matcherFeeAssetId) {
            ExchangeTransactionDiff.getReceiveAmount(ex.buyOrder, 8, 8, ex.amount.value, ex.price.value).explicitGet() > ex.buyMatcherFee.value
          } else true

        val isSellerReceiveAmountGreaterThanFee =
          if (ex.sellOrder.assetPair.amountAsset == ex.sellOrder.matcherFeeAssetId) {
            ExchangeTransactionDiff.getReceiveAmount(ex.sellOrder, 8, 8, ex.amount.value, ex.price.value).explicitGet() > ex.sellMatcherFee.value
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
  ): (Seq[AddrWithBalance], Seq[GenesisAssetSettings], MassTransferTransaction, Seq[ExchangeTransaction], Order) = {
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

    // Both assets start with the buyer: it hands asset2 to the sellers by mass transfer below, and pays the price and
    // its own matcher fee in asset1. asset2's quantity has to cover that transfer, which is deliberately near Long.MaxValue.
    val asset1Quantity = 1_000_000_000_000_000L
    val asset2Quantity = Long.MaxValue - 1_000_000
    val assetSettings  = Seq(genesisAsset(asset1, asset1Quantity), genesisAsset(asset2, asset2Quantity))

    val genesis = AddrWithBalance(buyer.toAddress, ENOUGH_AMT, Map(asset1 -> asset1Quantity, asset2 -> asset2Quantity)) +:
      AddrWithBalance.enoughBalances((TxHelpers.defaultSigner +: matcher +: buyer +: sellers)*)

    val massTransfer = TxHelpers.massTransfer(
      from = buyer,
      to = sellers.map(seller => seller.toAddress -> ((Long.MaxValue - 1_000_000) / sellOrdersCount)),
      asset = asset2,
      fee = 1_000_000
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

    (genesis, assetSettings, massTransfer, exchanges, bigBuyOrder)
  }

}
