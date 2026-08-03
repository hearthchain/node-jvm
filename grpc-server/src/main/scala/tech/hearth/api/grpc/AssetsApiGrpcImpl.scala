package tech.hearth.api.grpc

import tech.hearth.account.Address
import tech.hearth.api.common.{CommonAccountsApi, CommonAssetsApi}
import tech.hearth.api.http.ApiError.TransactionDoesNotExist
import tech.hearth.protobuf.*
import tech.hearth.state.{AssetDescription, Blockchain}
import tech.hearth.transaction.Asset.IssuedAsset
import io.grpc.stub.StreamObserver
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.Future

class AssetsApiGrpcImpl(assetsApi: CommonAssetsApi, accountsApi: CommonAccountsApi, blockchain: Blockchain)(implicit sc: Scheduler)
    extends AssetsApiGrpc.AssetsApi {
  override def getInfo(request: AssetRequest): Future[AssetInfoResponse] = Future {
    val result =
      for (info <- assetsApi.fullInfo(IssuedAsset(request.assetId.toByteStr)))
        yield {
          val issueTransaction = blockchain.transactionInfo(info.description.originTransactionId.byteStr).map(_._2.toPB)
          issueTransaction.fold(assetInfoResponse(info.description))(assetInfoResponse(info.description).withIssueTransaction)
        }
    result.explicitGetErr(TransactionDoesNotExist)
  }

  override def getNFTList(request: NFTRequest, responseObserver: StreamObserver[NFTResponse]): Unit = responseObserver.interceptErrors {
    val addressOption: Option[Address]    = if (request.address.isEmpty) None else Some(request.address.toAddress)
    val afterAssetId: Option[IssuedAsset] = if (request.afterAssetId.isEmpty) None else Some(IssuedAsset(request.afterAssetId.toByteStr))

    val responseStream = addressOption match {
      case Some(address) =>
        accountsApi
          .nftList(address, afterAssetId)
          .concatMapIterable(_.map { case (a, d) =>
            NFTResponse(a.id.toByteString, Some(assetInfoResponse(d)))
          })
          .take(request.limit)
      case _ => Observable.empty
    }

    responseObserver.completeWith(responseStream)
  }

  private def assetInfoResponse(d: AssetDescription): AssetInfoResponse =
    AssetInfoResponse(
      d.issuer.toByteString,
      d.name.toStringUtf8,
      d.description.toStringUtf8,
      d.decimals,
      d.reissuable,
      d.totalVolume.longValue,
      sequenceInBlock = d.sequenceInBlock,
      issueHeight = d.issueHeight.toInt
    )
}
