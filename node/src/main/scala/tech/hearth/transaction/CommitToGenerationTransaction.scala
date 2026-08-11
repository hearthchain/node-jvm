package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.crypto.bls.{BlsKeyPair, BlsPublicKey, BlsSignature}
import tech.hearth.lang.ValidationError
import tech.hearth.state.Height
import tech.hearth.transaction.serialization.impl.BaseTxJson
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.CommitToGenerationTxValidator
import monix.eval.Coeval
import tech.hearth.crypto.{Ecvrf, VrfKey}
import play.api.libs.json.*

/** @param vrfPublicKey
  *   The generator's VRF public key, which its blocks' generation signatures are verified against. It is registered
  *   here because a VRF key is derived independently of the account's signing key, so it can't be recovered from the
  *   block header alone.
  * @param vrfCommitmentSignature
  *   Proof that the sender holds the corresponding VRF secret key. Unlike the BLS commitmentSignature this is not
  *   needed to keep the scheme sound - VRF proofs are never aggregated, so there is no rogue-key attack, and
  *   registering a key you don't hold only stops you from mining. It catches a misconfigured key at commit time
  *   instead of leaving a dead generator slot for a whole period.
  */
final case class CommitToGenerationTransaction(
    sender: PublicKey,
    endorserPublicKey: BlsPublicKey,
    vrfPublicKey: ByteStr,
    generationPeriodStart: Height,
    timestamp: TxTimestamp,
    fee: TxPositiveAmount,
    commitmentSignature: BlsSignature,
    vrfCommitmentSignature: ByteStr,
    proofs: Proofs,
    override val chainId: Byte
) extends Transaction(TransactionType.CommitToGeneration)
    with ProvenTransaction
    with TxWithFee.InHearth
    with FastHashId {

  override type T = CommitToGenerationTransaction

  override def addProof(proof: ByteStr): CommitToGenerationTransaction = copy(proofs = proofs.add(proof))

  override val json: Coeval[JsObject] =
    Coeval.evalOnce(
      BaseTxJson.toJson(this) ++ Json.obj(
        "endorserPublicKey"      -> endorserPublicKey.base16,
        "vrfPublicKey"           -> vrfPublicKey.toString,
        "generationPeriodStart"  -> generationPeriodStart,
        "commitmentSignature"    -> commitmentSignature.base16,
        "vrfCommitmentSignature" -> vrfCommitmentSignature.toString
      )
    )

  lazy val popMessage: Array[Byte] = CommitToGenerationTransaction.mkPopMessage(endorserPublicKey, generationPeriodStart)

  lazy val vrfPopMessage: Array[Byte] = CommitToGenerationTransaction.mkVrfPopMessage(vrfPublicKey, generationPeriodStart)
}

object CommitToGenerationTransaction {
  val DepositInEmbers = 100_00000000L

  implicit val validator: TxValidator[CommitToGenerationTransaction] = CommitToGenerationTxValidator

  implicit def signed(tx: CommitToGenerationTransaction, privateKey: PrivateKey): CommitToGenerationTransaction =
    tx.copy(proofs = Proofs(crypto.sign(privateKey, tx.bodyBytes())))

  def mkPopSignature(blsKeyPair: BlsKeyPair, generationPeriodStart: Height): BlsSignature =
    blsKeyPair.sign(mkPopMessage(blsKeyPair.publicKey, generationPeriodStart))

  def mkPopMessage(blsPublicKey: BlsPublicKey, generationPeriodStart: Height): Array[Byte] =
    blsPublicKey.arr ++ generationPeriodStart.toByteArray

  /** The VRF proof of possession: an ECVRF proof over the key being registered, verifiable with that key alone. */
  def mkVrfPopSignature(vrfKey: VrfKey, generationPeriodStart: Height): ByteStr =
    ByteStr(Ecvrf.prove(vrfKey, mkVrfPopMessage(ByteStr(vrfKey.publicKey()), generationPeriodStart)).proof().bytes())

  def mkVrfPopMessage(vrfPublicKey: ByteStr, generationPeriodStart: Height): Array[Byte] =
    vrfPublicKey.arr ++ generationPeriodStart.toByteArray

  def create(
      sender: PublicKey,
      endorserPublicKey: BlsPublicKey,
      vrfPublicKey: ByteStr,
      generationPeriodStart: Height,
      timestamp: TxTimestamp,
      feeInHearth: Long,
      commitmentSignature: BlsSignature,
      vrfCommitmentSignature: ByteStr,
      proofs: Proofs,
      chainId: Byte
  ): Either[ValidationError, CommitToGenerationTransaction] =
    for {
      feeInHearth <- TxPositiveAmount(feeInHearth)(TxValidationError.InsufficientFee)
      tx <- CommitToGenerationTransaction(
        sender,
        endorserPublicKey,
        vrfPublicKey,
        generationPeriodStart,
        timestamp,
        feeInHearth,
        commitmentSignature,
        vrfCommitmentSignature,
        proofs,
        chainId
      ).validatedEither
    } yield tx
}
