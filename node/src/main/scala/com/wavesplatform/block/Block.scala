package com.wavesplatform.block

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.serialization.BlockSerializer
import com.wavesplatform.common.merkle.Merkle.{hash, mkProofs, verify}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.crypto.*
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.protobuf.block.PBBlocks
import com.wavesplatform.protobuf.transaction.PBTransactions
import com.wavesplatform.settings.GenesisSettings
import com.wavesplatform.state.*
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.TxValidationError.GenericError
import monix.eval.Coeval
import play.api.libs.json.*
import tech.hearth.crypto.*

case class BlockHeader(
    timestamp: Long,
    reference: ByteStr,
    baseTarget: Long,
    generationSignature: ByteStr,
    generator: PublicKey,
    featureVotes: Seq[Short],
    transactionsRoot: ByteStr,
    stateHash: Option[ByteStr],
    challengedHeader: Option[ChallengedHeader],
    finalizationVoting: Option[FinalizationVoting]
) {
  val score: Coeval[BigInt] = Coeval.evalOnce((BigInt("18446744073709551616") / baseTarget).ensuring(_ > 0))
}

case class ChallengedHeader(
    timestamp: Long,
    baseTarget: Long,
    generationSignature: ByteStr,
    featureVotes: Seq[Short],
    generator: PublicKey,
    stateHash: Option[ByteStr],
    headerSignature: ByteStr,
    finalizationVoting: Option[FinalizationVoting]
)

case class Block(
    header: BlockHeader,
    signature: ByteStr,
    transactionData: Seq[Transaction]
) {
  import Block.*

  val id: Coeval[ByteStr] = Coeval.evalOnce(Block.idFromHeader(header))

  def signedHeader: SignedBlockHeader = SignedBlockHeader(header, signature)

  val sender: PublicKey = header.generator

  val bytes: Coeval[Array[Byte]] = Coeval.evalOnce(BlockSerializer.toBytes(this))
  val json: Coeval[JsObject]     = Coeval.evalOnce(BlockSerializer.toJson(this))

  val blockScore: Coeval[BigInt] = header.score

  val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce {
    PBBlocks.protobuf(this).header.get.toByteArray
  }

  protected val signedDescendants: Coeval[Seq[Signed]] = Coeval.evalOnce(transactionData.flatMap(_.cast[Signed]))

  private[block] val transactionsMerkleTree: Coeval[TransactionsMerkleTree] = Coeval.evalOnce(mkMerkleTree(transactionData))

  private[block] val originalHeader: Coeval[BlockHeader] =
    Coeval.evalOnce(
      header.challengedHeader
        .map { ch =>
          header.copy(
            baseTarget = ch.baseTarget,
            timestamp = ch.timestamp,
            generationSignature = ch.generationSignature,
            generator = ch.generator,
            featureVotes = ch.featureVotes,
            stateHash = ch.stateHash,
            challengedHeader = None,
            finalizationVoting = ch.finalizationVoting
          )
        }
        .getOrElse(header)
    )

  val signatureValid: Coeval[Boolean] = Coeval.evalOnce {
    crypto.verify(signature, bodyBytes(), header.generator) &&
    (transactionsMerkleTree().transactionsRoot == header.transactionsRoot) &&
    header.challengedHeader.forall { ch =>
      crypto.verify(
        ch.headerSignature,
        PBBlocks.protobuf(originalHeader()).toByteArray,
        ch.generator
      )
    }
  }

  def toOriginal: Block =
    header.challengedHeader match {
      case Some(ch) => copy(header = originalHeader(), signature = ch.headerSignature)
      case _        => this
    }

  override def toString: String =
    s"Block(${id()},${header.reference},${header.generator.toAddress},${header.timestamp}," +
      s"${header.featureVotes.mkString("[", ",", "]")}" +
      s"${header.finalizationVoting.fold("")(v => s",$v")})"
}

object Block {
  def idFromHeader(h: BlockHeader): ByteStr = protoHeaderHash(h)

  def protoHeaderHash(h: BlockHeader): ByteStr = {
    ByteStr(crypto.fastHash(PBBlocks.protobuf(h).toByteArray))
  }

  def validateReferenceLength(length: Int): Boolean =
    length == DigestLength || length == SignatureLength

  def create(
      timestamp: Long,
      reference: ByteStr,
      baseTarget: Long,
      generationSignature: ByteStr,
      generator: PublicKey,
      featureVotes: Seq[Short],
      transactionData: Seq[Transaction],
      stateHash: Option[ByteStr],
      challengedHeader: Option[ChallengedHeader],
      finalizationVoting: Option[FinalizationVoting]
  ): Block = {
    Block(
      BlockHeader(
        timestamp,
        reference,
        baseTarget,
        generationSignature,
        generator,
        featureVotes,
        mkTransactionsRoot(transactionData),
        stateHash,
        challengedHeader,
        finalizationVoting
      ),
      ByteStr.empty,
      transactionData
    )
  }

  def create(
      base: Block,
      transactionData: Seq[Transaction],
      signature: ByteStr,
      stateHash: Option[ByteStr],
      finalizationVoting: Option[FinalizationVoting]
  ): Block = base.copy(
    signature = signature,
    transactionData = transactionData,
    header = base.header.copy(
      transactionsRoot = mkTransactionsRoot(transactionData),
      stateHash = stateHash,
      finalizationVoting = finalizationVoting
    )
  )

  def buildAndSign(
      timestamp: Long,
      reference: ByteStr,
      baseTarget: Long,
      generationSignature: ByteStr,
      txs: Seq[Transaction],
      signer: SigningKey,
      featureVotes: Seq[Short],
      stateHash: Option[ByteStr],
      challengedHeader: Option[ChallengedHeader],
      finalizationVoting: Option[FinalizationVoting]
  ): Either[GenericError, Block] =
    create(
      timestamp,
      reference,
      baseTarget,
      generationSignature,
      PublicKey(signer.publicKey),
      featureVotes,
      txs,
      stateHash,
      challengedHeader,
      finalizationVoting
    ).validate
      .map(_.sign(signer))

  /** The genesis block has no transactions: its effect on the state is the predefined snapshot built from [[GenesisSettings]]. */
  def genesis(
      genesisSettings: GenesisSettings
  ): Either[ValidationError, Block] =
    for {
      snapshot <- GenesisSnapshot.build(genesisSettings)
      baseTarget = genesisSettings.initialBaseTarget
      timestamp  = genesisSettings.blockTimestamp
      // The state hash goes into the header before signing: the block is protobuf-serialized, so its body bytes cover it
      block = create(
        timestamp,
        GenesisReference,
        baseTarget,
        GenesisGenerationSignature,
        PublicKey(GenesisGenerator.publicKey),
        featureVotes = Seq(),
        transactionData = Seq.empty,
        stateHash = Some(TxStateSnapshotHashBuilder.createGenesisStateHash(snapshot)),
        challengedHeader = None,
        finalizationVoting = None
      )
      signedBlock = genesisSettings.signature match {
        case None             => block.sign(GenesisGenerator)
        case Some(predefined) => block.copy(signature = predefined)
      }
      validBlock <- signedBlock.validateGenesis
    } yield validBlock

  type BlockId                = ByteStr
  type TransactionsMerkleTree = Seq[Seq[Array[Byte]]]
  case class TransactionProof(id: ByteStr, transactionIndex: Int, digests: Seq[Array[Byte]])

  val ReferenceLength: Int = DigestLength

  val MaxTransactionsPerBlockVer1Ver2: Int = 100
  val MaxTransactionsPerBlockVer3: Int     = 6000
  val MaxFeaturesInBlock: Int              = 64
  val BaseTargetLength: Int                = 8
  // A hearth Ecvrf proof is Gamma(32) || c(16) || s(32); Waves' was 96
  val GenerationVRFSignatureLength: Int = 80
  val BlockIdLength: Int                = SignatureLength
  val TransactionSizeLength             = 4
  val HitSourceLength                   = 32

  val GenesisReference: BlockId    = ByteStr(Array.fill(DigestLength)(-1: Byte))
  val GenesisGenerator: SigningKey = SigningKey.fromSeed(Crypto.defaultBackend().sha256(new Array[Byte](32)))

  /** The initial random beacon: the next block's VRF proof is verified against it. */
  val GenesisGenerationSignature: BlockId = ByteStr(new Array[Byte](GenerationVRFSignatureLength))

  // Merkle
  implicit class BlockTransactionsRootOps(private val block: Block) extends AnyVal {
    def transactionProof(transaction: Transaction): Option[TransactionProof] =
      block.transactionData.indexWhere(transaction.id() == _.id()) match {
        case -1  => None
        case idx => Some(TransactionProof(transaction.id(), idx, mkProofs(idx, block.transactionsMerkleTree()).reverse))
      }

    def verifyTransactionProof(transactionProof: TransactionProof): Boolean =
      block.transactionData
        .lift(transactionProof.transactionIndex)
        .filter(tx => tx.id() == transactionProof.id)
        .exists(tx =>
          verify(
            hash(PBTransactions.protobuf(tx).toByteArray),
            transactionProof.transactionIndex,
            transactionProof.digests.reverse,
            block.header.transactionsRoot.arr
          )
        )
  }
}

case class SignedBlockHeader(header: BlockHeader, signature: ByteStr) {
  val id: Coeval[ByteStr] = Coeval.evalOnce(Block.idFromHeader(header))
}
