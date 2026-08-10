package tech.hearth.api.grpc

import tech.hearth.account.Address
import tech.hearth.api.common.{CommonAccountsApi, LeaseInfo}
import tech.hearth.api.http.ApiError.CustomValidationError
import tech.hearth.protobuf.*
import tech.hearth.protobuf.transaction.PBRecipients
import tech.hearth.protobuf.utils.PBImplicitConversions.fromAssetIdAndAmount
import tech.hearth.transaction.Asset
import io.grpc.stub.StreamObserver
import monix.execution.Scheduler
import monix.reactive.Observable

class AccountsApiGrpcImpl(commonApi: CommonAccountsApi)(implicit sc: Scheduler) extends AccountsApiGrpc.AccountsApi {

  private def loadHearthBalance(address: Address): BalanceResponse = {
    commonApi
      .balanceDetails(address)
      .fold(
        e => throw GRPCErrors.toStatusException(CustomValidationError(e)),
        details =>
          BalanceResponse().withWaves(
            BalanceResponse.WavesBalances(
              details.regular,
              details.generating,
              details.available,
              details.effective,
              details.leaseIn,
              details.leaseOut
            )
          )
      )
  }

  private def assetBalanceResponse(v: (Asset.IssuedAsset, Long)): BalanceResponse =
    BalanceResponse().withAsset(fromAssetIdAndAmount(v))

  override def getBalances(request: BalancesRequest, responseObserver: StreamObserver[BalanceResponse]): Unit = responseObserver.interceptErrors {
    val addressOption: Option[Address] = if (request.address.isEmpty) None else Some(request.address.toAddress)
    val assetIds: Seq[Asset]           = request.assets.map(id => if (id.isEmpty) Asset.Hearth else Asset.IssuedAsset(id.toByteStr))

    val responseStream = (addressOption, assetIds) match {
      case (Some(address), Seq()) =>
        Observable(loadHearthBalance(address)) ++ commonApi.portfolio(address).concatMapIterable(identity).map(assetBalanceResponse)
      case (Some(address), nonEmptyList) =>
        Observable
          .fromIterable(nonEmptyList)
          .map {
            case Asset.Hearth          => loadHearthBalance(address)
            case ia: Asset.IssuedAsset => assetBalanceResponse(ia -> commonApi.assetBalance(address, ia))
          }
      case (None, Seq(_)) => // todo: asset distribution
        Observable.empty
      case (None, _) => // multiple distributions are not supported
        Observable.empty
    }

    responseObserver.completeWith(responseStream)
  }

  // TODO: Lease info route?
  override def getActiveLeases(request: AccountRequest, responseObserver: StreamObserver[LeaseResponse]): Unit =
    responseObserver.interceptErrors {
      val result =
        commonApi
          .activeLeases(request.address.toAddress)
          .map { case LeaseInfo(leaseId, originTransactionId, sender, recipient, amount, height, status, _, _) =>
            assert(status == LeaseInfo.Status.Active)
            LeaseResponse(
              leaseId.toByteString,
              originTransactionId.toByteString,
              sender.toByteString,
              Some(PBRecipients.create(recipient)),
              amount,
              height.toInt
            )
          }
      responseObserver.completeWith(result)
    }
}
