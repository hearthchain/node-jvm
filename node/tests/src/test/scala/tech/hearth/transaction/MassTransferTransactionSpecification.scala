package tech.hearth.transaction

import tech.hearth.account.{AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.*
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.transfer.MassTransferTransaction.{MaxTransferCount, ParsedTransfer, Transfer}
import play.api.libs.json.Json
import tech.hearth.crypto.{Crypto, SigningKey}

import java.nio.charset.StandardCharsets
import scala.util.Random

class MassTransferTransactionSpecification extends PropSpec {

  private val massTransferTxSupportedVersions: Seq[Byte] = Seq(1, 2)

  private val sender    = SigningKey.fromSeed(Crypto.defaultBackend().sha256("sender".getBytes(StandardCharsets.UTF_8)))
  private val recipient = SigningKey.fromSeed(Crypto.defaultBackend().sha256("recipient".getBytes(StandardCharsets.UTF_8)))

  private val asset = IssuedAsset(ByteStr((1 to AssetIdLength).map(_.toByte).to(Array)))

  private val proofs = Seq(
    Seq.empty,
    Seq(ByteStr.empty),
    Seq(ByteStr(Random.nextBytes(Proofs.MaxProofSize))),
    (1 to Proofs.MaxProofs).map(_ => ByteStr.empty),
    (1 to Proofs.MaxProofs).map(_ => ByteStr(Random.nextBytes(Proofs.MaxProofSize)))
  )

  private val massTransfers = for {
    chainId   <- Seq(Byte.MinValue, 0: Byte, AddressScheme.current.chainId, Byte.MaxValue)
    version   <- massTransferTxSupportedVersions
    asset     <- Seq(Waves, asset)
    recipient <- Seq(recipient.toAddress)
    fee       <- Seq(1, Long.MaxValue)
    transfers <- Seq(
      Seq.empty,
      Seq(ParsedTransfer(recipient, TxNonNegativeAmount.unsafeFrom(Long.MaxValue - fee))),
      (1 to MaxTransferCount).map(_ => ParsedTransfer(recipient, TxNonNegativeAmount.unsafeFrom(1 / MaxTransferCount)))
    )
    attachment <- Seq(ByteStr.empty, ByteStr(Random.nextBytes(TransferTransaction.MaxAttachmentSize)))
    proofs     <- proofs
  } yield (chainId, version, asset, recipient, fee, transfers, attachment, proofs)

  property("property validation") {
    import MassTransferTransaction.create

    val timestamp = 1L

    val (_, _, assetId, _, fee, transfers, attachment, proofs) = massTransfers.head

    val tooManyTransfers   = List.fill(MaxTransferCount + 1)(ParsedTransfer(sender.toAddress, TxNonNegativeAmount.unsafeFrom(1L)))
    val tooManyTransfersEi = create(PublicKey(sender.publicKey), asset, tooManyTransfers, fee, timestamp, attachment, proofs)
    tooManyTransfersEi shouldBe Left(GenericError(s"Number of transfers ${tooManyTransfers.length} is greater than $MaxTransferCount"))

    val oneHalf    = Long.MaxValue / 2 + 1
    val overflow   = List.fill(2)(ParsedTransfer(sender.toAddress, TxNonNegativeAmount.unsafeFrom(oneHalf)))
    val overflowEi = create(PublicKey(sender.publicKey), assetId, overflow, fee, timestamp, attachment, proofs)
    overflowEi shouldBe Left(TxValidationError.OverflowError)

    val feeOverflow   = List(ParsedTransfer(sender.toAddress, TxNonNegativeAmount.unsafeFrom(oneHalf)))
    val feeOverflowEi = create(PublicKey(sender.publicKey), assetId, feeOverflow, oneHalf, timestamp, attachment, proofs)
    feeOverflowEi shouldBe Left(TxValidationError.OverflowError)

    val longAttachment   = ByteStr(Array.fill(TransferTransaction.MaxAttachmentSize + 1)(1: Byte))
    val longAttachmentEi = create(PublicKey(sender.publicKey), assetId, transfers, fee, timestamp, longAttachment, proofs)
    longAttachmentEi shouldBe Left(
      TxValidationError.TooBigInBytes(
        s"Invalid attachment. Length ${TransferTransaction.MaxAttachmentSize + 1} bytes exceeds maximum of ${TransferTransaction.MaxAttachmentSize} bytes."
      )
    )

    val noFeeEi = create(PublicKey(sender.publicKey), assetId, feeOverflow, 0, timestamp, attachment, proofs)
    noFeeEi shouldBe Left(TxValidationError.InsufficientFee)

    val negativeFeeEi = create(PublicKey(sender.publicKey), assetId, feeOverflow, -100, timestamp, attachment, proofs)
    negativeFeeEi shouldBe Left(TxValidationError.InsufficientFee)
  }

  property("JSON format validation") {
    val js = Json.parse("""{
      "type": 6,
      "id": "wsBzuWz6FsJMrKEVGsdi74swqe13X2acFzFJ2P74mq1",
      "fee": 200000,
      "feeAssetId": null,
      "timestamp": 1518091313964,
      "chainId": 84,
      "sender": "thrth1ryd2f987gg464uf4q5jte5rcmc2xgq6kr3qe39",
      "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
      "proofs": [
        "FXMNu3ecy5zBjn9b69VtpuYRwxjCbxdkZ3xZpLzB8ZeFDvcgTkmEDrD29wtGYRPtyLS3LPYrL2d5UM6TpFBMUGQ"
      ],
      "assetId": null,
      "attachment": "59QuUcqP6p",
      "transferCount": 2,
      "totalAmount": 300000000,
      "transfers": [
        {
          "recipient": "thrth1a4wdg3n3hg6ppf35qe6t9d3sw97n853rv4m3j6",
          "amount": 100000000
        },
        {
          "recipient": "thrth1a4wdg3n3hg6ppf35qe6t9d3sw97n853rv4m3j6",
          "amount": 200000000
        }
      ]
    }""")

    val transfers = MassTransferTransaction
      .parseTransfersList(
        List(
          Transfer("thrth1a4wdg3n3hg6ppf35qe6t9d3sw97n853rv4m3j6", 100000000L),
          Transfer("thrth1a4wdg3n3hg6ppf35qe6t9d3sw97n853rv4m3j6", 200000000L)
        )
      )
      .explicitGet()

    val tx = MassTransferTransaction
      .create(
        PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
        Waves,
        transfers,
        200000,
        1518091313964L,
        ByteStr.decodeBase58("59QuUcqP6p").get,
        Proofs(Seq(ByteStr.decodeBase58("FXMNu3ecy5zBjn9b69VtpuYRwxjCbxdkZ3xZpLzB8ZeFDvcgTkmEDrD29wtGYRPtyLS3LPYrL2d5UM6TpFBMUGQ").get))
      )
      .explicitGet()

    js shouldEqual tx.json()
  }
}
