package tech.hearth.transaction

import tech.hearth.account.{Address, PublicKey}
import tech.hearth.api.http.requests.*
import tech.hearth.lang.ValidationError
import tech.hearth.mining.GeneratorKeys
import tech.hearth.state.Height
import tech.hearth.transaction.TxValidationError.*
import tech.hearth.wallet.Wallet
import play.api.libs.json.*
import tech.hearth.crypto.SigningKey

object TransactionFactory {

  /** Signs a request with the key of the account it names.
    *
    * A CommitToGeneration request is the one case that needs more than a signing key: it registers the account's own
    * generator keys, so it carries their public keys and a proof of possession for each. Those come from
    * `hearth.miner.accounts` through [[GeneratorKeys]], and so does the key it is signed with - an account commits to
    * generating for itself, and the wallet holds neither the generator keys nor, necessarily, that account.
    */
  def parseRequestAndSign(
      request: JsObject,
      wallet: Wallet,
      generatorKeys: GeneratorKeys,
      signer: Option[String | SigningKey],
      generationPeriodStart: => Option[Int]
  ): Either[ValidationError, Transaction] =
    if ((request \ "type").asOpt[Int].contains(TransactionType.CommitToGeneration.id))
      signCommitToGeneration(request, generatorKeys, signer, generationPeriodStart)
    else
      resolveSigner(request, wallet, signer).flatMap(signWith(request, _))

  private def resolveSigner(request: JsObject, wallet: Wallet, signer: Option[String | SigningKey]): Either[ValidationError, SigningKey] =
    signer.fold((request \ "sender").asOpt[String].toRight(GenericError("invalid.sender")).flatMap(wallet.findPrivateKey)) {
      case signerAddress: String => wallet.findPrivateKey(signerAddress)
      case signerKP: SigningKey  => Right(signerKP)
    }

  private def signCommitToGeneration(
      request: JsObject,
      generatorKeys: GeneratorKeys,
      signer: Option[String | SigningKey],
      generationPeriodStart: => Option[Int]
  ): Either[ValidationError, Transaction] =
    for {
      address <- signer.fold((request \ "sender").asOpt[String].toRight(GenericError("invalid.sender")))(Right(_)) match {
        case Right(addr: String)    => Address.fromString(addr)
        case Right(key: SigningKey) => Right(key.toAddress)
        case Left(err)              => Left(err)
      }
      signingKey <- generatorKeys
        .signingKey(address)
        .toRight(GenericError(s"$address is not one of this node's generators, see hearth.miner.accounts"))
      periodStart <- ((request \ "generationPeriodStart").asOpt[Int] orElse generationPeriodStart)
        .toRight(GenericError("missing generation period start"))
      commitment <- generatorKeys
        .commitment(address, Height(periodStart))
        .toRight(GenericError(s"$address is not one of this node's generators, see hearth.miner.accounts"))
      overrides = Json.obj(
        "senderPublicKey"        -> PublicKey(signingKey.publicKey()).toString,
        "generationPeriodStart"  -> periodStart,
        "endorserPublicKey"      -> commitment.endorserPublicKey.base16,
        "commitmentSignature"    -> commitment.commitmentSignature.base16,
        "vrfPublicKey"           -> commitment.vrfPublicKey.toString,
        "vrfCommitmentSignature" -> commitment.vrfCommitmentSignature.toString
      )
      tx <- parseRequest(overrides ++ request - "sender")
    } yield tx.signWith(signingKey)

  /** Signs with a key the caller already has. A CommitToGeneration request cannot be completed this way: its generator
    * public keys and proofs of possession come from `hearth.miner.accounts`, which a caller holding a lone signing key
    * does not have - such a request has to carry those fields itself.
    */
  def parseRequestAndSign(request: JsObject, signer: SigningKey): Either[ValidationError, Transaction] = signWith(request, signer)

  private def signWith(request: JsObject, signer: SigningKey): Either[ValidationError, Transaction] = {
    val overrides = Json.newBuilder
    if (!request.keys.contains("senderPublicKey")) {
      overrides += "senderPublicKey" -> PublicKey(signer.publicKey()).toString
    }
    parseRequest(overrides.result() ++ request).map(_.signWith(signer))
  }

  def parseRequest(request: JsObject): Either[ValidationError, Transaction & ProvenTransaction] = {
    val overrides = Json.newBuilder
    if (!request.keys.contains("timestamp")) {
      overrides += "timestamp" -> System.currentTimeMillis()
    }
    // A round-tripped transaction (e.g. one read back from /transactions/sign into a client-side model and
    // re-broadcast) can carry an explicit "version": null - the key is present, but not a usable value - since
    // nothing writes a version any more and a plain Option[Byte] writer renders None as null rather than omitting
    // the key. Checking key presence alone leaves that null in place, and then it (not this override) wins the
    // merge below, so the fallback has to trigger on it too.
    if ((request \ "version").asOpt[Byte].isEmpty) {
      overrides += "version" -> 1
    }

    val jsv = request ++ overrides.result()

    val typeId  = (jsv \ "type").as[Byte]
    val version = (jsv \ "version").as[Byte]

    try {
      import TransactionType.*
      import cats.syntax.either.*
      val req = TransactionType.fromId(typeId) match {
        case Transfer                                                                           => jsv.as[TransferRequest].asRight
        case Lease                                                                              => jsv.as[LeaseRequest].asRight
        case LeaseCancel                                                                        => jsv.as[LeaseCancelRequest].asRight
        case CommitToGeneration                                                                 => jsv.as[CommitToGenerationRequest].asRight
        case Exchange                                                                           => jsv.as[ExchangeRequest].asRight
        case Genesis | StartBoost | Reserve | BindApiKey | Settle | Withdraw | UpdateCollateral =>
          // StartBoost/Reserve/BindApiKey/Settle/Withdraw/UpdateCollateral are not yet signable through this REST
          // flow: their semantics (validation, state diff) are not implemented yet, see TransactionDiffer.
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
