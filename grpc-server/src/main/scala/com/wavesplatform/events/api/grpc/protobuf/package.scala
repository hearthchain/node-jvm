package com.wavesplatform.events.api.grpc

/** The generated gRPC service stub and message DTOs live in tech.hearth.events.api.grpc.protobuf; re-exported so
  * this package's impl classes can keep naming them directly, as they did when the generated code shared this
  * package.
  */
package object protobuf {
  type BlockchainUpdatesApiGrpc = tech.hearth.events.api.grpc.protobuf.BlockchainUpdatesApiGrpc.type
  val BlockchainUpdatesApiGrpc = tech.hearth.events.api.grpc.protobuf.BlockchainUpdatesApiGrpc

  type GetBlockUpdateRequest = tech.hearth.events.api.grpc.protobuf.GetBlockUpdateRequest
  val GetBlockUpdateRequest = tech.hearth.events.api.grpc.protobuf.GetBlockUpdateRequest

  type GetBlockUpdateResponse = tech.hearth.events.api.grpc.protobuf.GetBlockUpdateResponse
  val GetBlockUpdateResponse = tech.hearth.events.api.grpc.protobuf.GetBlockUpdateResponse

  type GetBlockUpdatesRangeRequest = tech.hearth.events.api.grpc.protobuf.GetBlockUpdatesRangeRequest
  val GetBlockUpdatesRangeRequest = tech.hearth.events.api.grpc.protobuf.GetBlockUpdatesRangeRequest

  type GetBlockUpdatesRangeResponse = tech.hearth.events.api.grpc.protobuf.GetBlockUpdatesRangeResponse
  val GetBlockUpdatesRangeResponse = tech.hearth.events.api.grpc.protobuf.GetBlockUpdatesRangeResponse

  type SubscribeEvent = tech.hearth.events.api.grpc.protobuf.SubscribeEvent
  val SubscribeEvent = tech.hearth.events.api.grpc.protobuf.SubscribeEvent

  type SubscribeRequest = tech.hearth.events.api.grpc.protobuf.SubscribeRequest
  val SubscribeRequest = tech.hearth.events.api.grpc.protobuf.SubscribeRequest
}
