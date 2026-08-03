package tech.hearth.crypto.bls

import tech.hearth.common.state.ByteStr
import supranational.blst.SecretKey

class BlsKeyPair(private val secretKey: SecretKey) {
  lazy val publicKey: BlsPublicKey             = BlsPublicKey.unchecked(ByteStr(BlsUtils.mkPublicKey(secretKey)))
  def sign(message: Array[Byte]): BlsSignature = BlsSignature.unsafe(ByteStr(BlsUtils.signBasic(secretKey, message)))
}

object BlsKeyPair {
  def fromSeed(seed: Array[Byte]): BlsKeyPair = BlsKeyPair(BlsUtils.mkSecretKey(seed))

  /** @param scalar A 32-byte big-endian scalar, e.g. one derived by KeyTree.blsSecretKey */
  def fromScalar(scalar: Array[Byte]): BlsKeyPair = BlsKeyPair(BlsUtils.secretKeyFromScalar(scalar))
}
