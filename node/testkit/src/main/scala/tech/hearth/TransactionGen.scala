package tech.hearth

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.diffs.ENOUGH_AMT
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.assets.*
import tech.hearth.transaction.assets.exchange.*
import tech.hearth.transaction.lease.*
import tech.hearth.transaction.transfer.*
import org.scalacheck.Gen.{alphaLowerChar, alphaUpperChar, frequency, numChar}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Suite
import tech.hearth.crypto.SigningKey

import scala.concurrent.duration.*

trait TransactionGenBase extends NTPTime { suite: Suite =>

  val ScriptExtraFee                   = 400000L
  protected def hearth(n: Float): Long = (n * 100000000L).toLong

  def byteArrayGen(length: Int): Gen[Array[Byte]] = Gen.containerOfN[Array, Byte](length, Arbitrary.arbitrary[Byte])

  val bytes32gen: Gen[Array[Byte]] = byteArrayGen(32)
  val bytes64gen: Gen[Array[Byte]] = byteArrayGen(64)
  val attachmentGen: Gen[ByteStr]  = bytes32gen.map(ByteStr(_))

  def genBoundedBytes(minSize: Int, maxSize: Int): Gen[Array[Byte]] =
    for {
      length <- Gen.chooseNum(minSize, maxSize)
      bytes  <- byteArrayGen(length)
    } yield bytes

  def genBoundedString(minSize: Int, maxSize: Int): Gen[String] = {
    genBoundedStringBytes(minSize, maxSize).map(new String(_))
  }

  def genBoundedStringBytes(minSize: Int, maxSize: Int): Gen[Array[Byte]] = {
    Gen.choose(minSize, maxSize) flatMap { sz =>
      Gen.listOfN(sz, Gen.choose(0, 0x7f).map(_.toByte)).map(_.toArray)
    }
  }

  val accountGen: Gen[SigningKey] = bytes32gen.map(seed => SigningKey.fromSeed(seed))

  val aliasSymbolChar: Gen[Char] = Gen.oneOf('.', '@', '_', '-')

  val invalidAliasSymbolChar: Gen[Char] = Gen.oneOf('~', '`', '!', '#', '$', '%', '^', '&', '*', '=', '+')

  val aliasAlphabetGen: Gen[Char] = frequency((1, numChar), (1, aliasSymbolChar), (9, alphaLowerChar))

  val invalidAliasAlphabetGen: Gen[Char] = frequency((1, numChar), (3, invalidAliasSymbolChar), (9, alphaUpperChar))

  val accountOrAliasGen: Gen[Address] = accountGen.map(_.toAddress)

  // Bounded by the funding accounts actually get (ENOUGH_AMT), keeping the original 1/100 headroom for fees and
  // several transfers per account - the old literal predates ENOUGH_AMT being lowered and now exceeds it
  val positiveLongGen: Gen[Long] = Gen.choose(1, ENOUGH_AMT / 100)
  val positiveIntGen: Gen[Int]   = Gen.choose(1, Int.MaxValue / 100)
  val smallFeeGen: Gen[Long]     = Gen.choose(400000L, 100000000L)

  val maxOrderTimeGen: Gen[Long] = Gen.choose(10000L, Order.MaxLiveTime).map(_ + ntpTime.correctedTime())
  val timestampGen: Gen[Long]    = Gen.choose(1L, Long.MaxValue - 100)
  val ntpTimestampGen: Gen[Long] = Gen.choose(1, 1000).map(ntpTime.correctedTime() - _)

  def validTimestampGen(blockTimestamp: Long, back: FiniteDuration = 120.minutes, forward: FiniteDuration = 90.minutes): Gen[Long] =
    Gen.choose(blockTimestamp - back.toMillis, blockTimestamp + forward.toMillis)

  val hearthAssetGen: Gen[Option[ByteStr]] = Gen.const(None)
  val assetIdGen: Gen[Option[ByteStr]]     = Gen.frequency((1, hearthAssetGen), (10, Gen.option(bytes32gen.map(ByteStr(_)))))

  val assetPairGen: Gen[AssetPair] = assetIdGen.flatMap {
    case None => bytes32gen.map(b => AssetPair(Hearth, IssuedAsset(ByteStr(b))))
    case Some(a1bytes) =>
      val a2bytesGen: Gen[Option[Array[Byte]]] = byteArrayGen(31).map(a2bytes => Option(a1bytes.arr(0) +: a2bytes))

      Gen.oneOf(Gen.const(None), a2bytesGen).map { a2 =>
        val asset1 = IssuedAsset(a1bytes)
        val asset2 = a2.fold[Asset](Hearth)(arr => IssuedAsset(ByteStr(arr)))
        AssetPair(asset1, asset2)
      }
  }

  val MinIssueFee = 100000000L

  val proofsGen: Gen[Proofs] = for {
    proofsAmount <- Gen.choose(1, 8)
    proofs       <- Gen.listOfN(proofsAmount, genBoundedBytes(0, 50))
  } yield Proofs.create(proofs.map(ByteStr(_))).explicitGet()

  private val leaseParamGen = for {
    sender    <- accountGen
    amount    <- positiveLongGen
    fee       <- smallFeeGen
    timestamp <- timestampGen
    recipient <- accountGen
  } yield (sender, amount, fee, timestamp, recipient)

  def createLease(sender: SigningKey, amount: Long, fee: Long, timestamp: Long, recipient: Address): Gen[LeaseTransaction] = {
    val v1 = LeaseTransaction
      .create(NetworkId.current, PublicKey(sender.publicKey), recipient, amount, fee, timestamp, Proofs.empty)
      .map(_.signWith(sender))
      .explicitGet()
    val v2 = LeaseTransaction
      .create(NetworkId.current, PublicKey(sender.publicKey), recipient, amount, fee, timestamp, Proofs.empty)
      .map(_.signWith(sender))
      .explicitGet()
    Gen.oneOf(v1, v2)
  }

  def createLeaseCancel(sender: SigningKey, leaseId: ByteStr, cancelFee: Long, timestamp: Long): Gen[LeaseCancelTransaction] = {
    val v1 = LeaseCancelTransaction
      .create(PublicKey(sender.publicKey), leaseId, cancelFee, timestamp + 1, Proofs.empty)
      .map(_.signWith(sender))
      .explicitGet()
    val v2 = LeaseCancelTransaction
      .create(PublicKey(sender.publicKey), leaseId, cancelFee, timestamp + 1, Proofs.empty)
      .map(_.signWith(sender))
      .explicitGet()
    Gen.oneOf(v1, v2)
  }
  val leaseAndCancelGen: Gen[(LeaseTransaction, LeaseCancelTransaction)] = for {
    (sender, amount, fee, timestamp, recipient) <- leaseParamGen
    lease                                       <- createLease(sender, amount, fee, timestamp, recipient.toAddress)
    cancelFee                                   <- smallFeeGen
    leaseCancel                                 <- createLeaseCancel(sender, lease.id(), cancelFee, timestamp + 1)
  } yield (lease, leaseCancel)

  def leaseAndCancelGeneratorP(leaseSender: SigningKey, recipient: Address, timestamp: Long): Gen[(LeaseTransaction, LeaseCancelTransaction)] =
    for {
      (_, amount, fee, _, _) <- leaseParamGen
      lease                  <- createLease(leaseSender, amount, fee, timestamp, recipient)
      fee2                   <- smallFeeGen
      unlease                <- createLeaseCancel(leaseSender, lease.id(), fee2, timestamp + 1)
    } yield (lease, unlease)

  val leaseGen: Gen[LeaseTransaction]             = leaseAndCancelGen.map(_._1)
  val leaseCancelGen: Gen[LeaseCancelTransaction] = leaseAndCancelGen.map(_._2)

  val transferParamGen: Gen[(Asset, SigningKey, Address, Long, TxTimestamp, Asset, TxTimestamp, ByteStr)] = for {
    amount     <- positiveLongGen
    feeAmount  <- smallFeeGen
    assetId    <- Gen.option(bytes32gen)
    feeAssetId <- Gen.option(bytes32gen)
    timestamp  <- timestampGen
    sender     <- accountGen
    attachment <- genBoundedBytes(0, TransferTransaction.MaxAttachmentSize)
    recipient  <- accountOrAliasGen
  } yield (
    Asset.fromCompatId(assetId.map(ByteStr(_))),
    sender,
    recipient,
    amount,
    timestamp,
    Asset.fromCompatId(feeAssetId.map(ByteStr(_))),
    feeAmount,
    ByteStr(attachment)
  )

  private def singleTransfer(recipient: Address, amount: Long): Seq[TransferTransaction.ParsedTransfer] =
    Seq(TransferTransaction.ParsedTransfer(recipient, TxNonNegativeAmount.unsafeFrom(amount)))

  def transferGeneratorP(sender: SigningKey, recipient: Address, assetId: Asset, feeAssetId: Asset): Gen[TransferTransaction] =
    for {
      (_, _, _, amount, timestamp, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction
      .create(
        PublicKey(sender.publicKey),
        assetId,
        singleTransfer(recipient, amount),
        feeAmount,
        timestamp,
        attachment,
        Proofs.empty,
        feeAssetId = feeAssetId
      )
      .map(_.signWith(sender))
      .explicitGet()

  def versionedTransferGeneratorP(sender: SigningKey, recipient: Address, assetId: Asset, feeAssetId: Asset): Gen[TransferTransaction] =
    for {
      (_, _, _, amount, timestamp, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction
      .create(
        PublicKey(sender.publicKey),
        assetId,
        singleTransfer(recipient, amount),
        feeAmount,
        timestamp,
        attachment,
        Proofs.empty,
        feeAssetId = feeAssetId
      )
      .map(_.signWith(sender))
      .explicitGet()

  def transferGeneratorP(timestamp: Long, sender: SigningKey, recipient: Address, maxAmount: Long): Gen[TransferTransaction] =
    for {
      amount                                    <- Gen.choose(1L, maxAmount)
      (_, _, _, _, _, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction
      .create(PublicKey(sender.publicKey), Hearth, singleTransfer(recipient, amount), feeAmount, timestamp, attachment, Proofs.empty)
      .map(_.signWith(sender))
      .explicitGet()

  def transferGeneratorP(timestamp: Long, sender: SigningKey, recipient: Address, assetId: Asset, feeAssetId: Asset): Gen[TransferTransaction] =
    for {
      (_, _, _, amount, _, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction
      .create(
        PublicKey(sender.publicKey),
        assetId,
        singleTransfer(recipient, amount),
        feeAmount,
        timestamp,
        attachment,
        Proofs.empty,
        feeAssetId = feeAssetId
      )
      .map(_.signWith(sender))
      .explicitGet()

  def hearthTransferGeneratorP(sender: SigningKey, recipient: Address): Gen[TransferTransaction] =
    transferGeneratorP(sender, recipient, Hearth, Hearth)

  def hearthTransferGeneratorP(timestamp: Long, sender: SigningKey, recipient: Address): Gen[TransferTransaction] =
    transferGeneratorP(timestamp, sender, recipient, Hearth, Hearth)

  def createHearthTransfer(
      sender: SigningKey,
      recipient: Address,
      amount: Long,
      fee: Long,
      timestamp: Long
  ): Either[ValidationError, TransferTransaction] =
    TransferTransaction
      .create(PublicKey(sender.publicKey), Hearth, singleTransfer(recipient, amount), fee, timestamp, ByteStr.empty, Proofs.empty)
      .map(_.signWith(sender))

  val transferV1Gen: Gen[TransferTransaction] = (for {
    (assetId, sender, recipient, amount, timestamp, feeAssetId, feeAmount, attachment) <- transferParamGen
  } yield TransferTransaction
    .create(
      PublicKey(sender.publicKey),
      assetId,
      singleTransfer(recipient, amount),
      feeAmount,
      timestamp,
      attachment,
      Proofs.empty,
      feeAssetId = feeAssetId
    )
    .map(_.signWith(sender))
    .explicitGet())
    .label("transferTransaction")

  val transferV2Gen: Gen[TransferTransaction] = (for {
    (assetId, sender, recipient, amount, timestamp, feeAssetId, feeAmount, attachment) <- transferParamGen
  } yield TransferTransaction
    .create(
      PublicKey(sender.publicKey),
      assetId,
      singleTransfer(recipient, amount),
      feeAmount,
      timestamp,
      attachment,
      Proofs.empty,
      feeAssetId = feeAssetId
    )
    .map(_.signWith(sender))
    .explicitGet(
    ))
    .label("VersionedTransferTransaction")

  val priceGen: Gen[Long]            = Gen.choose(1, 3 * 100000L * 100000000L)
  val matcherAmountGen: Gen[Long]    = Gen.choose(1, 3 * 100000L * 100000000L)
  val matcherFeeAmountGen: Gen[Long] = Gen.choose(1, 3 * 100000L * 100000000L)

  val orderTypeGen: Gen[OrderType] = Gen.oneOf(OrderType.BUY, OrderType.SELL)

  val orderParamGen: Gen[(SigningKey, SigningKey, AssetPair, OrderType, TxTimestamp, TxTimestamp, TxTimestamp, TxTimestamp, TxTimestamp)] = for {
    sender     <- accountGen
    matcher    <- accountGen
    pair       <- assetPairGen
    orderType  <- orderTypeGen
    amount     <- matcherAmountGen
    price      <- priceGen
    timestamp  <- timestampGen
    expiration <- maxOrderTimeGen
    matcherFee <- matcherFeeAmountGen
  } yield (sender, matcher, pair, orderType, amount, price, timestamp, expiration, matcherFee)

  val orderV1Gen: Gen[Order] = for {
    (sender, matcher, pair, orderType, price, amount, timestamp, expiration, matcherFee) <- orderParamGen
  } yield TxHelpers.order(
    orderType = orderType,
    amountAsset = pair.amountAsset,
    priceAsset = pair.priceAsset,
    amount = price,
    price = amount,
    fee = matcherFee,
    sender = sender,
    matcher = matcher,
    timestamp = timestamp,
    expiration = expiration,
    version = 1.toByte
  )

  val orderV2Gen: Gen[Order] = for {
    (sender, matcher, pair, orderType, amount, price, timestamp, expiration, matcherFee) <- orderParamGen
  } yield TxHelpers.order(
    orderType = orderType,
    amountAsset = pair.amountAsset,
    priceAsset = pair.priceAsset,
    amount = amount,
    price = price,
    fee = matcherFee,
    sender = sender,
    matcher = matcher,
    timestamp = timestamp,
    expiration = expiration,
    version = 2.toByte
  )

  val orderV3Gen: Gen[Order] = for {
    (sender, matcher, pair, orderType, price, amount, timestamp, expiration, matcherFee) <- orderParamGen
    matcherFeeAssetId                                                                    <- assetIdGen
  } yield TxHelpers.order(
    orderType = orderType,
    amountAsset = pair.amountAsset,
    priceAsset = pair.priceAsset,
    feeAsset = Asset.fromCompatId(matcherFeeAssetId),
    amount = amount,
    price = price,
    fee = matcherFee,
    sender = sender,
    matcher = matcher,
    timestamp = timestamp,
    expiration = expiration,
    version = 3.toByte
  )

  val orderGen: Gen[Order] = Gen.oneOf(orderV1Gen, orderV2Gen, orderV3Gen)

  val arbitraryOrderGen: Gen[Order] = for {
    (sender, matcher, pair, orderType, _, _, _, _, _) <- orderParamGen
    amount                                            <- Arbitrary.arbitrary[Long]
    price                                             <- Arbitrary.arbitrary[Long]
    timestamp                                         <- Arbitrary.arbitrary[Long]
    expiration                                        <- Arbitrary.arbitrary[Long]
    matcherFee                                        <- Arbitrary.arbitrary[Long]
  } yield TxHelpers.order(
    orderType = orderType,
    amountAsset = pair.amountAsset,
    priceAsset = pair.priceAsset,
    amount = amount,
    price = price,
    fee = matcherFee,
    sender = sender,
    matcher = matcher,
    timestamp = timestamp,
    expiration = expiration,
    version = 1.toByte
  )

  val exchangeTransactionGen: Gen[ExchangeTransaction] = for {
    sender1                 <- accountGen
    sender2                 <- accountGen
    assetPair               <- assetPairGen
    buyerAnotherAsset       <- assetIdGen.map(Asset.fromCompatId)
    sellerAnotherAsset      <- assetIdGen.map(Asset.fromCompatId)
    buyerMatcherFeeAssetId  <- Gen.oneOf(assetPair.amountAsset, assetPair.priceAsset, buyerAnotherAsset, Hearth)
    sellerMatcherFeeAssetId <- Gen.oneOf(assetPair.amountAsset, assetPair.priceAsset, sellerAnotherAsset, Hearth)
    r <- Gen.oneOf(
      exchangeV1GeneratorP(sender1, sender2, assetPair.amountAsset, assetPair.priceAsset),
      exchangeV2GeneratorP(
        buyer = sender1,
        seller = sender2,
        amountAssetId = assetPair.amountAsset,
        priceAssetId = assetPair.priceAsset,
        buyMatcherFeeAssetId = buyerMatcherFeeAssetId,
        sellMatcherFeeAssetId = sellerMatcherFeeAssetId
      )
    )
  } yield r

  def exchangeV1GeneratorP(
      buyer: SigningKey,
      seller: SigningKey,
      amountAssetId: Asset,
      priceAssetId: Asset,
      fixedMatcherFee: Option[Long] = None,
      fixedMatcher: Option[SigningKey] = None
  ): Gen[ExchangeTransaction] =
    for {
      (_, genMatcher, _, _, amount1, price, timestamp, expiration, genMatcherFee) <- orderParamGen
      amount2: Long                                                               <- matcherAmountGen
      matchedAmount: Long <- Gen.choose(Math.min(amount1, amount2) / 2000, Math.min(amount1, amount2) / 1000)
      assetPair = AssetPair(amountAssetId, priceAssetId)
    } yield {
      val matcherFee = fixedMatcherFee.getOrElse(genMatcherFee)
      val matcher    = fixedMatcher.getOrElse(genMatcher)
      val o1 =
        TxHelpers
          .buy(
            1: Byte,
            buyer,
            PublicKey(matcher.publicKey),
            assetPair,
            amount1,
            price,
            timestamp,
            expiration,
            matcherFee,
            priceMode = OrderPriceMode.Default
          )
          .explicitGet()
      val o2 = TxHelpers
        .sell(
          1: Byte,
          seller,
          PublicKey(matcher.publicKey),
          assetPair,
          amount2,
          price,
          timestamp,
          expiration,
          matcherFee,
          priceMode = OrderPriceMode.Default
        )
        .explicitGet()
      val buyFee  = (BigInt(matcherFee) * BigInt(matchedAmount) / BigInt(amount1)).longValue
      val sellFee = (BigInt(matcherFee) * BigInt(matchedAmount) / BigInt(amount2)).longValue
      val trans =
        ExchangeTransaction
          .create(o1, o2, matchedAmount, price, buyFee, sellFee, (buyFee + sellFee) / 2, expiration - 100)
          .map(_.signWith(matcher))
          .explicitGet()

      trans
    }

  private type OrderConstructor = (SigningKey, PublicKey, AssetPair, Long, Long, Long, Long, Long) => Order

  def exchangeV2GeneratorP(
      buyer: SigningKey,
      seller: SigningKey,
      amountAssetId: Asset,
      priceAssetId: Asset,
      fixedMatcherFee: Option[Long] = None,
      orderVersions: Set[Byte] = Set(1, 2, 3),
      buyMatcherFeeAssetId: Asset = Hearth,
      sellMatcherFeeAssetId: Asset = Hearth,
      fixedMatcher: Option[SigningKey] = None
  ): Gen[ExchangeTransaction] = {
    def mkBuyOrder(version: Byte): OrderConstructor = (version: @unchecked) match {
      case Order.V1 => TxHelpers.buy(Order.V1, _, _, _, _, _, _, _, _).explicitGet()
      case Order.V2 => TxHelpers.buy(Order.V2, _, _, _, _, _, _, _, _).explicitGet()
      case Order.V3 => TxHelpers.buy(Order.V3, _, _, _, _, _, _, _, _, buyMatcherFeeAssetId).explicitGet()
    }

    def mkSellOrder(version: Byte): OrderConstructor = (version: @unchecked) match {
      case Order.V1 => TxHelpers.sell(Order.V1, _, _, _, _, _, _, _, _).explicitGet()
      case Order.V2 => TxHelpers.sell(Order.V2, _, _, _, _, _, _, _, _).explicitGet()
      case Order.V3 => TxHelpers.sell(Order.V3, _, _, _, _, _, _, _, _, sellMatcherFeeAssetId).explicitGet()
    }

    for {
      (_, generatedMatcher, _, _, amount1, price, timestamp, expiration, generatedMatcherFee) <- orderParamGen
      amount2: Long                                                                           <- matcherAmountGen
      matcher    = fixedMatcher.getOrElse(generatedMatcher)
      matcherFee = fixedMatcherFee.getOrElse(generatedMatcherFee)
      matchedAmount: Long <- Gen.choose(Math.min(amount1, amount2) / 2000, Math.min(amount1, amount2) / 1000)
      assetPair = AssetPair(amountAssetId, priceAssetId)
      mkO1 <- Gen.oneOf(orderVersions.map(mkBuyOrder).toSeq)
      mkO2 <- Gen.oneOf(orderVersions.map(mkSellOrder).toSeq)
    } yield {

      val buyFee  = (BigInt(matcherFee) * BigInt(matchedAmount) / BigInt(amount1)).longValue
      val sellFee = (BigInt(matcherFee) * BigInt(matchedAmount) / BigInt(amount2)).longValue

      val o1 = mkO1(buyer, PublicKey(matcher.publicKey), assetPair, amount1, price, timestamp, expiration, matcherFee)
      val o2 = mkO2(seller, PublicKey(matcher.publicKey), assetPair, amount2, price, timestamp, expiration, matcherFee)

      ExchangeTransaction
        .create(o1, o2, matchedAmount, price, buyFee, sellFee, (buyFee + sellFee) / 2, expiration - 100)
        .map(_.signWith(matcher))
        .explicitGet()
    }
  }

  val randomTransactionGen: Gen[Transaction & ProvenTransaction] = (for {
    tr <- transferV1Gen
    xt <- exchangeTransactionGen
    tx <- Gen.oneOf(tr, xt)
  } yield tx).label("random transaction")

  def randomTransactionsGen(count: Int): Gen[Seq[Transaction]] =
    for {
      transactions <- Gen.listOfN(count, randomTransactionGen)
    } yield transactions
}

trait TransactionGen extends TransactionGenBase { suite: Suite => }
