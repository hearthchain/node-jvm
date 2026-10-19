package tech.hearth.state

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.{Address, VrfKey}

/** How a generator may generate a block: see [[tech.hearth.state.appender.generatorEligibility]]. */
enum GeneratorEligibility {

  /** A committed generator of the period that can generate: neither poor nor in conflict. */
  case Committed

  /** Mined in the period without a commitment already, and is held to the VRF key its first block there carried. */
  case Implicit(vrfPublicKey: ByteStr)

  /** Not committed, while no committed generator of the period can generate: its block commits it implicitly, so it
    * has to carry the VRF key it commits with.
    */
  case Uncommitted

  case Denied(reason: String)

  /** The VRF key a block by a generator proving with `vrfKey` has to declare in its header, if any. */
  def declaredVrfPublicKey(vrfKey: VrfKey): Either[String, Option[ByteStr]] = this match {
    case Committed      => Right(None)
    case Uncommitted    => Right(Some(ByteStr(vrfKey.publicKey())))
    case Denied(reason) => Left(reason)
    case Implicit(registered) =>
      Either.cond(registered == ByteStr(vrfKey.publicKey()), None, s"The VRF key it proves with is not the one $registered it committed with")
  }

  /** Whether a block by `generator` that declares `vrfPublicKey` in its header is allowed. */
  def check(generator: Address, vrfPublicKey: Option[ByteStr]): Either[String, Unit] = (this, vrfPublicKey) match {
    case (Denied(reason), _) => Left(reason)
    case (Uncommitted, None) => Left(s"$generator is not committed, so its block has to carry the VRF public key it commits with")
    case (Committed | Implicit(_), Some(_)) =>
      Left(s"$generator already has a VRF public key registered for this period, so its block can't carry one")
    case _ => Right(())
  }
}

object GeneratorEligibility {
  def apply(
      generator: Address,
      committed: Seq[CommittedGenerator],
      generatorSet: GeneratorSet,
      implicitGenerators: Seq[ImplicitGenerator]
  ): GeneratorEligibility = {
    def notAllowed = Denied(
      s"$generator is not allowed to generate a block, allowed: ${generatorSet.map(_.address).mkString(", ")}. " +
        s"If this is your node: commit to generation for the next period"
    )

    if (generatorSet.exists(_.address == generator)) Committed
    // A committed generator that can't generate must not fall back to generating without a commitment
    else if (committed.exists(_.address == generator)) notAllowed
    else
      implicitGenerators.find(_.address == generator) match {
        case Some(ig)                     => Implicit(ig.vrfPublicKey)
        case None if generatorSet.isEmpty => Uncommitted
        case None                         => notAllowed
      }
  }
}
