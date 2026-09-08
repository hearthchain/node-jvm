package tech.hearth.mining

import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.bls.{BlsKeyPair, BlsPublicKey, BlsSignature}
import tech.hearth.settings.{MinerSettings, MiningAccount as MiningAccountSettings}
import tech.hearth.state.Height
import tech.hearth.transaction.CommitToGenerationTransaction
import tech.hearth.crypto.{Bip39, Hex, KeyTree, SigningKey, VrfKey}

/** The generator keys of this node, as a set of operations rather than as key material.
  *
  * The accounts a node generates with come from `hearth.miner.accounts`, and nothing else holds them: the wallet is for
  * signing transactions and derives its accounts from a single seed, which is the reason those settings exist. Both the
  * endorser and the REST API need to act with these keys, so they take this instead - it hands out public keys and
  * signatures over data it is given, never a secret, so an API key reaches no further than the commitments this node is
  * entitled to make for itself.
  */
trait GeneratorKeys {

  /** The accounts themselves, for the miner - it holds the keys it generates with by nature of what it does. */
  def accounts: Seq[MiningAccount]

  def contains(address: Address): Boolean

  /** The endorser public key committed for this address, as `/addresses/bls/{address}` reports it. */
  def endorserPublicKey(address: Address): Option[BlsPublicKey]

  /** Signs with this address' endorser key. The endorser needs to produce signatures, not to hold the key. */
  def signWithEndorserKey(address: Address, message: Array[Byte]): Option[BlsSignature]

  /** Everything a CommitToGenerationTransaction has to carry about this address' generator keys: the public keys, and
    * the proofs that this node holds the secrets behind them.
    */
  def commitment(address: Address, generationPeriodStart: Height): Option[GeneratorKeys.Commitment]

  /** The key a CommitToGenerationTransaction from this address is signed with. The commitment registers the account's
    * own generator keys, so it is signed by that account, which is a mining account by definition - the wallet is not
    * consulted for it.
    */
  def signingKey(address: Address): Option[SigningKey]
}

object GeneratorKeys {

  /** The accounts as `hearth.miner.accounts` describes them: either derived from a mnemonic at the given nonces, or
    * built from explicitly configured key material. Parsed here rather than in the miner because the endorser and the
    * REST API need the same set, and a node that mines is not the only one that has it.
    */
  def fromSettings(settings: MinerSettings): GeneratorKeys = apply(settings.accounts.map { ma =>
    ma.mnemonic match {
      case Some(mnemonic) =>
        require(
          ma.signingKeySeed.isEmpty && ma.signingKeyScalar.isEmpty && ma.vrfKey.isEmpty && ma.blsKey.isEmpty && ma.blsKeyScalar.isEmpty,
          "when mnemonic is specified, explicit private keys can not be specified"
        )
        val seed = Bip39.toSeed(mnemonic)
        MiningAccount(
          KeyTree.signingKey(seed, ma.signingAccount),
          KeyTree.vrfKey(seed, ma.vrfAccount),
          BlsKeyPair.fromScalar(KeyTree.blsSecretKey(seed, ma.blsAccount))
        )
      case None =>
        MiningAccount(
          signingKeyOf(ma),
          VrfKey.fromSeed(Hex.decode(ma.vrfKey.getOrElse(throw new IllegalArgumentException("vrf-key is required when mnemonic is not provided")))),
          blsKeyOf(ma)
        )
    }
  })

  /** A signing key has no seed when it comes off a grinder: a vanity search walks scalars, and no seed hashes to the
    * one that wins, so `signing-key-scalar` takes that scalar directly. Either way in produces the same key type.
    */
  private def signingKeyOf(ma: MiningAccountSettings): SigningKey = (ma.signingKeySeed, ma.signingKeyScalar) match {
    case (Some(seed), None)   => SigningKey.fromSeed(Hex.decode(seed))
    case (None, Some(scalar)) => SigningKey.fromScalar(Hex.decode(scalar))
    case (Some(_), Some(_))   => throw new IllegalArgumentException("signing-key-seed and signing-key-scalar are mutually exclusive")
    case (None, None) =>
      throw new IllegalArgumentException("signing-key-seed or signing-key-scalar is required when mnemonic is not provided")
  }

  /** A BLS key comes either from a seed, which EIP-2333 KeyGen stretches into the secret scalar, or as that scalar
    * itself - which is what `KeyTree.blsSecretKey` derives from a mnemonic, and so what `hearth util crypto
    * create-keys` prints. The two readings of the same 32 bytes give different endorser keys, hence the separate key.
    */
  private def blsKeyOf(ma: MiningAccountSettings): BlsKeyPair = (ma.blsKey, ma.blsKeyScalar) match {
    case (Some(seed), None)   => BlsKeyPair.fromSeed(Hex.decode(seed))
    case (None, Some(scalar)) => BlsKeyPair.fromScalar(Hex.decode(scalar))
    case (Some(_), Some(_))   => throw new IllegalArgumentException("bls-key and bls-key-scalar are mutually exclusive")
    case (None, None) =>
      throw new IllegalArgumentException("bls-key or bls-key-scalar is required when mnemonic is not provided")
  }

  case class Commitment(
      endorserPublicKey: BlsPublicKey,
      commitmentSignature: BlsSignature,
      vrfPublicKey: ByteStr,
      vrfCommitmentSignature: ByteStr
  )

  val Empty: GeneratorKeys = apply(Nil)

  def apply(as: Seq[MiningAccount]): GeneratorKeys = new GeneratorKeys {
    override val accounts: Seq[MiningAccount]          = as
    private val byAddress: Map[Address, MiningAccount] = as.map(a => a.address -> a).toMap

    override def contains(address: Address): Boolean = byAddress.contains(address)

    override def endorserPublicKey(address: Address): Option[BlsPublicKey] = byAddress.get(address).map(_.blsKey.publicKey)

    override def signWithEndorserKey(address: Address, message: Array[Byte]): Option[BlsSignature] =
      byAddress.get(address).map(_.blsKey.sign(message))

    override def commitment(address: Address, generationPeriodStart: Height): Option[Commitment] =
      byAddress.get(address).map { account =>
        Commitment(
          endorserPublicKey = account.blsKey.publicKey,
          commitmentSignature = CommitToGenerationTransaction.mkPopSignature(account.blsKey, generationPeriodStart),
          vrfPublicKey = ByteStr(account.vrfKey.publicKey()),
          vrfCommitmentSignature = CommitToGenerationTransaction.mkVrfPopSignature(account.vrfKey, generationPeriodStart)
        )
      }

    override def signingKey(address: Address): Option[SigningKey] = byAddress.get(address).map(_.signingKey)
  }
}
