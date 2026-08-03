package tech.hearth.protobuf

/** The generated protobuf DTOs live in tech.hearth.protobuf; this package is the node's own encoding/decoding layer on
  * top of them, and re-exports the message types it converts to and from.
  */
package object block {
  type PBBlock = tech.hearth.protobuf.block.Block
  val PBBlock = tech.hearth.protobuf.block.Block

  type VanillaBlock = tech.hearth.block.Block
  val VanillaBlock = tech.hearth.block.Block

  type PBBlockHeader = tech.hearth.protobuf.block.Block.Header
  val PBBlockHeader = tech.hearth.protobuf.block.Block.Header

  type VanillaBlockHeader = tech.hearth.block.BlockHeader
  val VanillaBlockHeader = tech.hearth.block.BlockHeader

  type PBSignedMicroBlock = tech.hearth.protobuf.block.SignedMicroBlock
  val PBSignedMicroBlock = tech.hearth.protobuf.block.SignedMicroBlock

  type PBMicroBlock = tech.hearth.protobuf.block.MicroBlock
  val PBMicroBlock = tech.hearth.protobuf.block.MicroBlock

  type VanillaMicroBlock = tech.hearth.block.MicroBlock
  val VanillaMicroBlock = tech.hearth.block.MicroBlock

  type PBEndorseBlock = tech.hearth.protobuf.block.EndorseBlock
  val PBEndorseBlock = tech.hearth.protobuf.block.EndorseBlock

  type VanillaFinalizationVoting = tech.hearth.block.FinalizationVoting
  val VanillaFinalizationVoting = tech.hearth.block.FinalizationVoting

  type PBFinalizationVoting = tech.hearth.protobuf.block.FinalizationVoting
  val PBFinalizationVoting = tech.hearth.protobuf.block.FinalizationVoting
}
