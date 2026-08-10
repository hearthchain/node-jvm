package tech.hearth.crypto.bls

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.BlsKey as JBlsKey

class BlsKeyPair(private val key: JBlsKey) {
  lazy val publicKey: BlsPublicKey             = BlsPublicKey.unchecked(ByteStr(key.publicKey()))
  def sign(message: Array[Byte]): BlsSignature = BlsSignature.unsafe(ByteStr(key.signBasic(message)))
}

object BlsKeyPair {

  /** @param seed Should be more or equal to 32 bytes, otherwise returns a zero secret key (a point at infinity) */
  def fromSeed(seed: Array[Byte]): BlsKeyPair = BlsKeyPair(JBlsKey.fromSeedKeygenV5(seed))

  /** @param scalar A 32-byte big-endian scalar, e.g. one derived by KeyTree.blsSecretKey */
  def fromScalar(scalar: Array[Byte]): BlsKeyPair = BlsKeyPair(JBlsKey.fromSecretKey(scalar))
}
