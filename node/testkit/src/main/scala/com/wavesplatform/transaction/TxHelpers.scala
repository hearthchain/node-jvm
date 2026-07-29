package com.wavesplatform.transaction

import com.google.common.primitives.Ints
import com.wavesplatform.TestValues
import com.wavesplatform.account.*
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.bls.BlsKeyPair
import com.wavesplatform.crypto.{DigestLength, SignatureLength}
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import com.wavesplatform.state.{Height, TransactionId}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.transaction.assets.exchange.*
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import monix.execution.atomic.AtomicLong
import com.wavesplatform.settings.MiningAccount as MiningAccountSettings
import tech.hearth.crypto.{Crypto, Hex, SigningKey, VrfKey}

import java.util.concurrent.ThreadLocalRandom

object TxHelpers {

  /** The seed `signer(i)` is derived from.
    *
    * `MinerSettings` configures a mining account with a hex-encoded *seed*, not a key, and a seed cannot be recovered
    * from a `SigningKey` - so a test that has to configure the miner needs this rather than the key itself.
    */
  def signerSeed(i: Int): Array[Byte] = Crypto.defaultBackend().sha256(Ints.toByteArray(i))

  def signer(i: Int): SigningKey = SigningKey.fromSeed(signerSeed(i))
  def address(i: Int): Address   = signer(i).toAddress

  val defaultSigner: SigningKey = signer(0)
  val defaultAddress: Address   = defaultSigner.toAddress
  val secondSigner: SigningKey  = signer(1)
  val secondAddress: Address    = secondSigner.toAddress

  // BlsKeyPair.fromSeed yields a zero key (a point at infinity) for a seed shorter than 32 bytes
  val defaultBlsKey: BlsKeyPair = BlsKeyPair.fromSeed(Crypto.defaultBackend().sha256(Ints.toByteArray(1)))

  /** A generator's VRF key, derived from its public key. A VRF key can only be committed once, so deriving it per
    * sender keeps two generators from colliding on the same key.
    */
  def vrfSeedOf(sender: SigningKey): Array[Byte] = Crypto.defaultBackend().sha256(sender.publicKey())

  def vrfKeyOf(sender: SigningKey): VrfKey = VrfKey.fromSeed(vrfSeedOf(sender))

  /** The VRF key blocks are generated with by default, see history.defaultVrfKey. A generator has to commit to this one
    * for its blocks to verify, which is why it is the key [[vrfKeyOf]] derives for the default signer.
    */
  val defaultVrfKey: VrfKey = vrfKeyOf(defaultSigner)

  def accountSeqGenerator(numberAccounts: Int, amount: Long): Seq[ParsedTransfer] = {
    val firstAccountNum = 100
    val lastAccountNum  = firstAccountNum + numberAccounts
    val accountsSeq = (firstAccountNum until lastAccountNum).map { num =>
      val recipient = signer(num).toAddress
      ParsedTransfer(recipient, TxNonNegativeAmount.unsafeFrom(amount))
    }
    accountsSeq
  }

  val matcher: SigningKey = defaultSigner

  private val lastTimestamp = AtomicLong(System.currentTimeMillis())
  def timestamp: Long       = lastTimestamp.getAndIncrement()

  @throws[IllegalArgumentException]
  def signature(sig: String): Proofs =
    Proofs(ByteStr.decodeBase58(sig).get)

  def transfer(
      from: SigningKey = defaultSigner,
      to: Address = secondAddress,
      amount: Long = 1.waves,
      asset: Asset = Waves,
      fee: Long = TestValues.fee,
      feeAsset: Asset = Waves,
      attachment: ByteStr = ByteStr.empty,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): TransferTransaction =
    TransferTransaction
      .create(PublicKey(from.publicKey), to, asset, amount, feeAsset, fee, attachment, timestamp, Proofs.empty, chainId)
      .map(_.signWith(from))
      .explicitGet()

  def transferUnsigned(
      from: SigningKey = defaultSigner,
      to: Address = secondAddress,
      amount: Long = 1.waves,
      asset: Asset = Waves,
      fee: Long = TestValues.fee,
      feeAsset: Asset = Waves,
      chainId: Byte = AddressScheme.current.chainId
  ): TransferTransaction =
    TransferTransaction(
      PublicKey(from.publicKey),
      to,
      asset,
      TxPositiveAmount.unsafeFrom(amount),
      feeAsset,
      TxPositiveAmount.unsafeFrom(fee),
      ByteStr.empty,
      timestamp,
      Proofs.empty,
      chainId
    )

  def massTransfer(
      from: SigningKey = defaultSigner,
      to: Seq[(Address, Long)] = Seq(secondAddress -> 1.waves),
      asset: Asset = Waves,
      fee: Long = FeeConstants(TransactionType.MassTransfer) * FeeUnit,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): MassTransferTransaction =
    MassTransferTransaction
      .create(
        PublicKey(from.publicKey),
        asset,
        to.map { case (r, a) => MassTransferTransaction.ParsedTransfer(r, TxNonNegativeAmount.unsafeFrom(a)) },
        fee,
        timestamp,
        ByteStr.empty,
        Proofs.empty,
        chainId
      )
      .map(_.signWith(from))
      .explicitGet()

  def orderV3(orderType: OrderType, asset: Asset, feeAsset: Asset = Waves): Order = {
    order(orderType, asset, Waves, feeAsset)
  }

  def selfSigned(
      version: Byte,
      sender: SigningKey,
      matcher: PublicKey,
      assetPair: AssetPair,
      orderType: OrderType,
      amount: Long,
      price: Long,
      timestamp: TxTimestamp,
      expiration: TxTimestamp,
      matcherFee: Long,
      matcherFeeAssetId: Asset = Asset.Waves,
      priceMode: OrderPriceMode = OrderPriceMode.Default,
      attachment: Option[ByteStr] = None
  ): Either[ValidationError, Order] =
    for {
      amount     <- TxExchangeAmount(amount)(GenericError(s"Order validation error: ${TxExchangeAmount.errMsg}"))
      price      <- TxOrderPrice(price)(GenericError(s"Order validation error: ${TxOrderPrice.errMsg}"))
      matcherFee <- TxMatcherFee(matcherFee)(GenericError(s"Order validation error: ${TxMatcherFee.errMsg}"))
    } yield {
      val o = Order(
        version,
        OrderAuthentication(PublicKey(sender.publicKey)),
        matcher,
        assetPair,
        orderType,
        amount,
        price,
        timestamp,
        expiration,
        matcherFee,
        matcherFeeAssetId,
        priceMode = priceMode,
        attachment = attachment
      )
      o.withProofs(Proofs(ByteStr(sender.sign(o.bodyBytes()))))
    }

  def buy(
      version: Byte,
      sender: SigningKey,
      matcher: PublicKey,
      pair: AssetPair,
      amount: Long,
      price: Long,
      timestamp: TxTimestamp,
      expiration: TxTimestamp,
      matcherFee: Long,
      matcherFeeAssetId: Asset = Waves,
      priceMode: OrderPriceMode = OrderPriceMode.Default,
      attachment: Option[ByteStr] = None
  ): Either[ValidationError, Order] =
    selfSigned(
      version,
      sender,
      matcher,
      pair,
      OrderType.BUY,
      amount,
      price,
      timestamp,
      expiration,
      matcherFee,
      matcherFeeAssetId,
      priceMode,
      attachment
    )

  def sell(
      version: Byte,
      sender: SigningKey,
      matcher: PublicKey,
      pair: AssetPair,
      amount: Long,
      price: Long,
      timestamp: TxTimestamp,
      expiration: TxTimestamp,
      matcherFee: Long,
      matcherFeeAssetId: Asset = Waves,
      priceMode: OrderPriceMode = OrderPriceMode.Default,
      attachment: Option[ByteStr] = None
  ): Either[ValidationError, Order] =
    selfSigned(
      version,
      sender,
      matcher,
      pair,
      OrderType.SELL,
      amount,
      price,
      timestamp,
      expiration,
      matcherFee,
      matcherFeeAssetId,
      priceMode,
      attachment
    )

  def order(
      orderType: OrderType,
      amountAsset: Asset,
      priceAsset: Asset,
      feeAsset: Asset = Waves,
      amount: Long = 1L,
      price: Long = 1L,
      priceMode: OrderPriceMode = OrderPriceMode.Default,
      fee: Long = 1L,
      sender: SigningKey = defaultSigner,
      matcher: SigningKey = defaultSigner,
      timestamp: TxTimestamp = timestamp,
      expiration: TxTimestamp = timestamp + 100000,
      version: Byte = Order.V3,
      attachment: Option[ByteStr] = None
  ): Order = {

    selfSigned(
      version,
      sender,
      PublicKey(matcher.publicKey),
      AssetPair(amountAsset, priceAsset),
      orderType,
      amount,
      price,
      timestamp,
      expiration,
      fee,
      feeAsset,
      priceMode,
      attachment
    )
      .explicitGet()
  }

  def exchangeFromOrders(
      order1: Order,
      order2: Order,
      matcher: SigningKey = defaultSigner,
      fee: Long = TestValues.fee,
      chainId: Byte = AddressScheme.current.chainId
  ): ExchangeTransaction = exchangeFromOrders(order1, order2, order1.price.value, matcher, fee, chainId)

  def exchangeFromOrders(
      order1: Order,
      order2: Order,
      price: Long,
      matcher: SigningKey,
      fee: Long,
      chainId: Byte
  ): ExchangeTransaction =
    ExchangeTransaction
      .create(
        order1,
        order2,
        order1.amount.value,
        price,
        order1.matcherFee.value,
        order2.matcherFee.value,
        fee,
        timestamp,
        chainId = chainId
      )
      .map(_.signWith(matcher))
      .explicitGet()

  def exchange(
      order1: Order,
      order2: Order,
      matcher: SigningKey = defaultSigner,
      amount: Long = 1L,
      price: Long = 1L,
      buyMatcherFee: Long = 1L,
      sellMatcherFee: Long = 1L,
      fee: Long = TestValues.fee,
      timestamp: Long = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): ExchangeTransaction =
    ExchangeTransaction
      .create(
        order1 = order1,
        order2 = order2,
        amount = amount,
        price = price,
        buyMatcherFee = buyMatcherFee,
        sellMatcherFee = sellMatcherFee,
        fee = fee,
        timestamp = timestamp,
        chainId = chainId
      )
      .map(_.signWith(matcher))
      .explicitGet()

  def lease(
      sender: SigningKey = defaultSigner,
      recipient: Address = secondAddress,
      amount: Long = 10.waves,
      fee: Long = FeeConstants(TransactionType.Lease) * FeeUnit,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): LeaseTransaction = {
    LeaseTransaction
      .create(chainId, PublicKey(sender.publicKey), recipient, amount, fee, timestamp, Proofs.empty)
      .map(_.signWith(sender))
      .explicitGet()
  }

  def leaseCancel(
      leaseId: ByteStr,
      sender: SigningKey = defaultSigner,
      fee: Long = FeeConstants(TransactionType.LeaseCancel) * FeeUnit,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): LeaseCancelTransaction = {
    LeaseCancelTransaction
      .create(PublicKey(sender.publicKey), leaseId, fee, timestamp, Proofs.empty, chainId)
      .map(_.signWith(sender))
      .explicitGet()
  }

  /** The endorser key defaults to one derived from the sender, so that every sender commits a distinct BLS key. */
  def commitToGeneration(
      generationPeriodStart: Height,
      sender: SigningKey = defaultSigner,
      timestamp: TxTimestamp = timestamp,
      fee: Long = TestValues.commitToGenerationFee,
      chainId: Byte = AddressScheme.current.chainId,
      // Defaults to the sender's own derived key, see vrfKeyOf
      vrfKey: Option[VrfKey] = None
  ): CommitToGenerationTransaction =
    commitToGenerationWithEndorserKey(generationPeriodStart, blsKeyOf(sender), sender, timestamp, fee, chainId, vrfKey)

  /** A generator's BLS key, derived per sender for the same reason as its VRF key. */
  def blsSeedOf(sender: SigningKey): Array[Byte] = Crypto.defaultBackend().sha256(sender.publicKey())

  def blsKeyOf(sender: SigningKey): BlsKeyPair = BlsKeyPair.fromSeed(blsSeedOf(sender))

  /** The `waves.miner.accounts` entry for one of these signers: the seeds a node has to be configured with for its
    * miner, endorser and API to act as `signer(i)`. `MinerImpl` builds its accounts from settings and nothing else, so
    * a test that expects a node to generate, endorse or commit as this account has to configure it here.
    */
  def miningAccountSettings(i: Int): MiningAccountSettings = {
    val signer = TxHelpers.signer(i)
    MiningAccountSettings(
      mnemonic = None,
      signingKey = Some(Hex.encode(signerSeed(i))),
      vrfKey = Some(Hex.encode(vrfSeedOf(signer))),
      blsKey = Some(Hex.encode(blsSeedOf(signer)))
    )
  }

  def commitToGenerationWithEndorserKey(
      generationPeriodStart: Height,
      endorserKp: BlsKeyPair,
      sender: SigningKey = defaultSigner,
      timestamp: TxTimestamp = timestamp,
      fee: Long = TestValues.commitToGenerationFee,
      chainId: Byte = AddressScheme.current.chainId,
      // Defaults to the sender's own derived key, see vrfKeyOf
      vrfKeyOpt: Option[VrfKey] = None
  ): CommitToGenerationTransaction = {
    val vrfKey = vrfKeyOpt.getOrElse(vrfKeyOf(sender))
    CommitToGenerationTransaction
      .create(
        PublicKey(sender.publicKey()),
        endorserKp.publicKey,
        ByteStr(vrfKey.publicKey()),
        generationPeriodStart,
        timestamp,
        fee,
        CommitToGenerationTransaction.mkPopSignature(endorserKp, generationPeriodStart),
        CommitToGenerationTransaction.mkVrfPopSignature(vrfKey, generationPeriodStart),
        Proofs.empty,
        chainId
      )
      .map(_.signWith(sender))
      .explicitGet()
  }

  def randomId: TransactionId = TransactionId(ByteStr(Array.fill(DigestLength)(ThreadLocalRandom.current().nextInt(Byte.MaxValue).toByte)))
  def randomBlockId: BlockId  = ByteStr(Array.fill(SignatureLength)(ThreadLocalRandom.current().nextInt(Byte.MaxValue).toByte))
}
