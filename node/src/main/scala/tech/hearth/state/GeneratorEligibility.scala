package tech.hearth.state

import tech.hearth.common.state.ByteStr
import tech.hearth.consensus.GeneratingBalanceProvider
import tech.hearth.crypto.{Address, VrfKey, VrfPublicKeyLength}

/** How a generator may generate a block, see [[GeneratorEligibility.at]]. */
enum GeneratorEligibility {

  /** A committed generator of the period that can generate: neither poor nor in conflict. */
  case Committed

  /** Mined in the period without a commitment already, and is held to the VRF key its first block there carried. */
  case Implicit(vrfPublicKey: ByteStr)

  /** Not committed, while no committed generator of the period can: its block commits the VRF key it carries. */
  case Uncommitted(registeredVrfKeys: Set[ByteStr])

  case Denied(reason: String)

  /** The VRF key a block by a generator proving with `vrfKey` has to declare in its header, if any. */
  def declaredVrfPublicKey(vrfKey: VrfKey): Either[String, Option[ByteStr]] = {
    val own = ByteStr(vrfKey.publicKey())
    this match {
      case Committed            => Right(None)
      case Uncommitted(taken)   => GeneratorEligibility.checkNewVrfKey(taken, own).map(_ => Some(own))
      case Denied(reason)       => Left(reason)
      case Implicit(registered) => Either.cond(registered == own, None, s"The VRF key it proves with is not the one $registered it committed with")
    }
  }

  /** Whether a block by `generator` that declares `vrfPublicKey` in its header is allowed. */
  def check(generator: Address, vrfPublicKey: Option[ByteStr]): Either[String, Unit] = (this, vrfPublicKey) match {
    case (Denied(reason), _)             => Left(reason)
    case (Uncommitted(_), None)          => Left(s"$generator is not committed, so its block has to carry the VRF public key it commits with")
    case (Uncommitted(taken), Some(key)) => GeneratorEligibility.checkNewVrfKey(taken, key)
    case (Committed | Implicit(_), Some(_)) =>
      Left(s"$generator already has a VRF public key registered for this period, so its block can't carry one")
    case _ => Right(())
  }
}

object GeneratorEligibility {

  /** The committed generators of the block after `parentBlockId` that can generate it: neither poor nor in conflict. */
  def eligibleGenerators(blockchain: Blockchain, parentBlockId: ByteStr, parentHeight: Height): GeneratorSet = {
    val blockHeight         = parentHeight.next
    val period              = blockchain.generationPeriodOf(blockHeight)
    val conflictGenerators  = period.fold(ConflictGenerators.empty)(blockchain.conflictGenerators).upTo(blockHeight)
    val committedGenerators = period.fold(Nil)(blockchain.committedGenerators)
    // Once for the whole committee, see GeneratingBalanceProvider.workContext
    val workContext = GeneratingBalanceProvider.workContext(blockchain, parentHeight.toInt)
    committedGenerators.view
      .map(cg => cg -> GeneratingBalanceProvider.balanceWithContext(blockchain, cg.address, Some(parentBlockId), workContext))
      .zipWithIndex
      .collect {
        case ((cg, balance), idx)
            if !conflictGenerators.contains(GeneratorIndex(idx)) && GeneratingBalanceProvider.isGeneratingBalanceValid(balance) =>
          GeneratorInfo(GeneratorIndex(idx), cg.address, cg.endorserPublicKey, balance)
      }
      .toSeq
  }

  /** Whether `generator` may generate the block after `parentBlockId`, and how. The miner asks this before it forges a
    * block, and the appender checks a block against it, so the two can't disagree.
    */
  def at(
      blockchain: Blockchain,
      parentBlockId: ByteStr,
      parentHeight: Height,
      generator: Address
  ): (generatorSet: GeneratorSet, eligibility: GeneratorEligibility) = {
    val generatorSet = eligibleGenerators(blockchain, parentBlockId, parentHeight)
    (generatorSet, of(blockchain, parentHeight.next, generatorSet, generator))
  }

  /** Same as [[at]], for a caller that already has the generator set of the block at `blockHeight`. */
  def of(blockchain: Blockchain, blockHeight: Height, generatorSet: GeneratorSet, generator: Address): GeneratorEligibility = {
    val period = blockchain.generationPeriodOf(blockHeight)
    apply(generator, period.fold(Nil)(blockchain.committedGenerators), generatorSet, period.fold(Nil)(blockchain.implicitGenerators))
  }

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
        case None if generatorSet.isEmpty => Uncommitted((committed.map(_.vrfPublicKey) ++ implicitGenerators.map(_.vrfPublicKey)).toSet)
        case None                         => notAllowed
      }
  }

  /** Checked before the key reaches any crypto: the crypto backends treat a key of another length differently. */
  def checkVrfPublicKeyLength(vrfPublicKey: ByteStr): Either[String, Unit] =
    Either.cond(
      vrfPublicKey.arr.length == VrfPublicKeyLength,
      (),
      s"VRF public key of ${vrfPublicKey.arr.length} bytes, expected $VrfPublicKeyLength"
    )

  // Two generators of one period with one key would share every hit, which CommitToGeneration rules out as well
  private def checkNewVrfKey(registered: Set[ByteStr], key: ByteStr): Either[String, Unit] =
    checkVrfPublicKeyLength(key).flatMap(_ =>
      Either.cond(!registered.contains(key), (), s"VRF public key $key is already registered for this period, try another key")
    )
}
