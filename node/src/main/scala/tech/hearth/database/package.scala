package tech.hearth

import com.google.common.collect.Maps
import com.google.common.io.ByteStreams.{newDataInput, newDataOutput}
import com.google.common.io.{ByteArrayDataInput, ByteArrayDataOutput}
import com.google.common.primitives.{Ints, Longs}
import com.google.protobuf.ByteString
import tech.hearth.account.PublicKey
import tech.hearth.block.validation.Validators
import tech.hearth.block.{Block, BlockHeader}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.*
import tech.hearth.crypto.bls.BlsPublicKey
import tech.hearth.database.protobuf as pb
import tech.hearth.database.protobuf.TransactionData.Transaction as TD
import tech.hearth.protobuf.block.PBBlocks
import tech.hearth.protobuf.snapshot.TransactionStateSnapshot
import tech.hearth.protobuf.transaction.{PBRecipients, PBTransactions}
import tech.hearth.protobuf.{PBSnapshots, toByteStr, toByteString, toPublicKey}
import tech.hearth.state.*
import tech.hearth.state.StateHash.Section
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.{Transaction, TxPositiveAmount, TxValidationError}
import tech.hearth.utils.*
import monix.eval.Task
import monix.reactive.Observable
import org.rocksdb.*
import sun.nio.ch.Util

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Map as JMap
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.collection.{View, mutable}
import scala.jdk.CollectionConverters.*

//noinspection UnstableApiUsage
package object database {
  final type DBEntry = JMap.Entry[Array[Byte], Array[Byte]]

  implicit class ByteArrayDataOutputExt(val output: ByteArrayDataOutput) extends AnyVal {
    def writeByteStr(s: ByteStr): Unit = {
      output.write(s.arr)
    }
  }

  implicit class ByteArrayDataInputExt(val input: ByteArrayDataInput) extends AnyVal {
    def readBytes(len: Int): Array[Byte] = {
      val arr = new Array[Byte](len)
      input.readFully(arr)
      arr
    }

    def readByteStr(len: Int): ByteStr = {
      ByteStr(readBytes(len))
    }
  }

  def writeIntSeq(values: Seq[Int]): Array[Byte] = {
    values.foldLeft(ByteBuffer.allocate(4 * values.length))(_ `putInt` _).array()
  }

  def readIntSeq(data: Array[Byte]): Seq[Int] = Option(data).fold(Seq.empty[Int]) { d =>
    val in = ByteBuffer.wrap(data)
    Seq.fill(d.length / 4)(in.getInt)
  }

  def readAddressIds(data: Array[Byte]): Seq[AddressId] = Option(data).fold(Seq.empty[AddressId]) { d =>
    require(d.length % java.lang.Long.BYTES == 0, s"Invalid data length: ${d.length}")
    val buffer = ByteBuffer.wrap(data)
    Seq.fill(d.length / java.lang.Long.BYTES)(AddressId(buffer.getLong))
  }

  def writeAddressIds(values: Seq[AddressId]): Array[Byte] =
    values.foldLeft(ByteBuffer.allocate(values.length * java.lang.Long.BYTES)) { case (buf, aid) => buf.putLong(aid.toLong) }.array()

  def readAssetIds(data: Array[Byte]): Seq[ByteStr] = Option(data).fold(Seq.empty[ByteStr]) { d =>
    require(d.length % transaction.AssetIdLength == 0, s"Invalid data length: ${d.length}")
    val buffer = ByteBuffer.wrap(d)
    Seq.fill(d.length / transaction.AssetIdLength) {
      val idBytes = new Array[Byte](transaction.AssetIdLength)
      buffer.get(idBytes)
      ByteStr(idBytes)
    }
  }

  def writeAssetIds(values: Seq[ByteStr]): Array[Byte] =
    values.foldLeft(ByteBuffer.allocate(values.length * transaction.AssetIdLength)) { case (buf, ai) => buf.put(ai.arr) }.array()

  def readStrings(data: Array[Byte]): Seq[String] = Option(data).fold(Seq.empty[String]) { _ =>
    var i = 0
    val s = Seq.newBuilder[String]

    while (i < data.length) {
      val len = ((data(i) << 8) | (data(i + 1) & 0xff)).toShort // Optimization
      s += new String(data, i + 2, len, StandardCharsets.UTF_8)
      i += (2 + len)
    }
    s.result()
  }

  def writeStrings(strings: Seq[String]): Array[Byte] = {
    val utfBytes = strings.toVector.map(_.utf8Bytes)
    utfBytes
      .foldLeft(ByteBuffer.allocate(utfBytes.map(_.length + 2).sum)) { case (buf, bytes) =>
        buf.putShort(bytes.length.toShort).put(bytes)
      }
      .array()
  }

  // Generic length-prefixed Seq[ByteStr], for a list of opaque byte blobs of varying length (e.g. FMSPCs seen at a
  // given height for DCAP TCB Info rollback) - the same shape readStrings/writeStrings already use for Seq[String].
  def readByteStrSeq(data: Array[Byte]): Seq[ByteStr] = Option(data).fold(Seq.empty[ByteStr]) { _ =>
    var i = 0
    val s = Seq.newBuilder[ByteStr]

    while (i < data.length) {
      val len = ((data(i) << 8) | (data(i + 1) & 0xff)).toShort
      s += ByteStr(data.slice(i + 2, i + 2 + len))
      i += (2 + len)
    }
    s.result()
  }

  def writeByteStrSeq(values: Seq[ByteStr]): Array[Byte] =
    values
      .foldLeft(ByteBuffer.allocate(values.map(_.arr.length + 2).sum)) { case (buf, v) =>
        buf.putShort(v.arr.length.toShort).put(v.arr)
      }
      .array()

  def readLeaseBalanceNode(data: Array[Byte]): LeaseBalanceNode = if (data != null && data.length == 20)
    LeaseBalanceNode(Longs.fromByteArray(data.take(8)), Longs.fromByteArray(data.slice(8, 16)), Height(Ints.fromByteArray(data.takeRight(4))))
  else LeaseBalanceNode.Empty

  def writeLeaseBalanceNode(leaseBalanceNode: LeaseBalanceNode): Array[Byte] =
    Longs.toByteArray(leaseBalanceNode.in) ++ Longs.toByteArray(leaseBalanceNode.out) ++ leaseBalanceNode.prevHeight.toByteArray

  def readLeaseBalance(data: Array[Byte]): CurrentLeaseBalance = if (data != null && data.length == 24)
    CurrentLeaseBalance(
      Longs.fromByteArray(data.take(8)),
      Longs.fromByteArray(data.slice(8, 16)),
      Height(Ints.fromByteArray(data.slice(16, 20))),
      Height(Ints.fromByteArray(data.takeRight(4)))
    )
  else CurrentLeaseBalance.Unavailable

  def writeLeaseBalance(lb: CurrentLeaseBalance): Array[Byte] =
    Longs.toByteArray(lb.in) ++ Longs.toByteArray(lb.out) ++ lb.height.toByteArray ++ lb.prevHeight.toByteArray

  def writeLeaseDetails(ld: LeaseDetails): Array[Byte] =
    pb.LeaseDetails(
      ByteString.copyFrom(ld.sender.arr),
      Some(PBRecipients.create(ld.recipientAddress)),
      ld.amount.value,
      ByteString.copyFrom(ld.sourceId.arr),
      ld.height.toInt,
      ld.status match {
        case LeaseDetails.Status.Active => pb.LeaseDetails.CancelReason.Empty
        case LeaseDetails.Status.Cancelled(height, cancelTxId) =>
          pb.LeaseDetails.CancelReason
            .Cancelled(pb.LeaseDetails.Cancelled(height.toInt, cancelTxId.fold(ByteString.EMPTY)(id => ByteString.copyFrom(id.arr))))
        case LeaseDetails.Status.Expired(height) => pb.LeaseDetails.CancelReason.Expired(pb.LeaseDetails.Expired(height.toInt))
      }
    ).toByteArray

  def readLeaseDetails(data: Array[Byte]): LeaseDetails = {
    val d = pb.LeaseDetails.parseFrom(data)
    LeaseDetails(
      LeaseStaticInfo(
        d.senderPublicKey.toPublicKey,
        PBRecipients.toAddress(d.recipient.get).explicitGet(),
        TxPositiveAmount.unsafeFrom(d.amount),
        TransactionId(d.sourceId.toByteStr),
        Height(d.height)
      ),
      d.cancelReason match {
        case pb.LeaseDetails.CancelReason.Expired(pb.LeaseDetails.Expired(height, _)) => LeaseDetails.Status.Expired(Height(height))
        case pb.LeaseDetails.CancelReason.Cancelled(pb.LeaseDetails.Cancelled(height, transactionId, _)) =>
          LeaseDetails.Status.Cancelled(Height(height), Some(transactionId).collect { case id if !id.isEmpty => TransactionId(id.toByteStr) })
        case pb.LeaseDetails.CancelReason.Empty => LeaseDetails.Status.Active
      }
    )
  }

  def readVolumeAndFeeNode(data: Array[Byte]): VolumeAndFeeNode = if (data != null && data.length == 20)
    VolumeAndFeeNode(Longs.fromByteArray(data.take(8)), Longs.fromByteArray(data.slice(8, 16)), Height(Ints.fromByteArray(data.takeRight(4))))
  else VolumeAndFeeNode.Empty

  def writeVolumeAndFeeNode(volumeAndFeeNode: VolumeAndFeeNode): Array[Byte] =
    Longs.toByteArray(volumeAndFeeNode.volume) ++ Longs.toByteArray(volumeAndFeeNode.fee) ++ volumeAndFeeNode.prevHeight.toByteArray

  def readVolumeAndFee(data: Array[Byte]): CurrentVolumeAndFee = if (data != null && data.length == 24)
    CurrentVolumeAndFee(
      Longs.fromByteArray(data.take(8)),
      Longs.fromByteArray(data.slice(8, 16)),
      Height(Ints.fromByteArray(data.slice(16, 20))),
      Height(Ints.fromByteArray(data.takeRight(4)))
    )
  else CurrentVolumeAndFee.Unavailable

  def writeVolumeAndFee(vf: CurrentVolumeAndFee): Array[Byte] =
    Longs.toByteArray(vf.volume) ++ Longs.toByteArray(vf.fee) ++ vf.height.toByteArray ++ vf.prevHeight.toByteArray

  def readFeatureMap(data: Array[Byte]): Map[Short, Height] = Option(data).fold(Map.empty) { _ =>
    val b        = ByteBuffer.wrap(data)
    val features = Map.newBuilder[Short, Height]
    while (b.hasRemaining) {
      features += b.getShort -> Height(b.getInt)
    }

    features.result()
  }

  def writeFeatureMap(features: Map[Short, Height]): Array[Byte] = {
    val b = ByteBuffer.allocate(features.size * 6)
    for ((featureId, height) <- features)
      b.putShort(featureId).putInt(height.toInt)

    b.array()
  }

  def readAssetMinFee(data: Array[Byte]): MinAssetFee = {
    val ndi = newDataInput(data)
    MinAssetFee.unsafeFrom(ndi.readLong())
  }

  def writeAssetMinFee(minFee: MinAssetFee): Array[Byte] = {
    val ndo = newDataOutput()
    ndo.writeLong(minFee.value)
    ndo.toByteArray
  }

  def readAssetVolumeDetails(data: Array[Byte]): BigInt = {
    val pbad = pb.AssetVolumeDetails.parseFrom(data)
    BigInt(pbad.totalVolume.toByteArray)
  }

  def writeAssetVolumeDetails(volume: BigInt): Array[Byte] =
    pb.AssetVolumeDetails(ByteString.copyFrom(volume.toByteArray)).toByteArray

  def writeBlockMeta(data: pb.BlockMeta): Array[Byte] = data.toByteArray

  def readBlockMeta(bs: Array[Byte]): pb.BlockMeta = pb.BlockMeta.parseFrom(bs)

  def readTransactionHNSeqAndType(bs: Array[Byte]): (Height, Seq[(Byte, TxNum, Int)]) = {
    val ndi          = newDataInput(bs)
    val height       = Height(ndi.readInt())
    val numSeqLength = ndi.readInt()

    (
      height,
      List.fill(numSeqLength) {
        val tp   = ndi.readByte()
        val num  = TxNum(ndi.readShort())
        val size = ndi.readInt()
        (tp, num, size)
      }
    )
  }

  def writeTransactionHNSeqAndType(v: (Height, Seq[(Byte, TxNum, Int)])): Array[Byte] = {
    val (height, numSeq) = v
    val numSeqLength     = numSeq.length

    val outputLength = 4 + 4 + numSeqLength * (1 + 2 + 4)
    val ndo          = newDataOutput(outputLength)

    ndo.writeInt(height.toInt)
    ndo.writeInt(numSeqLength)
    numSeq.foreach { case (tp, num, size) =>
      ndo.writeByte(tp)
      ndo.writeShort(num.toShort)
      ndo.writeInt(size)
    }

    ndo.toByteArray
  }

  def readLeaseIdSeq(data: Array[Byte]): Seq[ByteStr] =
    pb.LeaseIds
      .parseFrom(data)
      .ids
      .map(_.toByteStr)

  def writeLeaseIdSeq(ids: Seq[ByteStr]): Array[Byte] =
    pb.LeaseIds(ids.map(_.toByteString)).toByteArray

  def readStateHash(bs: Array[Byte]): StateHash = {
    val ndi           = newDataInput(bs)
    val sectionsCount = ndi.readByte()
    val sections = (0 until sectionsCount).map { _ =>
      val sectionId = ndi.readByte()
      val value     = ndi.readByteStr(DigestLength)
      Section.fromOrdinal(sectionId) -> value
    }
    val totalHash = ndi.readByteStr(DigestLength)
    StateHash(totalHash, sections.toMap)
  }

  def writeStateHash(sh: StateHash): Array[Byte] = {
    val sorted = sh.sectionHashes.toSeq
    val ndo    = newDataOutput(crypto.DigestLength + 1 + sorted.length * (1 + crypto.DigestLength))
    ndo.writeByte(sorted.length)
    sorted.foreach { case (sectionId, value) =>
      ndo.writeByte(sectionId.ordinal)
      ndo.writeByteStr(value.ensuring(_.arr.length == DigestLength))
    }
    ndo.writeByteStr(sh.totalHash.ensuring(_.arr.length == DigestLength))
    ndo.toByteArray
  }

  def readCurrentBalance(bs: Array[Byte]): CurrentBalance = if (bs != null && bs.length == 16)
    CurrentBalance(Longs.fromByteArray(bs.take(8)), Height(Ints.fromByteArray(bs.slice(8, 12))), Height(Ints.fromByteArray(bs.takeRight(4))))
  else CurrentBalance.Unavailable

  def writeCurrentBalance(balance: CurrentBalance): Array[Byte] =
    Longs.toByteArray(balance.balance) ++ balance.height.toByteArray ++ balance.prevHeight.toByteArray

  def readBalanceNode(bs: Array[Byte]): BalanceNode = if (bs != null && bs.length == BalanceNode.SizeInBytes)
    BalanceNode(Longs.fromByteArray(bs.take(8)), Height(Ints.fromByteArray(bs.takeRight(4))))
  else BalanceNode.Empty

  def writeBalanceNode(balance: BalanceNode): Array[Byte] =
    Longs.toByteArray(balance.balance) ++ balance.prevHeight.toByteArray

  def readGeneratorBalances(data: Array[Byte]): Seq[(GeneratorIndex, Long)] = {
    val bs = ByteBuffer.wrap(data)
    Seq.fill(data.length / 12)(GeneratorIndex(bs.getInt) -> bs.getLong)
  }

  def writeGeneratorBalances(data: Seq[(GeneratorIndex, Long)]): Array[Byte] =
    data
      .foldLeft(ByteBuffer.allocate(data.length * 12)) { case (bs, (idx, balance)) =>
        bs.putInt(idx.toInt).putLong(balance)
      }
      .array()

  /** Each record is addressId ++ BLS public key ++ VRF public key, all fixed width */
  def readCommittedGenerators(data: Array[Byte]): Seq[(AddressId, BlsPublicKey, ByteStr)] = {
    val addressSize = Longs.BYTES
    data
      .grouped(addressSize + BlsPublicKey.SizeInBytes + crypto.KeyLength)
      .map { data =>
        val (addressIdBytes, rest)            = data.splitAt(addressSize)
        val (blsPublicKeyBytes, vrfPublicKey) = rest.splitAt(BlsPublicKey.SizeInBytes)
        (
          Longs.fromByteArray(addressIdBytes),
          BlsPublicKey(blsPublicKeyBytes).explicitGet(),
          ByteStr(vrfPublicKey)
        )
      }
      .toSeq
  }

  def writeCommittedGenerators(data: Seq[(AddressId, BlsPublicKey, ByteStr)]): Array[Byte] =
    data.view.flatMap { (addressId, blsPublicKey, vrfPublicKey) =>
      Longs.toByteArray(addressId) ++ blsPublicKey.arr ++ vrfPublicKey.arr
    }.toArray

  def readConflictGenerators(data: Array[Byte]): Seq[GeneratorIndex] = data
    .grouped(Ints.BYTES)
    .map { bytes => GeneratorIndex(Ints.fromByteArray(bytes)) }
    .toSeq

  def writeConflictGenerators(data: Seq[GeneratorIndex]): Array[Byte] = data.view.flatMap(i => Ints.toByteArray(i.toInt)).toArray

  def readCommitmentTransactions(data: Array[Byte]): Seq[TransactionId] = {
    val transactionSize = DigestLength
    data
      .grouped(transactionSize)
      .map { bytes => TransactionId(ByteStr(bytes)) }
      .toSeq
  }

  def writeCommitmentTransactions(data: Seq[TransactionId]): Array[Byte] = data.view.flatMap(_.arr).toArray

  def getKeyBuffersFromKeys(keys: collection.IndexedSeq[Key[?]]): collection.IndexedSeq[ByteBuffer] =
    keys.map { k =>
      val arr = k.keyBytes
      val b   = Util.getTemporaryDirectBuffer(arr.length)
      b.put(k.keyBytes).flip()
      b
    }

  def getKeyBuffers(keys: collection.IndexedSeq[Array[Byte]]): collection.IndexedSeq[ByteBuffer] =
    keys.map { k =>
      val b = Util.getTemporaryDirectBuffer(k.length)
      b.put(k).flip()
      b
    }

  def getValueBuffers(amount: Int, bufferSize: Int): IndexedSeq[ByteBuffer] =
    IndexedSeq
      .fill(amount) {
        val buf = Util.getTemporaryDirectBuffer(bufferSize)
        buf.limit(buf.capacity())
        buf
      }

  def getValueBuffers(bufferSizes: collection.IndexedSeq[Int]): collection.IndexedSeq[ByteBuffer] =
    bufferSizes.map { size =>
      val buf = Util.getTemporaryDirectBuffer(size)
      buf.limit(buf.capacity())
      buf
    }

  implicit class DBExt(val db: RocksDB) extends AnyVal {

    def readOnly[A](f: ReadOnlyDB => A): A = withReadOptions { ro => f(new ReadOnlyDB(db, ro)) }

    /** @note
      *   Runs operations in batch, so keep in mind, that previous changes don't appear lately in f
      */
    def readWrite[A](f: RW => A): A = withOptions { (ro, wo) => readWriteWithOptions(ro, wo)(f) }

    def readWriteWithOptions[A](readOptions: ReadOptions, writeOptions: WriteOptions)(f: RW => A): A = {
      val batch = new WriteBatch()
      val rw    = new RW(db, readOptions, batch)
      try {
        val r = f(rw)
        db.write(writeOptions, batch)
        r
      } finally batch.close()
    }

    def withOptions[A](f: (ReadOptions, WriteOptions) => A): A =
      withReadOptions { ro =>
        withWriteOptions { wo =>
          f(ro, wo)
        }
      }

    def withWriteOptions[A](f: WriteOptions => A): A = {
      val wo = new WriteOptions()
      try f(wo)
      finally wo.close()
    }

    def withReadOptions[A](f: ReadOptions => A): A = {
      val snapshot = db.getSnapshot
      // checksum may be verification is **very** expensive, so it's explicitly disabled
      val ro = new ReadOptions().setSnapshot(snapshot).setVerifyChecksums(false)
      try f(ro)
      finally {
        ro.close()
        db.releaseSnapshot(snapshot)
      }
    }

    def multiGetOpt[A](readOptions: ReadOptions, keys: collection.IndexedSeq[Key[Option[A]]], valBufSize: Int): Seq[Option[A]] =
      multiGetOpt(readOptions, keys, getKeyBuffersFromKeys(keys), getValueBuffers(keys.size, valBufSize))

    def multiGetOpt[A](
        readOptions: ReadOptions,
        keys: collection.IndexedSeq[Key[Option[A]]],
        valBufSizes: collection.IndexedSeq[Int]
    ): Seq[Option[A]] =
      multiGetOpt(readOptions, keys, getKeyBuffersFromKeys(keys), getValueBuffers(valBufSizes))

    def multiGet[A](readOptions: ReadOptions, keys: ArrayBuffer[Key[A]], valBufSizes: ArrayBuffer[Int]): View[A] =
      multiGet(readOptions, keys, getKeyBuffersFromKeys(keys), getValueBuffers(valBufSizes))

    def multiGet[A](readOptions: ReadOptions, keys: ArrayBuffer[Key[A]], valBufSize: Int): View[A] =
      multiGet(readOptions, keys, getKeyBuffersFromKeys(keys), getValueBuffers(keys.size, valBufSize))

    def multiGet[A](readOptions: ReadOptions, keys: collection.IndexedSeq[Key[A]], valBufSize: Int): Seq[Option[A]] = {
      val keyBufs = getKeyBuffersFromKeys(keys)
      val valBufs = getValueBuffers(keys.size, valBufSize)

      val cfhs = keys.map(_.columnFamilyHandle.getOrElse(db.getDefaultColumnFamily)).asJava
      val result = keys.view
        .zip(db.multiGetByteBuffers(readOptions, cfhs, keyBufs.asJava, valBufs.asJava).asScala)
        .map { case (parser, value) =>
          if (value.status.getCode == Status.Code.Ok) {
            val arr = new Array[Byte](value.requiredSize)
            value.value.get(arr)
            Util.releaseTemporaryDirectBuffer(value.value)
            Some(parser.parse(arr))
          } else None
        }
        .toSeq

      keyBufs.foreach(Util.releaseTemporaryDirectBuffer)
      result
    }

    def multiGetInts(readOptions: ReadOptions, keys: collection.IndexedSeq[Key[Int]]): Seq[Option[Int]] = {
      val keyBytes = keys.map(_.keyBytes)
      val keyBufs  = getKeyBuffers(keyBytes)
      val valBufs  = getValueBuffers(keyBytes.size, 4)

      val cfhs = keys.map(_.columnFamilyHandle.getOrElse(db.getDefaultColumnFamily)).asJava
      val result = db
        .multiGetByteBuffers(readOptions, cfhs, keyBufs.asJava, valBufs.asJava)
        .asScala
        .map { value =>
          if (value.status.getCode == Status.Code.Ok) {
            val h = Some(value.value.getInt)
            Util.releaseTemporaryDirectBuffer(value.value)
            h
          } else None
        }
        .toSeq

      keyBufs.foreach(Util.releaseTemporaryDirectBuffer)
      result
    }

    def multiGetFlat[A](readOptions: ReadOptions, keys: ArrayBuffer[Key[Option[A]]], valBufSizes: ArrayBuffer[Int]): Seq[A] = {
      val keyBufs = getKeyBuffersFromKeys(keys)
      val valBufs = getValueBuffers(valBufSizes)

      val cfhs = keys.map(_.columnFamilyHandle.getOrElse(db.getDefaultColumnFamily)).asJava
      val result = keys.view
        .zip(db.multiGetByteBuffers(readOptions, cfhs, keyBufs.asJava, valBufs.asJava).asScala)
        .flatMap { case (parser, value) =>
          if (value.status.getCode == Status.Code.Ok) {
            val arr = new Array[Byte](value.requiredSize)
            value.value.get(arr)
            Util.releaseTemporaryDirectBuffer(value.value)
            parser.parse(arr)
          } else None
        }
        .toSeq

      keyBufs.foreach(Util.releaseTemporaryDirectBuffer)
      result
    }

    def get[A](key: Key[A]): A = key.parse(db.get(key.columnFamilyHandle.getOrElse(db.getDefaultColumnFamily), key.keyBytes))
    def get[A](key: Key[A], readOptions: ReadOptions): A =
      key.parse(db.get(key.columnFamilyHandle.getOrElse(db.getDefaultColumnFamily), readOptions, key.keyBytes))
    def has(key: Key[?]): Boolean = db.get(key.columnFamilyHandle.getOrElse(db.getDefaultColumnFamily), key.keyBytes) != null

    def iterateOver(tag: KeyTag, cfh: Option[ColumnFamilyHandle] = None)(f: DBEntry => Unit): Unit =
      iterateOver(tag.prefixBytes, cfh)(f)

    def iterateOver(prefix: Array[Byte], cfh: Option[ColumnFamilyHandle])(f: DBEntry => Unit): Unit = {
      @tailrec
      def loop(iter: RocksIterator): Unit = {
        if (iter.isValid && iter.key().startsWith(prefix)) {
          f(Maps.immutableEntry(iter.key(), iter.value()))
          iter.next()
          loop(iter)
        } else ()
      }

      withReadOptions { ro =>
        val iterator = db.newIterator(cfh.getOrElse(db.getDefaultColumnFamily), ro.setTotalOrderSeek(true))
        try {
          iterator.seek(prefix)
          loop(iterator)
        } finally iterator.close()
      }
    }

    def resourceObservable: Observable[DBResource] =
      Observable.resource(Task(new DBResource(db, None)))(r => Task(r.close()))

    def resourceObservable(iteratorCfHandle: ColumnFamilyHandle): Observable[DBResource] =
      Observable.resource(Task(new DBResource(db, Some(iteratorCfHandle))))(r => Task(r.close()))

    def withResource[A](f: DBResource => A): A = {
      val resource = new DBResource(db)
      try f(resource)
      finally resource.close()
    }

    def withResource[A](iteratorCfHandle: ColumnFamilyHandle)(f: DBResource => A): A = {
      val resource = new DBResource(db, Some(iteratorCfHandle))
      try f(resource)
      finally resource.close()
    }

    private def multiGetOpt[A](
        readOptions: ReadOptions,
        keys: collection.IndexedSeq[Key[Option[A]]],
        keyBufs: collection.IndexedSeq[ByteBuffer],
        valBufs: collection.IndexedSeq[ByteBuffer]
    ): Seq[Option[A]] = {
      val cfhs = keys.map(_.columnFamilyHandle.getOrElse(db.getDefaultColumnFamily)).asJava
      val result = keys.view
        .zip(db.multiGetByteBuffers(readOptions, cfhs, keyBufs.asJava, valBufs.asJava).asScala)
        .map { case (parser, value) =>
          if (value.status.getCode == Status.Code.Ok) {
            val arr = new Array[Byte](value.requiredSize)
            value.value.get(arr)
            Util.releaseTemporaryDirectBuffer(value.value)
            parser.parse(arr)
          } else None
        }
        .toSeq

      keyBufs.foreach(Util.releaseTemporaryDirectBuffer)
      result
    }

    private def multiGet[A](
        readOptions: ReadOptions,
        keys: ArrayBuffer[Key[A]],
        keyBufs: collection.IndexedSeq[ByteBuffer],
        valBufs: collection.IndexedSeq[ByteBuffer]
    ): View[A] = {
      val cfhs = keys.map(_.columnFamilyHandle.getOrElse(db.getDefaultColumnFamily)).asJava
      val result = keys.view
        .zip(db.multiGetByteBuffers(readOptions, cfhs, keyBufs.asJava, valBufs.asJava).asScala)
        .flatMap { case (parser, value) =>
          if (value.status.getCode == Status.Code.Ok) {
            val arr = new Array[Byte](value.requiredSize)
            value.value.get(arr)
            Util.releaseTemporaryDirectBuffer(value.value)
            Some(parser.parse(arr))
          } else None
        }

      keyBufs.foreach(Util.releaseTemporaryDirectBuffer)
      result
    }
  }

  def createBlock(header: BlockHeader, signature: ByteStr, txs: Seq[Transaction]): Either[TxValidationError.GenericError, Block] =
    Validators.validateBlock(Block(header, signature, txs))

  def readTransaction(height: Height)(b: Array[Byte]): (TxMeta, Transaction) = {
    val data = pb.TransactionData.parseFrom(b)
    TxMeta(height, TxMeta.Status.fromProtobuf(data.status), data.spentComplexity) -> toVanillaTransaction(data.transaction)
  }

  def toVanillaTransaction(tx: pb.TransactionData.Transaction): Transaction = tx match {
    case tx: TD.HearthTransaction => PBTransactions.vanilla(tx.value).explicitGet()
    case _                        => throw new IllegalArgumentException("Illegal transaction data")
  }

  def writeTransaction(v: (TxMeta, Transaction)): Array[Byte] = {
    val (m, tx) = v
    val ptx     = TD.HearthTransaction(PBTransactions.protobuf(tx))
    pb.TransactionData(ptx, m.status.protobuf, m.spentComplexity).toByteArray
  }

  def loadTransactions(height: Height, rdb: RDB): Seq[(TxMeta, Transaction)] = {
    val transactions = Seq.newBuilder[(TxMeta, Transaction)]
    rdb.db.iterateOver(KeyTag.NthTransactionInfoAtHeight.prefixBytes ++ height.toByteArray, Some(rdb.txHandle.handle)) { e =>
      transactions += readTransaction(height)(e.getValue)
    }
    transactions.result()
  }

  def loadTxStateSnapshots(height: Height, rdb: RDB): Seq[TransactionStateSnapshot] = {
    val txSnapshots = Seq.newBuilder[TransactionStateSnapshot]
    rdb.db.iterateOver(KeyTag.NthTransactionStateSnapshotAtHeight.prefixBytes ++ height.toByteArray, Some(rdb.txSnapshotHandle.handle)) { e =>
      txSnapshots += TransactionStateSnapshot.parseFrom(e.getValue)
    }
    txSnapshots.result()
  }

  def loadTxStateSnapshotsWithStatus(height: Height, rdb: RDB, transactions: Seq[Transaction]): Seq[(StateSnapshot, TxMeta.Status)] =
    loadTxStateSnapshots(height, rdb).zip(transactions).map { case (s, tx) => PBSnapshots.fromProtobuf(s, tx.id(), height) }

  def loadBlock(height: Height, rdb: RDB): Option[Block] =
    for {
      meta  <- rdb.db.get(Keys.blockMetaAt(height))
      block <- createBlock(PBBlocks.vanilla(meta.getHeader), meta.signature.toByteStr, loadTransactions(height, rdb).map(_._2)).toOption
    } yield block

  def fromHistory[A](resource: DBResource, historyKey: Key[Seq[Height]], valueKey: Height => Key[A]): Option[A] =
    for {
      h <- resource.get(historyKey).headOption
    } yield resource.get(valueKey(h))

  def loadAssetDescription(resource: DBResource, asset: IssuedAsset): Option[AssetDescription] =
    for {
      pbStaticInfo <- resource.get(Keys.assetStaticInfo(asset))
      volumeInfo   <- fromHistory(resource, Keys.assetVolumeDetailsHistory(asset), Keys.assetVolumeDetails(asset))
      minFee       <- fromHistory(resource, Keys.assetMinFeeHistory(asset), Keys.assetMinFee(asset))
    } yield AssetDescription(
      pbStaticInfo.name,
      pbStaticInfo.description,
      pbStaticInfo.decimals,
      volumeInfo,
      pbStaticInfo.sequenceInBlock,
      Height(pbStaticInfo.height),
      minFee
    )

  def loadActiveLeases(rdb: RDB, fromHeight: Height, toHeight: Height): Map[ByteStr, LeaseDetails] = rdb.db.withResource { r =>
    (for {
      id         <- loadLeaseIds(r, fromHeight, toHeight, includeCancelled = false)
      newDetails <- loadLease(r, id)
      if newDetails.isActive
    } yield (id, newDetails)).toMap
  }

  def loadLease(resource: DBResource, id: ByteStr): Option[LeaseDetails] =
    fromHistory(resource, Keys.leaseDetailsHistory(id), Keys.leaseDetails(id)).flatten

  def loadLeaseIds(resource: DBResource, fromHeight: Height, toHeight: Height, includeCancelled: Boolean): Set[ByteStr] = {
    val leaseIds = mutable.Set.empty[ByteStr]
    val iterator = resource.fullIterator

    @inline
    def keyInRange(): Boolean = {
      val actualKey = iterator.key()
      actualKey.startsWith(KeyTag.LeaseDetails.prefixBytes) && Height(Ints.fromByteArray(actualKey.slice(2, 6))) <= toHeight
    }

    iterator.seek(KeyTag.LeaseDetails.prefixBytes ++ fromHeight.toByteArray)
    while (iterator.isValid && keyInRange()) {
      val leaseId = ByteStr(iterator.key().drop(6))
      if (includeCancelled || readLeaseDetails(iterator.value()).isActive)
        leaseIds += leaseId
      else
        leaseIds -= leaseId

      iterator.next()
    }

    leaseIds.toSet
  }

  opaque type AddressId = Long

  object AddressId {
    def apply(l: Long): AddressId                 = l
    def raw(x: AddressId): Long                   = x
    def fromByteArray(bs: Array[Byte]): AddressId = Longs.fromByteArray(bs)

    extension (x: AddressId) {
      def toByteArray: Array[Byte] = Longs.toByteArray(x)
      def toLong: Long             = x
    }
  }

  implicit class LongExt(val l: Long) extends AnyVal {
    def toByteArray: Array[Byte] = Longs.toByteArray(l)
  }
}
