package com.wavesplatform.it

import com.wavesplatform.test.*

package object sync {
  val smartFee: Long                   = 0.004.waves
  val minFee: Long                     = 0.001.waves
  val leasingFee: Long                 = 0.001.waves
  val issueFee: Long                   = 1.waves
  val reissueFee: Long                 = 1.waves
  val reissueReducedFee: Long          = 0.001.waves
  val burnFee: Long                    = 0.001.waves
  val invokeFee: Long                  = 0.009.waves
  val invokeExpressionFee: Long        = 0.01.waves
  val sponsorFee: Long                 = 1.waves
  val sponsorReducedFee: Long          = 0.001.waves
  val setAssetScriptFee: Long          = 1.waves
  val setScriptFee: Long               = 0.01.waves
  val transferAmount: Long             = 10.waves
  val leasingAmount: Long              = transferAmount
  val issueAmount: Long                = transferAmount
  val massTransferFeePerTransfer: Long = 0.0005.waves
  val someAssetAmount: Long            = 9999999999999L
  val matcherFee: Long                 = 0.003.waves
  val orderFee: Long                   = matcherFee
  val smartMatcherFee: Long            = 0.007.waves
  val smartMinFee: Long                = minFee + smartFee

  def calcMassTransferFee(numberOfRecipients: Int): Long = {
    minFee + massTransferFeePerTransfer * (numberOfRecipients + 1)
  }

  val supportedVersions: List[Byte]               = List(1, 2, 3)
  val burnTxSupportedVersions: List[Byte]         = List(1, 2, 3)
  val leaseTxSupportedVersions: List[Byte]        = List(1, 2, 3)
  val dataTxSupportedVersions: List[Byte]         = List(1, 2)
  val massTransferTxSupportedVersions: List[Byte] = List(1, 2)
  val sponsorshipTxSupportedVersions: List[Byte]  = List(1, 2)
  val setAssetScrTxSupportedVersions: List[Byte]  = List(1, 2)
  val issueTxSupportedVersions: List[Byte]        = List(1, 2, 3)
  val transferTxSupportedVersions: List[Byte]     = List(1, 2, 3)
  val aliasTxSupportedVersions: List[Byte]        = List(1, 2, 3)
  val reissueTxSupportedVersions: List[Byte]      = List(1, 2, 3)

}
