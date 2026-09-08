package tech.hearth.crypto

import tech.hearth.utils.randomBytes

import java.nio.charset.StandardCharsets

/** BIP-39 phrase generation, which the crypto library only reads (`Bip39.validate`/`toSeed`): 256 bits from a secure
  * random source plus the leading byte of their SHA-256 as the checksum, cut into 11-bit indices over the standard
  * English wordlist that ships with the library. A 24-word phrase, so it carries the full 256 bits an account key is
  * worth.
  */
object Mnemonic {
  private val EntropyBytes = 32

  private lazy val wordlist: IndexedSeq[String] = {
    val stream = Option(getClass.getResourceAsStream("/bip39/english.txt")).getOrElse(sys.error("BIP-39 wordlist is not on the classpath"))
    try new String(stream.readAllBytes(), StandardCharsets.UTF_8).linesIterator.map(_.trim).filter(_.nonEmpty).toIndexedSeq
    finally stream.close()
  }

  def generate(): String = {
    val entropy = randomBytes(EntropyBytes)
    (entropy :+ Sha256.hash(entropy).head)
      .map(b => String.format("%8s", Integer.toBinaryString(b & 0xff)).replace(' ', '0'))
      .mkString
      .grouped(11)
      .map(index => wordlist(Integer.parseInt(index, 2)))
      .mkString(" ")
  }

  def isValid(mnemonic: String): Boolean = Bip39.validate(mnemonic).isValid

  /** The key material [[KeyTree]] derives at an account index, in the form `hearth.miner.accounts` takes it. SLIP-10
    * hands out the signing and VRF private keys, and those are exactly the seeds `SigningKey.fromSeed`/`VrfKey.fromSeed`
    * take, so `signing-key-seed`/`vrf-key` reproduce the derived keys; the BLS secret is a scalar rather than a seed,
    * which is why it configures a node as `bls-key-scalar` and not as `bls-key`.
    */
  object Keys {
    def signingKeySeed(mnemonic: String, account: Int): Array[Byte] =
      Slip10.derivePath(Bip39.toSeed(mnemonic), KeyTree.signingPath(account)).privateKey()

    def vrfKeySeed(mnemonic: String, account: Int): Array[Byte] =
      Slip10.derivePath(Bip39.toSeed(mnemonic), KeyTree.vrfPath(account)).privateKey()

    def blsKeyScalar(mnemonic: String, account: Int): Array[Byte] =
      KeyTree.blsSecretKey(Bip39.toSeed(mnemonic), account)
  }
}
