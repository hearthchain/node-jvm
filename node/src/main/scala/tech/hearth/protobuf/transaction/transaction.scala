package tech.hearth.protobuf

/** The generated protobuf DTOs live in this same package; this package object only re-exports the node's own vanilla
  * domain types under the aliases this package's converters use.
  */
//noinspection TypeAnnotation
package object transaction {
  type PBOrder = tech.hearth.protobuf.order.Order
  val PBOrder = tech.hearth.protobuf.order.Order

  type VanillaOrder = tech.hearth.transaction.assets.exchange.Order
  val VanillaOrder = tech.hearth.transaction.assets.exchange.Order

  type PBTransaction = tech.hearth.protobuf.transaction.Transaction
  val PBTransaction = tech.hearth.protobuf.transaction.Transaction

  type PBSignedTransaction = tech.hearth.protobuf.transaction.SignedTransaction
  val PBSignedTransaction = tech.hearth.protobuf.transaction.SignedTransaction

  type VanillaTransaction = tech.hearth.transaction.Transaction

  type VanillaAssetId = tech.hearth.transaction.Asset
}
