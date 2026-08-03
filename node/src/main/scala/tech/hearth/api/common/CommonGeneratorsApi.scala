package tech.hearth.api.common

import com.google.common.primitives.Ints
import tech.hearth.account.Address
import tech.hearth.api.common.CommonGeneratorsApi.GeneratorEntry
import tech.hearth.crypto.bls.BlsPublicKey
import tech.hearth.database.{AddressId, DBExt, Keys, RDB}
import tech.hearth.state.{Blockchain, ConflictGenerators, GeneratorIndex, Height, NG, StateSnapshot, TransactionId}
import tech.hearth.transaction.CommitToGenerationTransaction
import tech.hearth.utils.ScorexLogging

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

trait CommonGeneratorsApi {
  def generators(at: Height): Seq[GeneratorEntry]
}

object CommonGeneratorsApi {
  def apply(rdb: RDB, blockchain: Blockchain & NG): CommonGeneratorsApi = new CommonGeneratorsApi with ScorexLogging {
    private val approxGenerators = blockchain.settings.functionalitySettings.maxValidEndorsers // Rough buffer size

    override def generators(at: Height): Seq[GeneratorEntry] = blockchain.generationPeriodOf(at).fold(Nil) { period =>
      val (addresses, blsPks, txIds, balances, conflict) = rdb.db.readOnly { ro =>
        val committedKey       = Keys.committedGenerators(period, at)
        val committedKeyPrefix = committedKey.keyBytes.dropRight(Ints.BYTES) // Drop height

        val addressIds = new mutable.ArrayBuffer[AddressId](approxGenerators)
        val blsPks     = new mutable.ArrayBuffer[BlsPublicKey](approxGenerators)
        val txnIds     = new mutable.ArrayBuffer[Option[TransactionId]](approxGenerators)
        // Commitment transactions are read per commitment height alongside the generators committed at that height:
        // generators committed by the genesis snapshot have no transaction at all, so a flat zip of the two would
        // misalign them (and previously dropped the whole period)
        ro.iterateOver(committedKeyPrefix) { dbEntry =>
          val commitmentHeight = Height(Ints.fromByteArray(dbEntry.getKey.takeRight(Ints.BYTES)))
          val committedTxnIds  = ro.get(Keys.commitmentTransactions(period, commitmentHeight))
          committedKey
            .parse(dbEntry.getValue)
            .getOrElse(Seq.empty)
            .zipWithIndex
            .foreach { case ((addressId, blsPk, _), idx) =>
              addressIds.append(addressId)
              blsPks.append(blsPk)
              txnIds.append(committedTxnIds.lift(idx))
            }
        }

        // An address is stored as the bytes of its hash, see Keys.idToAddress
        val addresses = ArrayBuffer.from(ro.multiGet(addressIds.map(Keys.idToAddress), tech.hearth.crypto.Address.HASH_LEN))
        val balances: Map[GeneratorIndex, Long] =
          if (at.toInt == blockchain.height) blockchain.currentGeneratorSet.fold(Map.empty)(_.map(x => x.index -> x.balance).toMap)
          else ro.get(Keys.generatorBalances(at, rdb.apiHandle)).fold(Map.empty)(_.toMap)

        val conflictKey       = Keys.conflictGenerators(period, at)
        val conflictKeyPrefix = conflictKey.keyBytes.dropRight(Ints.BYTES) // Drop height

        val conflict = {
          if (at == Height(blockchain.height)) blockchain.conflictGenerators(period)
          else {
            var conflict = ConflictGenerators.empty
            ro.iterateOverWithSeek(conflictKeyPrefix, conflictKeyPrefix) { dbEntry =>
              val hBytes = dbEntry.getKey.takeRight(Ints.BYTES) // Take height
              val h      = Height(Ints.fromByteArray(hBytes))
              if (h > at) false
              else {
                val idxs = conflictKey.parse(dbEntry.getValue)
                conflict = conflict.appendAll(h, idxs*)
                true
              }
            }

            conflict
          }
        }

        (addresses, blsPks, txnIds, balances, conflict)
      }

      if (blockchain.currentGenerationPeriod.exists(_.next == period)) // NG
        blockchain.bestLiquidSnapshot.getOrElse(StateSnapshot.empty).transactions.values.foreach { txnInfo =>
          txnInfo.transaction match {
            case tx: CommitToGenerationTransaction =>
              addresses.append(Some(tx.sender.toAddress))
              blsPks.append(tx.endorserPublicKey)
              txIds.append(Some(TransactionId(tx.id())))

            case _ =>
          }
        }

      if (
        addresses.size == blsPks.size &&
        blsPks.size == txIds.size
      ) {
        addresses
          .lazyZip(txIds)
          .lazyZip(Iterator.from(0).take(addresses.size).map(GeneratorIndex(_)).to(Iterable))
          .collect { case (Some(address), txnId, idx) => // TODO: address=None ?
            val b = balances.get(idx) match {
              case None if at.toInt <= blockchain.height => Some(0L)
              case r                                     => r
            }

            GeneratorEntry(address, b, txnId, conflict.heightOf(idx))
          }
          .toSeq
      } else {
        log.warn(s"Different size: addresses=${addresses.size}, balances=${balances.size}, blsPks=${blsPks.size}")
        Seq.empty
      }
    }
  }

  /** @param balance None if unknown
    */
  /** @param commitTxnId
    *   None for a generator committed by the genesis snapshot, which has no commitment transaction
    */
  case class GeneratorEntry(address: Address, balance: Option[Long], commitTxnId: Option[TransactionId], conflictHeight: Option[Height])
}
