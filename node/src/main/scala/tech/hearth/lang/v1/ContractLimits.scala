package tech.hearth.lang.v1

object ContractLimits {
  val FailFreeInvokeComplexity = 1000
  val FreeVerifierComplexity   = 200

  val MaxExprSizeInBytes: Int     = 8 * 1024
  val MaxContractSizeInBytes: Int = 32 * 1024
  val MaxContractSizeInBytesV6    = 160 * 1024

  val MaxContractMetaSizeInBytes = 1024

  // As in Scala
  val MaxInvokeScriptArgs       = 22
  val MaxDeclarationNameInBytes = 255

  // Data 0.001 per kilobyte, rounded up, fee for CI is 0.005
  val MaxInvokeScriptSizeInBytes: Int = 5 * 1024
  val MaxWriteSetSizeInBytes: Int     = 5 * 1024
  val MaxWriteSetSize: Int            = 100

  val MaxTotalWriteSetSizeInBytes: Int = 15 * 1024

  // should conform DataEntry limits
//  val MaxKeySizeInBytesByVersion: StdLibVersion => Int =
//    v => if (v >= V4) 400 else 100

  val MaxBalanceScriptActionsAmountV6: Int = 100
  val MaxAssetScriptActionsAmountV6: Int   = 30

  val MaxAttachedPaymentAmount    = 2
  val MaxAttachedPaymentAmountV5  = 10
  val MaxTotalPaymentAmountRideV6 = 100

  // Data weight related constants
  val OBJ_WEIGHT          = 40L
  val FIELD_WEIGHT        = 30L
  val EMPTYARR_WEIGHT     = 20L
  val ELEM_WEIGHT         = 20L
  val DataTxMaxProtoBytes = 165947L
  val MaxWeight: Long =
    DataTxMaxProtoBytes * 2L +                                                // bodyBytes and data
      32L + 8L + 8L + 8L +                                                    // header
      OBJ_WEIGHT + FIELD_WEIGHT + 32L +                                       // address object
      EMPTYARR_WEIGHT + (ELEM_WEIGHT + 64L) * 8L +                            // proofs
      EMPTYARR_WEIGHT + (ELEM_WEIGHT + OBJ_WEIGHT + FIELD_WEIGHT * 2L) * 100L // Data entries

  val MaxCmpWeight = 13000

  val MinTupleSize = 2
  val MaxTupleSize = 22
}
