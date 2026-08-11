package tech.hearth.state

import com.google.common.primitives.Longs
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.crypto.bls.BlsPublicKey
import tech.hearth.state.StateHash.Section
import tech.hearth.state.StateHashBuilder.Result
import tech.hearth.transaction.Asset.IssuedAsset
import org.bouncycastle.crypto.digests.Blake2bDigest

import scala.collection.mutable

object StateHashBuilder {
  val EmptySectionHash: ByteStr = createSectionHash(Nil)

  final case class Result(hashes: Map[Section, ByteStr]) {
    def createStateHash(prevHash: ByteStr): StateHash = {
      val sortedHashes = StateHash.Section.values.map(hashes.getOrElse(_, EmptySectionHash))
      val payload      = prevHash +: sortedHashes
      StateHash(createSectionHash(payload), hashes)
    }
  }

  private def createSectionHash(bs: Iterable[ByteStr], digestFn: Blake2bDigest = newDigestInstance()): ByteStr = {
    bs.foreach(bs => digestFn.update(bs.arr, 0, bs.arr.length))
    val result = new Array[Byte](crypto.DigestLength)
    digestFn.doFinal(result, 0)
    ByteStr(result)
  }

  private def newDigestInstance(): Blake2bDigest = new Blake2bDigest(crypto.DigestLength * 8)
}

class StateHashBuilder {
  import tech.hearth.utils.byteStrOrdering
  private val maps = Vector.fill(Section.values.length)(mutable.TreeMap.empty[ByteStr, Array[Byte]])

  private def addEntry(section: Section, key: Array[Byte]*)(value: Array[Byte]*): Unit = {
    val solidKey   = ByteStr(key.foldLeft(Array.emptyByteArray)(_ ++ _))
    val solidValue = value.foldLeft(Array.emptyByteArray)(_ ++ _)
    maps(section.ordinal)(solidKey) = solidValue
  }

  def addHearthBalance(address: Address, balance: Long): Unit = {
    addEntry(Section.HearthBalance, address.toBytes)(Longs.toByteArray(balance))
  }

  def addAssetBalance(address: Address, asset: IssuedAsset, balance: Long): Unit = {
    addEntry(Section.AssetBalance, address.toBytes, asset.id.arr)(
      Longs.toByteArray(balance)
    )
  }

  def addLeaseBalance(address: Address, leaseIn: Long, leaseOut: Long): Unit = {
    addEntry(Section.LeaseBalance, address.toBytes)(
      Longs.toByteArray(leaseIn),
      Longs.toByteArray(leaseOut)
    )
  }

  def addLeaseStatus(leaseId: ByteStr, isActive: Boolean): Unit = {
    addEntry(Section.LeaseStatus, leaseId.arr)(
      if (isActive) Array(1: Byte) else Array(0: Byte)
    )
  }

  def addCommittedGeneratorBalances(balances: Seq[Long]): Unit = {
    addEntry(Section.CommittedGeneratorBalances)(
      balances.map(Longs.toByteArray)*
    )
  }

  def addNextCommittedGenerator(commitment: GenerationCommitment): Unit = {
    addEntry(Section.NextCommittedGenerators, commitment.sender.arr)(
      commitment.endorserPublicKey.arr,
      commitment.vrfPublicKey.arr
    )
  }

  def result(): Result =
    Result(
      maps
        .zip(Section.values)
        .collect {
          case (hs, s) if hs.nonEmpty =>
            s -> StateHashBuilder.createSectionHash(hs.flatMap { case (k, v) => Seq(k, ByteStr(v)) }, StateHashBuilder.newDigestInstance())
        }
        .toMap
    )
}
