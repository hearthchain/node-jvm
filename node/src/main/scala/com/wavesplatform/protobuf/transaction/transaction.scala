package com.wavesplatform.protobuf

/** The generated protobuf DTOs live in tech.hearth.protobuf; this package is the node's own encoding/decoding layer on
  * top of them, and re-exports the message types it converts to and from.
  */
//noinspection TypeAnnotation
package object transaction {
  type PBOrder = tech.hearth.protobuf.order.Order
  val PBOrder = tech.hearth.protobuf.order.Order

  type VanillaOrder = com.wavesplatform.transaction.assets.exchange.Order
  val VanillaOrder = com.wavesplatform.transaction.assets.exchange.Order

  type PBTransaction = tech.hearth.protobuf.transaction.Transaction
  val PBTransaction = tech.hearth.protobuf.transaction.Transaction

  type PBSignedTransaction = tech.hearth.protobuf.transaction.SignedTransaction
  val PBSignedTransaction = tech.hearth.protobuf.transaction.SignedTransaction

  type VanillaTransaction = com.wavesplatform.transaction.Transaction

  type VanillaAssetId = com.wavesplatform.transaction.Asset

  // Re-exported so that this package's converters can keep naming the DTOs directly, as they did when the generated
  // code shared this package
  type SignedTransaction = tech.hearth.protobuf.transaction.SignedTransaction
  val SignedTransaction = tech.hearth.protobuf.transaction.SignedTransaction

  type Transaction = tech.hearth.protobuf.transaction.Transaction
  val Transaction = tech.hearth.protobuf.transaction.Transaction

  type Recipient = tech.hearth.protobuf.transaction.Recipient
  val Recipient = tech.hearth.protobuf.transaction.Recipient

  type TransferTransactionData = tech.hearth.protobuf.transaction.TransferTransactionData
  val TransferTransactionData = tech.hearth.protobuf.transaction.TransferTransactionData

  type MassTransferTransactionData = tech.hearth.protobuf.transaction.MassTransferTransactionData
  val MassTransferTransactionData = tech.hearth.protobuf.transaction.MassTransferTransactionData

  type LeaseTransactionData = tech.hearth.protobuf.transaction.LeaseTransactionData
  val LeaseTransactionData = tech.hearth.protobuf.transaction.LeaseTransactionData

  type LeaseCancelTransactionData = tech.hearth.protobuf.transaction.LeaseCancelTransactionData
  val LeaseCancelTransactionData = tech.hearth.protobuf.transaction.LeaseCancelTransactionData

  type ExchangeTransactionData = tech.hearth.protobuf.transaction.ExchangeTransactionData
  val ExchangeTransactionData = tech.hearth.protobuf.transaction.ExchangeTransactionData

  type CommitToGenerationTransactionData = tech.hearth.protobuf.transaction.CommitToGenerationTransactionData
  val CommitToGenerationTransactionData = tech.hearth.protobuf.transaction.CommitToGenerationTransactionData
}
