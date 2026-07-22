package com.wavesplatform.features

import com.wavesplatform.state.Blockchain

object FunctionCallPolicyProvider {
  implicit class MultiPaymentAllowedExt(b: Blockchain) {
    def callableListArgumentsAllowed: Boolean = true

    def callableListArgumentsCorrected: Boolean = true

    def checkSyncCallArgumentsTypes: Boolean = true
  }
}
