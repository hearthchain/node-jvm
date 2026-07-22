package com.wavesplatform.transaction

import com.wavesplatform.api.http.requests.*
import com.wavesplatform.crypto.bls.BlsKeyPair
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.state.Height
import com.wavesplatform.transaction.TxValidationError.*
import com.wavesplatform.wallet.Wallet
import play.api.libs.json.*
import supranational.blst.SecretKey
import tech.hearth.crypto.SigningKey

object TransactionFactory {
  def parseRequestAndSign(
      request: JsObject,
      signer: SigningKey,
      blsKey: Option[SecretKey],
      generationPeriodStart: => Option[Int]
  ): Either[ValidationError, Transaction] = {
    val overrides = Json.newBuilder
    if (!request.keys.contains("senderPublicKey")) {
      overrides += "senderPublicKey" -> signer.publicKey
    }

    val extendedRequest = if ((request \ "type").as[Int] == TransactionType.CommitToGeneration.id) {
      for {
        k <- blsKey.toRight(GenericError("Missing BLS secret key"))
        periodStart <- ((request \ "generationPeriodStart").asOpt[Int] orElse generationPeriodStart)
          .toRight(GenericError("missing generation period start"))
      } yield {
        val endorserKP = BlsKeyPair(k)
        overrides ++= Seq(
          "commitmentSignature"   -> CommitToGenerationTransaction.mkPopSignature(endorserKP, Height(periodStart)).base16,
          "generationPeriodStart" -> periodStart,
          "endorserPublicKey"     -> endorserKP.publicKey.base16
        )
        overrides.result()
      }
    } else Right(overrides.result())

    for {
      req <- extendedRequest
      tx  <- parseRequest(req ++ request)
    } yield tx.signWith(signer)
  }

  def parseRequestAndSign(
      request: JsObject,
      wallet: Wallet,
      signer: Option[String | SigningKey],
      blsKey: Option[SecretKey],
      generationPeriodStart: => Option[Int]
  ): Either[ValidationError, Transaction] =
    signer
      .fold((request \ "sender").asOpt[String].toRight(GenericError("invalid.sender")).flatMap(wallet.findPrivateKey)) {
        case signerAddress: String => wallet.findPrivateKey(signerAddress)
        case signerKP: SigningKey  => Right(signerKP)
      }
      .flatMap(signer => parseRequestAndSign(request, signer, blsKey, generationPeriodStart))

  def parseRequest(request: JsObject): Either[ValidationError, Transaction & ProvenTransaction] = {
    val overrides = Json.newBuilder
    if (!request.keys.contains("timestamp")) {
      overrides += "timestamp" -> System.currentTimeMillis()
    }
    if (!request.keys.contains("version")) {
      overrides += "version" -> 1
    }

    val jsv = overrides.result() ++ request

    val typeId  = (jsv \ "type").as[Byte]
    val version = (jsv \ "version").as[Byte]

    try {
      import TransactionType.*
      import cats.syntax.either.*
      val req = TransactionType.fromId(typeId) match {
        case Transfer           => jsv.as[TransferRequest].asRight
        case Lease              => jsv.as[LeaseRequest].asRight
        case LeaseCancel        => jsv.as[LeaseCancelRequest].asRight
        case MassTransfer       => jsv.as[MassTransferRequest].asRight
        case CommitToGeneration => jsv.as[CommitToGenerationRequest].asRight
        case Exchange           => jsv.as[ExchangeRequest].asRight
        case Genesis =>
          UnsupportedTransactionType.asLeft[TxBroadcastRequest[Transaction & ProvenTransaction]]
      }

      for {
        r  <- req
        tx <- r.toTx
      } yield tx
    } catch {
      case _: MatchError | _: NoSuchElementException => Left(UnsupportedTypeAndVersion(typeId, version))
    }
  }
}
