package com.wavesplatform.lagonaki.mocks

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.*
import com.wavesplatform.transaction.Transaction
import tech.hearth.crypto.{Crypto, KeyTree, SigningKey}

import scala.util.{Random, Try}

object TestBlock {
  case class BlockWithSigner(block: Block, signer: SigningKey)

  val defaultSigner: SigningKey = KeyTree.signingKey(Crypto.defaultBackend().sha256(new Array[Byte](32)), 100)

  val random: Random = new Random()

  def randomOfLength(length: Int): ByteStr = ByteStr(Array.fill(length)(random.nextInt().toByte))

  def randomSignature(): ByteStr = randomOfLength(SignatureLength)

  def sign(signer: SigningKey, b: Block): BlockWithSigner = {
    val x = Block
      .buildAndSign(
        b.header.version,
        b.header.timestamp,
        b.header.reference,
        b.header.baseTarget,
        b.header.generationSignature,
        b.transactionData,
        signer,
        b.header.featureVotes,
        b.header.rewardVote,
        b.header.stateHash,
        b.header.challengedHeader,
        b.header.finalizationVoting
      )

    BlockWithSigner(x.explicitGet(), signer)
  }

  def create(txs: Seq[Transaction]): BlockWithSigner = create(defaultSigner, txs)

  def create(txs: Seq[Transaction], version: Byte): BlockWithSigner =
    create(time = Try(txs.map(_.timestamp).max).getOrElse(0L), ref = randomSignature(), txs = txs, version = version)

  def create(signer: SigningKey, txs: Seq[Transaction]): BlockWithSigner =
    create(time = Try(txs.map(_.timestamp).max).getOrElse(0L), txs = txs, signer = signer)

  def create(signer: SigningKey, txs: Seq[Transaction], features: Seq[Short]): BlockWithSigner =
    create(time = Try(txs.map(_.timestamp).max).getOrElse(0), ref = randomSignature(), txs = txs, signer = signer, version = 3, features = features)

  def create(time: Long, txs: Seq[Transaction]): BlockWithSigner = create(time, randomSignature(), txs, defaultSigner)

  def create(time: Long, txs: Seq[Transaction], signer: SigningKey): BlockWithSigner = create(time, randomSignature(), txs, signer)

  def create(
      time: Long,
      ref: ByteStr,
      txs: Seq[Transaction],
      signer: SigningKey = defaultSigner,
      version: Byte = 2,
      features: Seq[Short] = Seq.empty[Short],
      rewardVote: Long = -1L,
      stateHash: Option[ByteStr] = None,
      baseTarget: Long = 2L,
      challengedHeader: Option[ChallengedHeader] = None
  ): BlockWithSigner =
    sign(
      signer,
      Block.create(
        timestamp = time,
        version = version,
        reference = ref,
        baseTarget = baseTarget,
        generationSignature =
          if (version < Block.ProtoBlockVersion) ByteStr(Array.fill(Block.GenerationSignatureLength)(0: Byte))
          else ByteStr(Array.fill(Block.GenerationVRFSignatureLength)(0: Byte)),
        generator = PublicKey(signer.publicKey),
        featureVotes = features,
        rewardVote = rewardVote,
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
          version = 1.toByte,
          timestamp = 0,
          ref,
          baseTarget = 2L,
          randomOfLength(Block.GenerationSignatureLength),
          PublicKey(defaultSigner.publicKey),
          featureVotes = Seq.empty,
          rewardVote = -1L,
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
        version = 3.toByte,
        timestamp = 0,
        ref,
        baseTarget = 2L,
        randomOfLength(Block.GenerationSignatureLength),
        PublicKey(defaultSigner.publicKey),
        features,
        rewardVote = -1L,
        transactionData = Seq.empty,
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = None
      )
    )
}
