package com.wavesplatform.database

import com.google.common.primitives.{Ints, Longs, Shorts}
import com.wavesplatform.TestValues
import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.database.RocksDBWriter.{merge3, slice}
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.history.Domain
import com.wavesplatform.settings.{GenesisBalanceSettings, WavesSettings}
import com.wavesplatform.state.Height as H
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.{TxHelpers, TxPositiveAmount}
import org.rocksdb.{ReadOptions, RocksIterator}

import scala.util.{Random, Using}

class RocksDBWriterSpec extends FreeSpec with WithDomain {
  "Slice" - {
    "drops tail" in {
      slice(H.seq(10, 7, 4), H(7), H(10)) shouldEqual H.seq(10, 7)
    }
    "drops head" in {
      slice(H.seq(10, 7, 4), H(4), H(8)) shouldEqual H.seq(7, 4)
    }
    "includes Genesis" in {
      slice(H.seq(10, 7), H(5), H(11)) shouldEqual H.seq(10, 7, 1)
    }
    "with zero" in {
      slice(H.seq(10, 7, 0), H(5), H(11)) shouldEqual H.seq(10, 7, 0)
    }
  }
  "Merge" - {
    "correctly joins height ranges" in {
      merge3(H.seq(15, 12, 3), H.seq(12, 5), H.seq(3, 1)) shouldEqual Seq(H.tuple(15, 12, 3), H.tuple(12, 12, 3), H.tuple(3, 5, 3), H.tuple(3, 5, 1))
      merge3(H.seq(12, 5), H.seq(15, 12, 3), H.seq(9, 6)) shouldEqual Seq(H.tuple(12, 15, 9), H.tuple(12, 12, 9), H.tuple(5, 3, 9), H.tuple(5, 3, 6))
      merge3(H.seq(8, 4), H.seq(8, 4), H.seq(1)) shouldEqual Seq(H.tuple(8, 8, 1), H.tuple(4, 4, 1))
    }

    "zeroes" in {
      merge3(H.seq(1), H.seq(0), H.seq(0)) shouldEqual Seq(H.tuple(1, 0, 0))
      merge3(H.seq(0), H.seq(0), H.seq(0)) shouldEqual Seq(H.tuple(0, 0, 0))
      merge3(H.seq(0), H.seq(2), H.seq(0)) shouldEqual Seq(H.tuple(0, 2, 0))
      merge3(H.seq(4, 2, 1), H.seq(0), H.seq(0)) shouldEqual Seq(H.tuple(4, 0, 0), H.tuple(2, 0, 0), H.tuple(1, 0, 0))
      merge3(H.seq(4, 2, 1), H.seq(0), H.seq(6, 4, 2)) shouldEqual Seq(H.tuple(4, 0, 6), H.tuple(4, 0, 4), H.tuple(2, 0, 2), H.tuple(1, 0, 2))
    }

    "one sequence longer than others, exhausted sequences keep head steady" in {
      merge3(H.seq(9, 8), H.seq(3), H.seq(2)) shouldBe Seq(H.tuple(9, 3, 2), H.tuple(8, 3, 2))
    }

    "all heads equal but only some have tails" in {
      merge3(H.seq(5, 4), H.seq(5), H.seq(5, 1)) shouldBe Seq(H.tuple(5, 5, 5), H.tuple(4, 5, 1))
    }

    "strictly descending and all tails exhausted at different times" in {
      merge3(H.seq(4, 2, 1), H.seq(6, 3), H.seq(5)) shouldBe Seq(H.tuple(4, 6, 5), H.tuple(4, 3, 5), H.tuple(2, 3, 5), H.tuple(1, 3, 5))
    }
  }

  private val settingsWithGenesis: WavesSettings = DomainPresets.NG.setFeaturesHeight(BlockchainFeatures.BlockReward -> 2)
  private val genesisBalance: Long               = 10 * 100.waves

  "wavesAmount includes the genesis snapshot" in withDomain(
    settingsWithGenesis.copy(blockchainSettings =
      settingsWithGenesis.blockchainSettings.copy(
        genesisSettings = settingsWithGenesis.blockchainSettings.genesisSettings.copy(
          signature = None,
          balances = (1 to 10).map(i => GenesisBalanceSettings(TxHelpers.address(1000 + i).toBech32, 100.waves))
        )
      )
    )
  ) { d =>
    d.blockchain.wavesAmount(1) shouldBe genesisBalance
  }

  private val HistoricalKeyTags = Seq(
    KeyTag.ChangedAssetBalances,
    KeyTag.ChangedWavesBalances,
    KeyTag.WavesBalanceHistory,
    KeyTag.AssetBalanceHistory,
    KeyTag.ChangedDataKeys,
    KeyTag.DataHistory,
    KeyTag.ChangedAddresses
  )

  private type CollectedKeys = Vector[(ByteStr, String)]
  private def collectNonHistoricalKeys(d: Domain): CollectedKeys = {
    var xs: CollectedKeys = Vector.empty
    withGlobalIterator(d.rdb) { iter =>
      iter.seekToFirst()
      while (iter.isValid) {
        val k = iter.key()
        if (
          !(HistoricalKeyTags.exists(kt => k.startsWith(kt.prefixBytes)) || k
            .startsWith(KeyTag.HeightOf.prefixBytes) || k.startsWith(KeyTag.LastCleanupHeight.prefixBytes))
        ) {
          val description = KeyTag.fromOrdinal(Shorts.fromByteArray(k)).toString
          xs = xs.appended(ByteStr(k) -> description)
        }
        iter.next()
      }
    }
    xs
  }

  private def checkHistoricalDataOnlySinceHeight(d: Domain, addresses: Seq[Address], sinceHeight: Int): Unit = {
    val addressIds = addresses.map(getAddressId(d, _))
    HistoricalKeyTags.foreach { keyTag =>
      withClue(s"$keyTag:") {
        d.rdb.db.iterateOver(keyTag) { e =>
          val (affectedHeight, affectedAddressIds) = getHeightAndAddressIds(keyTag, e)
          if (affectedAddressIds.exists(addressIds.contains)) {
            withClue(s"$addresses: ") {
              affectedHeight should be >= sinceHeight
            }
          }
        }
      }
    }
  }

  private def getHeightAndAddressIds(tag: KeyTag, bytes: DBEntry): (Int, Seq[AddressId]) = {
    val (heightBytes, addresses) = tag match {
      case KeyTag.ChangedAddresses | KeyTag.ChangedAssetBalances | KeyTag.ChangedWavesBalances =>
        (
          bytes.getKey.drop(Shorts.BYTES),
          readAddressIds(bytes.getValue)
        )

      case KeyTag.WavesBalanceHistory | KeyTag.AssetBalanceHistory | KeyTag.ChangedDataKeys =>
        (
          bytes.getKey.takeRight(Ints.BYTES),
          Seq(AddressId.fromByteArray(bytes.getKey.dropRight(Ints.BYTES).takeRight(Longs.BYTES)))
        )

      case KeyTag.DataHistory =>
        (
          bytes.getKey.takeRight(Ints.BYTES),
          Seq(AddressId.fromByteArray(bytes.getKey.drop(Shorts.BYTES)))
        )

      case _ => throw new IllegalArgumentException(s"$tag")
    }

    (Ints.fromByteArray(heightBytes), addresses)
  }

  private def getAddressId(d: Domain, address: Address): AddressId =
    d.rdb.db.get(Keys.addressId(address)).getOrElse(throw new RuntimeException(s"Can't find address id for $address"))

  private def withGlobalIterator(rdb: RDB)(f: RocksIterator => Unit): Unit = {
    Using(new ReadOptions().setTotalOrderSeek(true)) { ro =>
      Using(rdb.db.newIterator(ro))(f).get
    }.get
  }
}
