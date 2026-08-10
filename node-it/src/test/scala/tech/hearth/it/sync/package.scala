package tech.hearth.it

import tech.hearth.test.*

package object sync {
  val smartFee: Long                   = 0.004.hearth
  val minFee: Long                     = 0.001.hearth
  val leasingFee: Long                 = 0.001.hearth
  val issueFee: Long                   = 1.hearth
  val reissueFee: Long                 = 1.hearth
  val reissueReducedFee: Long          = 0.001.hearth
  val burnFee: Long                    = 0.001.hearth
  val invokeFee: Long                  = 0.009.hearth
  val invokeExpressionFee: Long        = 0.01.hearth
  val sponsorFee: Long                 = 1.hearth
  val sponsorReducedFee: Long          = 0.001.hearth
  val setAssetScriptFee: Long          = 1.hearth
  val setScriptFee: Long               = 0.01.hearth
  val transferAmount: Long             = 10.hearth
  val leasingAmount: Long              = transferAmount
  val issueAmount: Long                = transferAmount
  val massTransferFeePerTransfer: Long = 0.0005.hearth
  val someAssetAmount: Long            = 9999999999999L
  val matcherFee: Long                 = 0.003.hearth
  val orderFee: Long                   = matcherFee
  val smartMatcherFee: Long            = 0.007.hearth
  val smartMinFee: Long                = minFee + smartFee

  def calcMassTransferFee(numberOfRecipients: Int): Long = {
    minFee + massTransferFeePerTransfer * (numberOfRecipients + 1)
  }

  val supportedVersions: List[Byte]               = List(1, 2, 3)
  val burnTxSupportedVersions: List[Byte]         = List(1, 2, 3)
  val dataTxSupportedVersions: List[Byte]         = List(1, 2)
  val massTransferTxSupportedVersions: List[Byte] = List(1, 2)
  val sponsorshipTxSupportedVersions: List[Byte]  = List(1, 2)
  val setAssetScrTxSupportedVersions: List[Byte]  = List(1, 2)
  val issueTxSupportedVersions: List[Byte]        = List(1, 2, 3)
  val aliasTxSupportedVersions: List[Byte]        = List(1, 2, 3)
  val reissueTxSupportedVersions: List[Byte]      = List(1, 2, 3)

}
