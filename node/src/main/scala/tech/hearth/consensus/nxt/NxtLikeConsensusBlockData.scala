package tech.hearth.consensus.nxt

import tech.hearth.common.state.ByteStr

case class NxtLikeConsensusBlockData(baseTarget: Long, generationSignature: ByteStr)
