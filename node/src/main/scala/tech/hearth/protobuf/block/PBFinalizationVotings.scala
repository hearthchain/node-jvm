package tech.hearth.protobuf.block

import com.google.protobuf.ByteString
import tech.hearth.common.utils.EitherExt2.explicitGet
import tech.hearth.crypto.bls.BlsSignature
import tech.hearth.protobuf.*
import tech.hearth.state.{GeneratorIndex, Height}

import scala.util.Try

object PBFinalizationVotings {
  def vanilla(pb: PBFinalizationVoting): Try[VanillaFinalizationVoting] = Try {
    val aggSig =
      if (pb.aggregatedEndorsementSignature.isEmpty) None
      else Option(BlsSignature(pb.aggregatedEndorsementSignature.toByteArray).explicitGet())

    VanillaFinalizationVoting(
      GeneratorIndex.seq(pb.endorserIndexes),
      Height(pb.finalizedBlockHeight),
      aggSig,
      pb.conflictEndorsements.zipWithIndex.map { case (x, i) =>
        BlsSignature(x.signature.toByteArray).map(PBEndorseBlocks.vanilla(x, _)) match {
          case Left(e)  => throw new IllegalArgumentException(s"Error during parsing conflict endorsement #$i: $e")
          case Right(r) => r
        }
      }.toVector
    )
  }

  def protobuf(v: VanillaFinalizationVoting): PBFinalizationVoting = PBFinalizationVoting.of(
    GeneratorIndex.toInts(v.valid),
    v.finalizedHeight.toInt,
    v.aggregatedEndorsement.fold(ByteString.EMPTY)(_.byteStr.toByteString),
    v.conflict.map(PBEndorseBlocks.protobuf)
  )
}
