package tech.hearth.state

import com.google.common.primitives.Shorts
import com.typesafe.config.ConfigFactory
import tech.hearth.database.{
  AddressId,
  BalanceNode,
  CurrentBalance,
  KeyTag,
  Keys,
  RDB,
  readBalanceNode,
  readCurrentBalance,
  writeBalanceNode,
  writeCurrentBalance
}
import tech.hearth.settings.{HearthSettings, loadConfig}
import tech.hearth.state.RocksDBSeekForPrevBenchmark.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.rocksdb.{ReadOptions, WriteBatch, WriteOptions}

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import scala.util.Using

@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 100)
class RocksDBSeekForPrevBenchmark {
  @Benchmark
  def seekForPrev(st: BaseSt, bh: Blackhole): Unit = {
    bh.consume {
      val iter = st.rdb.db.newIterator(st.readOptions)
      iter.seekForPrev(st.balanceNodeKey(Height(Int.MaxValue)))
      if (iter.isValid && iter.key().startsWith(st.balanceNodeKeyPrefix)) {
        readBalanceNode(iter.value()).prevHeight
      }
      iter.close()
    }
  }

  @Benchmark
  def get(st: BaseSt, bh: Blackhole): Unit = {
    bh.consume {
      readCurrentBalance(st.rdb.db.get(st.currentBalanceKey)).prevHeight
    }
  }
}

object RocksDBSeekForPrevBenchmark {

  @State(Scope.Benchmark)
  class BaseSt {
    private val hearthSettings: HearthSettings =
      HearthSettings.fromRootConfig(loadConfig(ConfigFactory.load()))

    val rdb: RDB = {
      val dir = Files.createTempDirectory("state-synthetic").toAbsolutePath.toString
      RDB.open(hearthSettings.dbSettings.copy(directory = dir))
    }

    val addressId: AddressId = AddressId(1L)

    val currentBalanceKey: Array[Byte]        = Keys.hearthBalance(addressId).keyBytes
    val balanceNodeKey: Height => Array[Byte] = h => Keys.hearthBalanceAt(addressId, h).keyBytes
    val balanceNodeKeyPrefix: Array[Byte] =
      Shorts.toByteArray(KeyTag.HearthBalanceHistory.ordinal.toShort) ++ addressId.toByteArray

    val readOptions: ReadOptions = new ReadOptions()

    Using.Manager { use =>
      val wb = use(new WriteBatch())
      wb.put(currentBalanceKey, writeCurrentBalance(CurrentBalance(100000000L, Height(10000), Height(9999))))
      (1 to 1000).foreach { h =>
        wb.put(balanceNodeKey(Height(h)), writeBalanceNode(BalanceNode(100000000L, Height(h - 1))))
      }
      rdb.db.write(use(new WriteOptions()), wb)
    }

    @TearDown
    def close(): Unit = {
      readOptions.close()
      rdb.close()
    }
  }
}
