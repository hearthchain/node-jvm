package com.wavesplatform.lagonaki.mocks

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.*
import com.wavesplatform.transaction.{Transaction, TxHelpers}
import tech.hearth.crypto.SigningKey

import scala.util.{Random, Try}

object TestBlock {
  case class BlockWithSigner(block: Block, signer: SigningKey)

  /** The same account `withDomain` auto-commits as the genesis generator. DeterministicFinality only accepts blocks
    * from a committed generator, so blocks these helpers sign by default have to come from that very account -
    * otherwise every test building blocks here would need to commit a separate TestBlock key.
    */
  val defaultSigner: SigningKey = TxHelpers.defaultSigner

  val random: Random = new Random()

  def randomOfLength(length: Int): ByteStr = ByteStr(Array.fill(length)(random.nextInt().toByte))

  def randomSignature(): ByteStr = randomOfLength(SignatureLength)

  def sign(signer: SigningKey, b: Block): BlockWithSigner = {
    val x = Block
      .buildAndSign(
        b.header.timestamp,
        b.header.reference,
        b.header.baseTarget,
        b.header.generationSignature,
        b.transactionData,
        signer,
        b.header.featureVotes,
        b.header.stateHash,
        b.header.challengedHeader,
        b.header.finalizationVoting
      )

    BlockWithSigner(x.explicitGet(), signer)
  }

  def create(txs: Seq[Transaction]): BlockWithSigner = create(defaultSigner, txs)

  def create(signer: SigningKey, txs: Seq[Transaction]): BlockWithSigner =
    create(time = Try(txs.map(_.timestamp).max).getOrElse(0L), txs = txs, signer = signer)

  def create(signer: SigningKey, txs: Seq[Transaction], features: Seq[Short]): BlockWithSigner =
    create(time = Try(txs.map(_.timestamp).max).getOrElse(0), ref = randomSignature(), txs = txs, signer = signer, features = features)

  def create(time: Long, txs: Seq[Transaction]): BlockWithSigner = create(time, randomSignature(), txs, defaultSigner)

  def create(time: Long, txs: Seq[Transaction], signer: SigningKey): BlockWithSigner = create(time, randomSignature(), txs, signer)

  def create(
      time: Long,
      ref: ByteStr,
      txs: Seq[Transaction],
      signer: SigningKey = defaultSigner,
      features: Seq[Short] = Seq.empty[Short],
      stateHash: Option[ByteStr] = None,
      baseTarget: Long = 2L,
      challengedHeader: Option[ChallengedHeader] = None
  ): BlockWithSigner =
    sign(
      signer,
      Block.create(
        timestamp = time,
        reference = ref,
        baseTarget = baseTarget,
        generationSignature = ByteStr(Array.fill(Block.GenerationVRFSignatureLength)(0: Byte)),
        generator = PublicKey(signer.publicKey),
        featureVotes = features,
        transactionData = txs,
        stateHash = stateHash,
        challengedHeader = challengedHeader,
        finalizationVoting = None
      )
    )

  def withReference(ref: ByteStr): BlockWithSigner =
    sign(
      defaultSigner,
      Block(
        BlockHeader(
          timestamp = 0,
          ref,
          baseTarget = 2L,
          randomOfLength(Block.GenerationVRFSignatureLength),
          PublicKey(defaultSigner.publicKey),
          featureVotes = Seq.empty,
          transactionsRoot = ByteStr.empty,
          stateHash = None,
          challengedHeader = None,
          finalizationVoting = None
        ),
        ByteStr.empty,
        Seq.empty
      )
    )

  def withReferenceAndFeatures(ref: ByteStr, features: Seq[Short]): BlockWithSigner =
    sign(
      defaultSigner,
      Block.create(
        timestamp = 0,
        ref,
        baseTarget = 2L,
        randomOfLength(Block.GenerationVRFSignatureLength),
        PublicKey(defaultSigner.publicKey),
        features,
        transactionData = Seq.empty,
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = None
      )
    )
}
