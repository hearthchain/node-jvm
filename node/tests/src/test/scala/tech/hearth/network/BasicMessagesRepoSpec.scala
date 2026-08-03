package tech.hearth.network

import com.google.protobuf.{ByteString, CodedOutputStream, WireFormat}
import tech.hearth.common.state.ByteStr
import tech.hearth.mining.MiningConstraints
import tech.hearth.protobuf.block.*
import tech.hearth.protobuf.transaction.*
import tech.hearth.test.FreeSpec
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.{Proofs, TxHelpers}

import java.io.ByteArrayOutputStream

class BasicMessagesRepoSpec extends FreeSpec {
  "PBBlockSpec max length" in {
    val maxSizedHeader = PBBlock.Header(
      Byte.MaxValue,
      ByteString.copyFrom(bytes64gen.sample.get),
      Long.MaxValue,
      ByteString.copyFrom(byteArrayGen(VanillaBlock.GenerationVRFSignatureLength).sample.get),
      Seq.fill(VanillaBlock.MaxFeaturesInBlock)(Short.MaxValue),
      Long.MaxValue,
      ByteString.copyFrom(bytes32gen.sample.get),
      ByteString.copyFrom(bytes32gen.sample.get)
    )
    val maxSignature = ByteString.copyFrom(bytes64gen.sample.get)

    val headerSize    = maxSizedHeader.serializedSize
    val signatureSize = maxSignature.toByteArray.length

    val headerPBPrefix      = new ByteArrayOutputStream()
    val codedHeaderPBPrefix = CodedOutputStream.newInstance(headerPBPrefix)
    codedHeaderPBPrefix.writeTag(PBBlock.HEADER_FIELD_NUMBER, WireFormat.WIRETYPE_LENGTH_DELIMITED)
    codedHeaderPBPrefix.writeUInt32NoTag(headerSize)
    codedHeaderPBPrefix.flush()

    val signaturePBPrefix      = new ByteArrayOutputStream()
    val codedSignaturePBPrefix = CodedOutputStream.newInstance(signaturePBPrefix)
    codedSignaturePBPrefix.writeTag(PBBlock.SIGNATURE_FIELD_NUMBER, WireFormat.WIRETYPE_LENGTH_DELIMITED)
    codedSignaturePBPrefix.writeUInt32NoTag(maxSignature.toByteArray.length)
    codedSignaturePBPrefix.flush()

    val transactionPBPrefix               = new ByteArrayOutputStream()
    val codedTransactionMaxLengthPBPrefix = CodedOutputStream.newInstance(transactionPBPrefix)
    codedTransactionMaxLengthPBPrefix.writeTag(PBBlock.TRANSACTIONS_FIELD_NUMBER, WireFormat.WIRETYPE_LENGTH_DELIMITED)
    codedTransactionMaxLengthPBPrefix.writeUInt32NoTag(MiningConstraints.MaxTxsSizeInBytes)
    codedTransactionMaxLengthPBPrefix.flush()

    val minPossibleTransactionSize = PBTransactions.protobuf(TxHelpers.transfer(amount = 1, fee = 1)).serializedSize

    val maxSize =
      headerPBPrefix.toByteArray.length + headerSize +
        signaturePBPrefix.toByteArray.length + signatureSize +
        MiningConstraints.MaxTxsSizeInBytes +
        (transactionPBPrefix.toByteArray.length * MiningConstraints.MaxTxsSizeInBytes / minPossibleTransactionSize)

    maxSize should be <= PBBlockSpec.maxLength
  }

  "PBTransactionSpec max length" in {
    val maxSizeTransaction = PBSignedTransaction(
      Some(
        PBTransaction(
          Byte.MaxValue,
          ByteString.copyFrom(bytes32gen.sample.get),
          Some(PBAmounts.fromAssetAndAmount(IssuedAsset(ByteStr(bytes32gen.sample.get)), Long.MaxValue)),
          Long.MaxValue
        )
      ),
      Seq.fill(Proofs.MaxProofs)(ByteString.copyFrom(byteArrayGen(Proofs.MaxProofSize).sample.get))
    )

    val dataPBPrefix      = new ByteArrayOutputStream()
    val codedDataPBPrefix = CodedOutputStream.newInstance(dataPBPrefix)
    codedDataPBPrefix.writeTag(Transaction.COMMIT_TO_GENERATION_FIELD_NUMBER, WireFormat.WIRETYPE_LENGTH_DELIMITED)
    codedDataPBPrefix.flush()

    val size = maxSizeTransaction.serializedSize + dataPBPrefix.toByteArray.length + 1

    size should be <= PBTransactionSpec.maxLength
  }
}
