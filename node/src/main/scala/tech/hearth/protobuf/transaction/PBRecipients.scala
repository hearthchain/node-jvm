package tech.hearth.protobuf.transaction

import com.google.protobuf.ByteString
import tech.hearth.account.PublicKey
import tech.hearth.crypto
import tech.hearth.lang.ValidationError
import tech.hearth.protobuf.transaction.Recipient as PBRecipient
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.crypto.Address

object PBRecipients {
  // The canonical on-chain form is the 21-byte payload (version || hash), which is what toAddress parses back.
  // Writing the bare 20-byte hash here would make a recipient unreadable once it is stored or serialized.
  def create(recipient: Address): PBRecipient = PBRecipient().withPublicKeyHash(ByteString.copyFrom(recipient.toBytes()))

  def toAddress(bytes: Array[Byte]): Either[ValidationError, Address] = bytes.length match {
    case Address.HASH_LEN => // Compressed address
      tech.hearth.account.Address.fromBytes(bytes)

    case crypto.KeyLength => // Public key
      Right(tech.hearth.account.Address.fromPublicKey(PublicKey(bytes)))

    case _ =>
      Left(GenericError(s"Invalid address length: ${bytes.length}"))
  }

  // A Recipient is only ever a public key hash now, rather than a oneof of that and an alias
  def toAddress(r: PBRecipient): Either[ValidationError, Address] =
    if (r.publicKeyHash.isEmpty) Left(GenericError(s"Not an address: $r"))
    else toAddress(r.publicKeyHash.toByteArray)
}
