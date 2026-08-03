package tech.hearth.block

import tech.hearth.account.PublicKey
import tech.hearth.block.serialization.BlockSerializer
import tech.hearth.common.merkle.Merkle.{hash, mkProofs, verify}
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.crypto.*
import tech.hearth.lang.ValidationError
import tech.hearth.protobuf.block.PBBlocks
import tech.hearth.protobuf.transaction.PBTransactions
import tech.hearth.settings.BlockchainSettings
import tech.hearth.state.*
import tech.hearth.transaction.*
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.utils.EmptyBlockchain
import monix.eval.Coeval
import play.api.libs.json.*

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

  /** The genesis block has no transactions: its effect on the state is the height-1 predefined snapshot, built against an
    * always-empty view since nothing precedes genesis - unlike [[tech.hearth.state.diffs.BlockDiffer]]'s own genesis handling,
    * this runs unconditionally on every startup (see [[tech.hearth.checkGenesis]]), including against an already-populated
    * chain, to verify the persisted genesis block still matches.
    */
  def genesis(
      blockchainSettings: BlockchainSettings
  ): Either[ValidationError, Block] =
    for {
      // A real network's config must declare predefined-snapshots (parsing fails otherwise, see BlockchainSettings'
      // ConfigReader), but nothing checks a height-1 entry is actually among them, nor do settings built directly in
      // code (tests, tools) necessarily have one; either way this defaults to an empty snapshot rather than failing
      // here, same as an empty genesis balances list did before predefined snapshots existed.
      snapshot <- PredefinedSnapshot.build(blockchainSettings.genesisSnapshot, EmptyBlockchain)
      genesisSettings = blockchainSettings.genesisSettings
      baseTarget      = genesisSettings.initialBaseTarget
      timestamp       = genesisSettings.blockTimestamp
      stateHash       = TxStateSnapshotHashBuilder.createGenesisStateHash(snapshot)
      // The configured snapshot is what a misconfiguration silently changes, so check it before anything derived from it
      _ <- checkPredefined("state hash", genesisSettings.stateHash, stateHash)
      // The state hash goes into the header before signing: the block is protobuf-serialized, so its body bytes cover it
      block = create(
        timestamp,
        GenesisReference,
        baseTarget,
        GenesisGenerationSignature,
        PublicKey(GenesisGenerator.publicKey),
        featureVotes = Seq(),
        transactionData = Seq.empty,
        stateHash = Some(stateHash),
        challengedHeader = None,
        finalizationVoting = None
      )
      signedBlock = genesisSettings.signature match {
        case None             => block.sign(GenesisGenerator)
        case Some(predefined) => block.copy(signature = predefined)
      }
      validBlock <- signedBlock.validateGenesis
      _          <- checkPredefined("block id", genesisSettings.blockId, validBlock.id())
    } yield validBlock

  /** A predefined value in the genesis settings is a commitment: if it disagrees with what the rest of the settings
    * produce, the settings are wrong and the node must not run on them.
    */
  private def checkPredefined(what: String, predefined: Option[ByteStr], computed: ByteStr): Either[ValidationError, Unit] =
    predefined match {
      case Some(expected) if expected != computed =>
        Left(GenericError(s"Genesis $what mismatch: settings declare $expected, but the configured genesis block has $computed"))
      case _ => Right(())
    }

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
  val BlockIdLength: Int                = DigestLength
  val TransactionSizeLength             = 4
  val HitSourceLength                   = 64

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
