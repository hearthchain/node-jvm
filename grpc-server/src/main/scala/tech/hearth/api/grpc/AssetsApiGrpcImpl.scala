package tech.hearth.api.grpc

import com.google.protobuf.ByteString
import tech.hearth.api.common.CommonAssetsApi
import tech.hearth.api.http.ApiError.TransactionDoesNotExist
import tech.hearth.protobuf.*
import tech.hearth.state.AssetDescription
import tech.hearth.transaction.Asset.IssuedAsset
import io.grpc.stub.StreamObserver
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.Future

class AssetsApiGrpcImpl(assetsApi: CommonAssetsApi)(implicit sc: Scheduler) extends AssetsApiGrpc.AssetsApi {
  override def getInfo(request: AssetRequest): Future[AssetInfoResponse] = Future {
    assetsApi
      .fullInfo(IssuedAsset(request.assetId.toByteStr))
      .map(info => assetInfoResponse(info.description))
      .explicitGetErr(TransactionDoesNotExist)
  }

  // The NFT-portfolio-listing feature was dropped along with AssetDescription.nft (no consensus purpose left
  // once Reissue/Burn/Issue don't exist); this RPC still has to be implemented, but has nothing to serve.
  override def getNFTList(request: NFTRequest, responseObserver: StreamObserver[NFTResponse]): Unit = responseObserver.interceptErrors {
    responseObserver.completeWith(Observable.empty)
  }

  private def assetInfoResponse(d: AssetDescription): AssetInfoResponse =
    AssetInfoResponse(
      ByteString.EMPTY, // wire-compat only: AssetDescription no longer carries an issuer
      d.name,
      d.description,
      d.decimals,
      reissuable = false, // wire-compat only: no Reissue transaction exists any more
      d.totalVolume.longValue,
      sequenceInBlock = d.sequenceInBlock,
      issueHeight = d.issueHeight.toInt
    )
}
