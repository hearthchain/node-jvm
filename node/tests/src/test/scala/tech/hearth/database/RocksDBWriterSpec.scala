package tech.hearth.database

import com.google.common.primitives.{Ints, Longs, Shorts}
import tech.hearth.TestValues
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.database.RocksDBWriter.{merge3, slice}
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.Height as H
import tech.hearth.test.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers
import org.rocksdb.{ReadOptions, RocksIterator}

import scala.util.Using

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

  // The genesis balances have to go through withDomain rather than into the settings: it builds the snapshot from its
  // own `balances` argument, replacing whatever the settings carried. It also funds defaultSigner - which it commits as
  // the generator - unless the test names it, so name it and keep the total under the test's control.
  private val minerBalance = 500.waves
  private val genesisBalances =
    AddrWithBalance(TxHelpers.defaultAddress, minerBalance) +: (1 to 10).map(i => AddrWithBalance(TxHelpers.address(1000 + i), 100.waves))
  private val genesisBalance: Long = minerBalance + 10 * 100.waves

  "wavesAmount includes the genesis snapshot" in withDomain(DomainPresets.NG, genesisBalances) { d =>
    d.blockchain.wavesAmount(1) shouldBe genesisBalance
  }

  "cleanup" - {
    val settings = {
      val s = DomainPresets.RideV6
      s.copy(dbSettings = s.dbSettings.copy(maxRollbackDepth = 4, cleanupInterval = Some(4)))
    }

    val alice        = TxHelpers.signer(1)
    val aliceAddress = alice.toAddress

    val bob        = TxHelpers.signer(2)
    val bobAddress = bob.toAddress

    val carl        = TxHelpers.signer(3)
    val carlAddress = carl.toAddress

    val userAddresses  = Seq(aliceAddress, bobAddress, carlAddress)
    val minerAddresses = Seq(TxHelpers.defaultAddress)
    val allAddresses   = userAddresses ++ minerAddresses

    def transferWavesTx = TxHelpers.massTransfer(
      to = Seq(
        aliceAddress -> 100.waves,
        bobAddress   -> 100.waves
      ),
      fee = 1.waves
    )

    // There is no issue transaction any more, so the asset alice holds has to come from the genesis snapshot -
    // held there by a throwaway account instead of alice herself, so alice's own history still starts clean, only
    // once she receives it below (mirroring what issuing it herself used to do).
    val issuer      = TxHelpers.signer(900)
    val issuedAsset = IssuedAsset(ByteStr.fill(32)(7))
    val assetSettings = GenesisAssetSettings(
      id = issuedAsset.id,
      name = "Genesis",
      decimals = 0,
      quantity = 100,
      minFee = TestValues.fee
    )

    def issueAssetTx = TxHelpers.transfer(from = issuer, to = aliceAddress, asset = issuedAsset, amount = 100)

    def transferAssetTx = TxHelpers.transfer(from = alice, to = carlAddress, asset = issuedAsset, amount = 1)

    val cleanupBalances = Seq(
      AddrWithBalance(TxHelpers.defaultSigner.toAddress),
      AddrWithBalance(issuer.toAddress, 10.waves, assets = Map(issuedAsset -> 100))
    )

    "doesn't delete if disabled" in withDomain(
      settings.copy(dbSettings = settings.dbSettings.copy(cleanupInterval = None)),
      cleanupBalances,
      assets = Seq(assetSettings)
    ) { d =>
      d.appendBlock(transferWavesTx, issueAssetTx, transferAssetTx)
      (3 to 10).foreach(_ => d.appendBlock())
      d.blockchain.height shouldBe 10

      d.rdb.db.get(Keys.lastCleanupHeight) shouldBe H(0)
      withClue("All data exists: ") {
        checkHistoricalDataOnlySinceHeight(d, allAddresses, 1)
      }
    }

    "doesn't delete sole data" in withDomain(settings, cleanupBalances, assets = Seq(assetSettings)) { d =>
      d.appendBlock(transferWavesTx, issueAssetTx, transferAssetTx) // Last user data
      d.blockchain.height shouldBe 2

      (3 to 11).foreach(_ => d.appendBlock())
      d.blockchain.height shouldBe 11

      d.rdb.db.get(Keys.lastCleanupHeight) shouldBe H(4)
      withClue("No data before: ") {
        checkHistoricalDataOnlySinceHeight(d, userAddresses, 2)
        checkHistoricalDataOnlySinceHeight(d, minerAddresses, 4) // Updated on each height
      }
    }

    "deletes old data and doesn't delete recent data" in withDomain(settings, cleanupBalances, assets = Seq(assetSettings)) { d =>
      d.appendBlock(transferWavesTx, issueAssetTx, transferAssetTx)

      d.appendBlock()

      d.appendBlock(
        transferWavesTx,
        transferAssetTx
      ) // Last user data
      d.blockchain.height shouldBe 4

      (5 to 11).foreach(_ => d.appendBlock())
      d.blockchain.height shouldBe 11

      d.rdb.db.get(Keys.lastCleanupHeight) shouldBe H(4)
      withClue("No data before: ") {
        checkHistoricalDataOnlySinceHeight(d, allAddresses, 4)
      }
    }

    "deletes old data from previous intervals" in withDomain(settings, cleanupBalances, assets = Seq(assetSettings)) { d =>
      (2 to 3).foreach(_ => d.appendBlock())

      d.appendBlock(
        transferWavesTx,
        issueAssetTx,
        transferAssetTx
      )
      d.blockchain.height shouldBe 4

      d.appendBlock()

      d.appendBlock(
        transferWavesTx,
        transferAssetTx
      ) // Last user data
      d.blockchain.height shouldBe 6

      (7 to 15).foreach(_ => d.appendBlock())
      d.blockchain.height shouldBe 15

      d.rdb.db.get(Keys.lastCleanupHeight) shouldBe H(8)
      withClue("No data before: ") {
        checkHistoricalDataOnlySinceHeight(d, userAddresses, 6)
        checkHistoricalDataOnlySinceHeight(d, minerAddresses, 8) // Updated on each height
      }
    }

    "doesn't affect other sequences" in {
      def appendBlocks(d: Domain): Unit = {
        (2 to 3).foreach(_ => d.appendBlock())
        d.appendBlock(transferWavesTx, issueAssetTx, transferAssetTx)

        d.appendBlock()
        d.appendBlock(transferWavesTx, transferAssetTx)

        (7 to 14).foreach(_ => d.appendBlock())
      }

      var nonHistoricalKeysWithoutCleanup: CollectedKeys = Vector.empty
      withDomain(
        settings.copy(dbSettings = settings.dbSettings.copy(cleanupInterval = None)),
        cleanupBalances,
        assets = Seq(assetSettings)
      ) { d =>
        appendBlocks(d)
        nonHistoricalKeysWithoutCleanup = collectNonHistoricalKeys(d)
      }

      withDomain(settings, cleanupBalances, assets = Seq(assetSettings)) { d =>
        appendBlocks(d)
        val nonHistoricalKeys = collectNonHistoricalKeys(d)
        nonHistoricalKeys should contain theSameElementsInOrderAs nonHistoricalKeysWithoutCleanup
      }
    }

    "balanceAtHeight returns correct values" in {
      val richAccount = TxHelpers.signer(1001)
      val account1    = TxHelpers.signer(1002)
      val account2    = TxHelpers.signer(1003)

      // There is no issue transaction any more, so the asset richAccount holds has to come from the genesis
      // snapshot instead - a plain empty block takes its place to keep the height numbering below unchanged.
      val issuedAsset = IssuedAsset(ByteStr.fill(32)(9))
      val assetSettings = GenesisAssetSettings(
        id = issuedAsset.id,
        name = "IA01",
        decimals = 2,
        quantity = 10000,
        minFee = TestValues.fee
      )

      withDomain(
        DomainPresets.TransactionStateSnapshot,
        Seq(AddrWithBalance(richAccount.toAddress, 10_000.waves, assets = Map(issuedAsset -> 10000))),
        assets = Seq(assetSettings)
      ) { d =>
        d.appendBlock()
        (1 to 3).foreach(_ => d.appendBlock())
        d.blockchain.height shouldBe 5

        d.appendBlock(TxHelpers.transfer(richAccount, account1.toAddress, 10.waves))
        d.appendBlock(TxHelpers.transfer(richAccount, account1.toAddress, 100, asset = issuedAsset))
        d.appendBlock()
        d.appendBlock(TxHelpers.transfer(richAccount, account1.toAddress, 1.waves))
        d.appendBlock(TxHelpers.transfer(richAccount, account1.toAddress, 500, asset = issuedAsset))
        d.blockchain.height shouldBe 10

        d.blockchain.balanceAtHeight(account1.toAddress, 10) shouldBe Some(9 -> 11.waves)
        d.blockchain.balanceAtHeight(account1.toAddress, 9) shouldBe Some(9 -> 11.waves)
        d.blockchain.balanceAtHeight(account1.toAddress, 8) shouldBe Some(6 -> 10.waves)
        d.blockchain.balanceAtHeight(account1.toAddress, 6) shouldBe Some(6 -> 10.waves)
        d.blockchain.balanceAtHeight(account1.toAddress, 5) shouldBe None

        d.blockchain.balanceAtHeight(account1.toAddress, 10, issuedAsset) shouldBe Some(10 -> 600)
        d.blockchain.balanceAtHeight(account1.toAddress, 9, issuedAsset) shouldBe Some(7 -> 100)
        d.blockchain.balanceAtHeight(account1.toAddress, 8, issuedAsset) shouldBe Some(7 -> 100)
        d.blockchain.balanceAtHeight(account1.toAddress, 6, issuedAsset) shouldBe None

        d.appendBlock(TxHelpers.transfer(richAccount, account2.toAddress, 20.waves))
        d.appendBlock(TxHelpers.transfer(richAccount, account2.toAddress, 700, issuedAsset))

        d.blockchain.balanceAtHeight(account2.toAddress, 12) shouldBe Some(11 -> 20.waves)
        d.blockchain.balanceAtHeight(account2.toAddress, 11) shouldBe Some(11 -> 20.waves)
        d.blockchain.balanceAtHeight(account2.toAddress, 10) shouldBe None

        d.blockchain.balanceAtHeight(account2.toAddress, 12, issuedAsset) shouldBe Some(12 -> 700)
        d.blockchain.balanceAtHeight(account2.toAddress, 11, issuedAsset) shouldBe None
      }
    }
  }

  // There is no data transaction any more, so KeyTag.ChangedDataKeys/DataHistory are never written and are left out
  // here - nothing would ever be found under them. KeyTag.ChangedAddresses is left out too: it used to be cleaned up
  // as a side effect of the account-data cleanup that went with data transactions, but the balance cleanup that
  // remains (batchCleanupWavesBalances/batchCleanupAssetBalances) never touches it, so it is kept forever now - an
  // address active since height 1, like the miner, would always have an entry there below any sinceHeight.
  private val HistoricalKeyTags = Seq(
    KeyTag.ChangedAssetBalances,
    KeyTag.ChangedWavesBalances,
    KeyTag.WavesBalanceHistory,
    KeyTag.AssetBalanceHistory
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
      case KeyTag.ChangedAssetBalances | KeyTag.ChangedWavesBalances =>
        (
          bytes.getKey.drop(Shorts.BYTES),
          readAddressIds(bytes.getValue)
        )

      case KeyTag.WavesBalanceHistory | KeyTag.AssetBalanceHistory =>
        (
          bytes.getKey.takeRight(Ints.BYTES),
          Seq(AddressId.fromByteArray(bytes.getKey.dropRight(Ints.BYTES).takeRight(Longs.BYTES)))
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
