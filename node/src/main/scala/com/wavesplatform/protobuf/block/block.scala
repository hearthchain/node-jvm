package com.wavesplatform.protobuf

/** The generated protobuf DTOs live in tech.hearth.protobuf; this package is the node's own encoding/decoding layer on
  * top of them, and re-exports the message types it converts to and from.
  */
package object block {
  type PBBlock = tech.hearth.protobuf.block.Block
  val PBBlock = tech.hearth.protobuf.block.Block

  type VanillaBlock = com.wavesplatform.block.Block
  val VanillaBlock = com.wavesplatform.block.Block

  type PBBlockHeader = tech.hearth.protobuf.block.Block.Header
  val PBBlockHeader = tech.hearth.protobuf.block.Block.Header

  type VanillaBlockHeader = com.wavesplatform.block.BlockHeader
  val VanillaBlockHeader = com.wavesplatform.block.BlockHeader

  type PBSignedMicroBlock = tech.hearth.protobuf.block.SignedMicroBlock
  val PBSignedMicroBlock = tech.hearth.protobuf.block.SignedMicroBlock

  type PBMicroBlock = tech.hearth.protobuf.block.MicroBlock
  val PBMicroBlock = tech.hearth.protobuf.block.MicroBlock

  type VanillaMicroBlock = com.wavesplatform.block.MicroBlock
  val VanillaMicroBlock = com.wavesplatform.block.MicroBlock

  type PBEndorseBlock = tech.hearth.protobuf.block.EndorseBlock
  val PBEndorseBlock = tech.hearth.protobuf.block.EndorseBlock

  type VanillaFinalizationVoting = com.wavesplatform.block.FinalizationVoting
  val VanillaFinalizationVoting = com.wavesplatform.block.FinalizationVoting

  type PBFinalizationVoting = tech.hearth.protobuf.block.FinalizationVoting
  val PBFinalizationVoting = tech.hearth.protobuf.block.FinalizationVoting

  // Re-exported so that this package's converters can keep naming the DTOs directly, as they did when the generated
  // code shared this package
  type Block = tech.hearth.protobuf.block.Block
  val Block = tech.hearth.protobuf.block.Block

  type MicroBlock = tech.hearth.protobuf.block.MicroBlock
  val MicroBlock = tech.hearth.protobuf.block.MicroBlock

  type SignedMicroBlock = tech.hearth.protobuf.block.SignedMicroBlock
  val SignedMicroBlock = tech.hearth.protobuf.block.SignedMicroBlock

  type EndorseBlock = tech.hearth.protobuf.block.EndorseBlock
  val EndorseBlock = tech.hearth.protobuf.block.EndorseBlock

  type FinalizationVoting = tech.hearth.protobuf.block.FinalizationVoting
  val FinalizationVoting = tech.hearth.protobuf.block.FinalizationVoting
}
