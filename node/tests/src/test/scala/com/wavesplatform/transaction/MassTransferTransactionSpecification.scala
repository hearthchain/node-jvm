package com.wavesplatform.transaction

import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base64
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.transaction.serialization.impl.{MassTransferTxSerializer, PBTransactionSerializer}
import com.wavesplatform.transaction.transfer.*
import com.wavesplatform.transaction.transfer.MassTransferTransaction.{MaxTransferCount, ParsedTransfer, Transfer}
import play.api.libs.json.{JsObject, Json}
import tech.hearth.crypto.{Crypto, SigningKey}

import java.nio.charset.StandardCharsets
import scala.util.{Random, Success}

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

  private val massTransfersTable = Table(
    ("chainId", "version", "asset", "recipient", "fee", "transfers", "attachment", "proofs"),
    massTransfers*
  )

  property("property validation") {
    import MassTransferTransaction.create

    val timestamp = 1L

    val (_, _, assetId, _, fee, transfers, attachment, proofs) = massTransfers.head

    val tooManyTransfers   = List.fill(MaxTransferCount + 1)(ParsedTransfer(sender.toAddress, TxNonNegativeAmount.unsafeFrom(1L)))
    val tooManyTransfersEi = create(1.toByte, PublicKey(sender.publicKey), asset, tooManyTransfers, fee, timestamp, attachment, proofs)
    tooManyTransfersEi shouldBe Left(GenericError(s"Number of transfers ${tooManyTransfers.length} is greater than $MaxTransferCount"))

    val oneHalf    = Long.MaxValue / 2 + 1
    val overflow   = List.fill(2)(ParsedTransfer(sender.toAddress, TxNonNegativeAmount.unsafeFrom(oneHalf)))
    val overflowEi = create(1.toByte, PublicKey(sender.publicKey), assetId, overflow, fee, timestamp, attachment, proofs)
    overflowEi shouldBe Left(TxValidationError.OverflowError)

    val feeOverflow   = List(ParsedTransfer(sender.toAddress, TxNonNegativeAmount.unsafeFrom(oneHalf)))
    val feeOverflowEi = create(1.toByte, PublicKey(sender.publicKey), assetId, feeOverflow, oneHalf, timestamp, attachment, proofs)
    feeOverflowEi shouldBe Left(TxValidationError.OverflowError)

    val longAttachment   = ByteStr(Array.fill(TransferTransaction.MaxAttachmentSize + 1)(1: Byte))
    val longAttachmentEi = create(1.toByte, PublicKey(sender.publicKey), assetId, transfers, fee, timestamp, longAttachment, proofs)
    longAttachmentEi shouldBe Left(
      TxValidationError.TooBigInBytes(
        s"Invalid attachment. Length ${TransferTransaction.MaxAttachmentSize + 1} bytes exceeds maximum of ${TransferTransaction.MaxAttachmentSize} bytes."
      )
    )

    val noFeeEi = create(1.toByte, PublicKey(sender.publicKey), assetId, feeOverflow, 0, timestamp, attachment, proofs)
    noFeeEi shouldBe Left(TxValidationError.InsufficientFee)

    val negativeFeeEi = create(1.toByte, PublicKey(sender.publicKey), assetId, feeOverflow, -100, timestamp, attachment, proofs)
    negativeFeeEi shouldBe Left(TxValidationError.InsufficientFee)

    val differentChainIds = Seq(
      ParsedTransfer(sender.toAddress, TxNonNegativeAmount.unsafeFrom(100)),
      ParsedTransfer(PublicKey(sender.publicKey).toAddress('?'.toByte), TxNonNegativeAmount.unsafeFrom(100))
    )
    val invalidChainIdEi = create(1.toByte, PublicKey(sender.publicKey), assetId, differentChainIds, 100, timestamp, attachment, proofs)
    invalidChainIdEi should produce("One of chain ids not match")

    val otherChainIds = Seq(
      ParsedTransfer(PublicKey(sender.publicKey).toAddress('?'.toByte), TxNonNegativeAmount.unsafeFrom(100)),
      ParsedTransfer(PublicKey(sender.publicKey).toAddress('?'.toByte), TxNonNegativeAmount.unsafeFrom(100))
    )
    val invalidOtherChainIdEi = create(1.toByte, PublicKey(sender.publicKey), assetId, otherChainIds, 100, timestamp, attachment, proofs)
    invalidOtherChainIdEi should produce("One of chain ids not match")
  }

  property("JSON format validation") {
    val js = Json.parse("""{
                       "type": 11,
                       "id": "ee44077c7354ccce547c401edc3cd87a8ae24bf1e69aa75bc07cf2618aeb6722",
                       "sender": "3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh",
                       "senderPublicKey": "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22",
                       "fee": 200000,
                       "feeAssetId": null,
                       "timestamp": 1518091313964,
                       "proofs": [
                       "0c863b41d8c03da0d9c07a645c120477b5d0644fc4ee2862fffbf7462cdda96d9a9693340d6249e8f7322ce39c61b781bcb271e3d5efdae0938083081088b289"],
                       "version": 1,
                       "assetId": null,
                       "attachment": "59QuUcqP6p",
                       "transferCount": 2,
                       "totalAmount": 300000000,
                       "transfers": [
                       {
                       "recipient": "3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh",
                       "amount": 100000000
                       },
                       {
                       "recipient": "3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh",
                       "amount": 200000000
                       }
                       ]
                       }
  """)

    val transfers = MassTransferTransaction
      .parseTransfersList(
        List(Transfer("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", 100000000L), Transfer("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", 200000000L))
      )
      .explicitGet()

    val tx = MassTransferTransaction
      .create(
        1.toByte,
        PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet(),
        Waves,
        transfers,
        200000,
        1518091313964L,
        ByteStr.decodeBase16("6d617373706179").get,
        Proofs(
          Seq(
            ByteStr
              .decodeBase16(
                "0c863b41d8c03da0d9c07a645c120477b5d0644fc4ee2862fffbf7462cdda96d9a9693340d6249e8f7322ce39c61b781bcb271e3d5efdae0938083081088b289"
              )
              .get
          )
        )
      )
      .explicitGet()

    js shouldEqual tx.json()
  }
}
