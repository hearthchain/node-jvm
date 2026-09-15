package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.state.{Blockchain, LeaseBalance, StateSnapshot}
import tech.hearth.transaction.Asset
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.CommitToGenerationTransaction.DepositInEmbers
import tech.hearth.transaction.TxValidationError.AccountBalanceError

import scala.util.{Left, Right}

object BalanceDiffValidation {
  trait BalanceProvider {
    def balance(address: Address, mayBeAssetId: Asset = Hearth): Long
    def leaseBalance(address: Address): LeaseBalance
    def generationDeposit(address: Address): Long
    def lockedStake(address: Address): Long
  }

  object BalanceProvider {
    def apply(blockchain: Blockchain): BalanceProvider = new BalanceProvider {
      override def balance(address: Address, mayBeAssetId: Asset): Long = blockchain.balance(address, mayBeAssetId)
      override def leaseBalance(address: Address): LeaseBalance         = blockchain.leaseBalance(address)
      override def generationDeposit(address: Address): Long            = blockchain.generationDeposit(address)
      override def lockedStake(address: Address): Long                  = blockchain.lockedStake(address)
    }

    val Empty: BalanceProvider = new BalanceProvider {
      override def balance(address: Address, mayBeAssetId: Asset): Long = 0
      override def leaseBalance(address: Address): LeaseBalance         = LeaseBalance.empty
      override def generationDeposit(address: Address): Long            = 0
      override def lockedStake(address: Address): Long                  = 0
    }
  }

  def cond(b: Blockchain, cond: Blockchain => Boolean)(s: StateSnapshot): Either[AccountBalanceError, StateSnapshot] = {
    if (cond(b)) apply(b)(s)
    else Right(s)
  }

  def apply(snapshot: StateSnapshot): Either[AccountBalanceError, StateSnapshot] =
    apply(BalanceProvider.Empty, snapshot)

  def apply(b: Blockchain)(snapshot: StateSnapshot): Either[AccountBalanceError, StateSnapshot] =
    apply(BalanceProvider(b), snapshot)

  def apply(b: BalanceProvider, snapshot: StateSnapshot): Either[AccountBalanceError, StateSnapshot] = {
    def checkHearth(
        acc: Address,
        hearthAfter: Long,
        leaseAfter: LeaseBalance,
        additionalDeposit: Long,
        stakedAfter: Long
    ): Either[(Address, String), Unit] = {
      val hearthBefore  = b.balance(acc)
      val depositBefore = b.generationDeposit(acc)
      val leaseBefore   = b.leaseBalance(acc)

      // Staked HRTH is locked the same way a generation deposit is, so both are folded into one "locked" term the
      // checks below are written against. They are still reported and attributed separately, so that an error
      // message names whichever of the two the sender actually ran into.
      val stakedBefore = b.lockedStake(acc)
      val lockedBefore = depositBefore + stakedBefore
      val depositAfter = depositBefore + additionalDeposit
      val lockedAfter  = depositAfter + stakedAfter

      val hearthWithoutLockedAfter = hearthAfter - lockedAfter

      val leaseOutDiff = leaseAfter.out - leaseBefore.out

      @inline def ifNotZero(label: String, value: Long): String = if (value == 0) "" else s", $label=$value"
      @inline def balancesStr(hearth: Long, lease: LeaseBalance, deposit: Long, staked: Long): String =
        s"spendable=${hearth - lease.out - deposit - staked}" + ifNotZero("hearth", hearth) + ifNotZero("lease", lease.out) +
          ifNotZero("deposit", deposit) + ifNotZero("staked", staked)

      lazy val stateChanges =
        s"before: ${balancesStr(hearthBefore, leaseBefore, depositBefore, stakedBefore)}, " +
          s"after: ${balancesStr(hearthAfter, leaseAfter, depositAfter, stakedAfter)}"

      val errorMessage =
        if (hearthAfter < 0) s"negative hearth balance: before=$hearthBefore, after=$hearthAfter".asLeft
        else if (hearthWithoutLockedAfter < 0) {
          // Which of the two locks the sender fell short of: the one this transaction raised, if either did.
          if (stakedAfter > stakedBefore) s"not enough funds to stake, $stateChanges".asLeft
          else if (depositAfter > depositBefore) s"not enough funds for deposit, $stateChanges".asLeft
          else if (depositBefore > 0) s"trying to spend a deposit, $stateChanges".asLeft
          else s"trying to spend a stake, $stateChanges".asLeft
        } else if (hearthWithoutLockedAfter < leaseAfter.out) {
          if (hearthWithoutLockedAfter + leaseAfter.in - leaseAfter.out < 0) s"negative effective balance, $stateChanges".asLeft
          else if (leaseOutDiff == 0) s"trying to spend leased money, $stateChanges".asLeft
          else s"leased being more than own, $stateChanges".asLeft
        } else if (hearthWithoutLockedAfter - leaseAfter.out < 0 && lockedBefore > 0)
          s"trying to spend either a deposit or leased money, $stateChanges".asLeft
        else Either.unit

      errorMessage.leftMap(err => acc -> s"$err")
    }

    val hearthCheck =
      snapshot.balances
        .flatMap {
          case ((address, Hearth), balance) =>
            val currentLeaseBalance = snapshot.leaseBalances.getOrElse(address, b.leaseBalance(address))
            val depositedOnNext = DepositInEmbers *
              snapshot.nextCommittedGenerators.find(_.sender.toAddress == address).size
            val stakedAfter = snapshot.stakes.get(address).fold(b.lockedStake(address))(_.locked)
            checkHearth(address, balance, currentLeaseBalance, depositedOnNext, stakedAfter).fold(error => List(error), _ => Nil)
          case _ =>
            Nil
        }

    val assetsCheck =
      snapshot.balances
        .collectFirst {
          case ((address, asset), balance) if asset != Hearth && balance < 0 =>
            Map(address -> s"negative asset balance: $address, new portfolio: ${negativeAssetsInfo(address, snapshot)}")
        }
        .getOrElse(Map())

    val positiveBalanceErrors = hearthCheck ++ assetsCheck
    if (positiveBalanceErrors.isEmpty) Right(snapshot)
    else Left(AccountBalanceError(positiveBalanceErrors))
  }

  private def negativeAssetsInfo(
      address: Address,
      snapshot: StateSnapshot
  ): Map[ByteStr, Long] =
    snapshot.balances
      .collect {
        case ((`address`, assetId: IssuedAsset), balance) if balance < 0 => (assetId.id, balance)
      }
}
