package tech.hearth.transaction

import com.google.common.primitives.Ints
import tech.hearth.TestValues
import tech.hearth.account.*
import tech.hearth.block.Block.BlockId
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.bls.BlsKeyPair
import tech.hearth.crypto.{DigestLength, SignatureLength}
import tech.hearth.lang.ValidationError
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.state.{Height, TransactionId}
import tech.hearth.test.*
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.assets.exchange.*
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.transfer.TransferTransaction.ParsedTransfer
import monix.execution.atomic.AtomicLong
import tech.hearth.settings.MiningAccount as MiningAccountSettings
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
    Proofs(ByteStr.decodeBase16(sig).get)

  def transfer(
      from: SigningKey = defaultSigner,
      to: Address = secondAddress,
      amount: Long = 1.hearth,
      asset: Asset = Hearth,
      fee: Long = TestValues.fee,
      feeAsset: Asset = Hearth,
      attachment: ByteStr = ByteStr.empty,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): TransferTransaction =
    TransferTransaction
      .create(
        PublicKey(from.publicKey),
        asset,
        Seq(ParsedTransfer(to, TxNonNegativeAmount.unsafeFrom(amount))),
        fee,
        timestamp,
        attachment,
        Proofs.empty,
        chainId,
        feeAsset
      )
      .map(_.signWith(from))
      .explicitGet()

  def transferUnsigned(
      from: SigningKey = defaultSigner,
      to: Address = secondAddress,
      amount: Long = 1.hearth,
      asset: Asset = Hearth,
      fee: Long = TestValues.fee,
      feeAsset: Asset = Hearth,
      chainId: Byte = AddressScheme.current.chainId
  ): TransferTransaction =
    TransferTransaction(
      PublicKey(from.publicKey),
      asset,
      Seq(ParsedTransfer(to, TxNonNegativeAmount.unsafeFrom(amount))),
      TxPositiveAmount.unsafeFrom(fee),
      feeAsset,
      timestamp,
      ByteStr.empty,
      Proofs.empty,
      chainId
    )

  def massTransfer(
      from: SigningKey = defaultSigner,
      to: Seq[(Address, Long)] = Seq(secondAddress -> 1.hearth),
      asset: Asset = Hearth,
      fee: Long = FeeConstants(TransactionType.Transfer) * FeeUnit,
      feeAsset: Asset = Hearth,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): TransferTransaction =
    TransferTransaction
      .create(
        PublicKey(from.publicKey),
        asset,
        to.map { case (r, a) => ParsedTransfer(r, TxNonNegativeAmount.unsafeFrom(a)) },
        fee,
        timestamp,
        ByteStr.empty,
        Proofs.empty,
        chainId,
        feeAsset
      )
      .map(_.signWith(from))
      .explicitGet()

  def orderV3(orderType: OrderType, asset: Asset, feeAsset: Asset = Hearth): Order = {
    order(orderType, asset, Hearth, feeAsset)
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
      matcherFeeAssetId: Asset = Asset.Hearth,
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
      matcherFeeAssetId: Asset = Hearth,
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
      matcherFeeAssetId: Asset = Hearth,
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
      feeAsset: Asset = Hearth,
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
      amount: Long = 10.hearth,
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

  /** The `hearth.miner.accounts` entry for one of these signers: the seeds a node has to be configured with for its
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

  // StartBoost/BindApiKey/Reserve/Withdraw/Settle/UpdateCollateral have no implemented semantics yet (see
  // TransactionDiffer); these helpers only exercise the wire-format (protobuf/JSON) plumbing.

  def startBoost(
      sender: SigningKey = defaultSigner,
      validator: Address = secondAddress,
      tdxQuote: ByteStr = ByteStr.empty,
      generationPeriodStart: Height = Height(1),
      fee: Long = FeeConstants(TransactionType.StartBoost) * FeeUnit,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): StartBoostTransaction =
    StartBoostTransaction
      .create(PublicKey(sender.publicKey), validator, tdxQuote, generationPeriodStart, fee, timestamp, Proofs.empty, chainId)
      .map(_.signWith(sender))
      .explicitGet()

  def bindApiKey(
      sender: SigningKey = defaultSigner,
      enclavePublicKey: ByteStr = ByteStr.empty,
      encryptedApiKey: ByteStr = ByteStr.empty,
      fee: Long = FeeConstants(TransactionType.BindApiKey) * FeeUnit,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): BindApiKeyTransaction =
    BindApiKeyTransaction
      .create(PublicKey(sender.publicKey), enclavePublicKey, encryptedApiKey, fee, timestamp, Proofs.empty, chainId)
      .map(_.signWith(sender))
      .explicitGet()

  def reserve(
      sender: SigningKey = defaultSigner,
      asset: Asset = Hearth,
      amount: Long = 1.hearth,
      miner: Address = secondAddress,
      feeAsset: Asset = Hearth,
      fee: Long = FeeConstants(TransactionType.Reserve) * FeeUnit,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): ReserveTransaction =
    ReserveTransaction
      .create(PublicKey(sender.publicKey), asset, amount, miner, feeAsset, fee, timestamp, Proofs.empty, chainId)
      .map(_.signWith(sender))
      .explicitGet()

  def withdraw(
      sender: SigningKey = defaultSigner,
      fromMiner: Address = secondAddress,
      asset: Asset = Hearth,
      amount: Long = 1.hearth,
      feeAsset: Asset = Hearth,
      fee: Long = FeeConstants(TransactionType.Withdraw) * FeeUnit,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): WithdrawTransaction =
    WithdrawTransaction
      .create(PublicKey(sender.publicKey), fromMiner, asset, amount, feeAsset, fee, timestamp, Proofs.empty, chainId)
      .map(_.signWith(sender))
      .explicitGet()

  def settle(
      sender: SigningKey = defaultSigner,
      senderAddress: Address = defaultAddress,
      fee: Long = FeeConstants(TransactionType.Settle) * FeeUnit,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): SettleTransaction =
    SettleTransaction
      .create(PublicKey(sender.publicKey), senderAddress, fee, timestamp, Proofs.empty, chainId)
      .map(_.signWith(sender))
      .explicitGet()

  def updateCollateral(
      sender: SigningKey = defaultSigner,
      rootCaCrl: Option[ByteStr] = None,
      pckCrl: Option[ByteStr] = None,
      tcbInfo: Option[ByteStr] = None,
      qeIdentity: Option[ByteStr] = None,
      tcbSigningIssuerChain: Option[ByteStr] = None,
      fee: Long = FeeConstants(TransactionType.UpdateCollateral) * FeeUnit,
      timestamp: TxTimestamp = timestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): UpdateCollateralTransaction =
    UpdateCollateralTransaction
      .create(
        PublicKey(sender.publicKey),
        rootCaCrl,
        pckCrl,
        tcbInfo,
        qeIdentity,
        tcbSigningIssuerChain,
        fee,
        timestamp,
        Proofs.empty,
        chainId
      )
      .map(_.signWith(sender))
      .explicitGet()

  def randomId: TransactionId = TransactionId(ByteStr(Array.fill(DigestLength)(ThreadLocalRandom.current().nextInt(Byte.MaxValue).toByte)))
  def randomBlockId: BlockId  = ByteStr(Array.fill(SignatureLength)(ThreadLocalRandom.current().nextInt(Byte.MaxValue).toByte))
}
