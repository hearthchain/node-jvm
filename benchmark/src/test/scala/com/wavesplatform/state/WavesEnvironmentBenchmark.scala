package com.wavesplatform.state

import cats.Id
import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.{AddressOrAlias, AddressScheme, Alias}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base16
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.database.{RDB, RocksDBWriter}
import com.wavesplatform.lang.directives.DirectiveSet
import com.wavesplatform.lang.v1.traits.Environment
import com.wavesplatform.lang.v1.traits.domain.Recipient
import com.wavesplatform.settings.{WavesSettings, loadConfig}
import com.wavesplatform.state.WavesEnvironmentBenchmark.*
import com.wavesplatform.state.bench.DataTestData
import com.wavesplatform.transaction.smart.WavesEnvironment
import monix.eval.Coeval
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import scodec.bits.BitVector

import java.io.File
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import scala.io.Codec
import scala.util.Using

/** Tests over real database. How to test:
  *   1. Download a database 2. Import it:
  *      https://github.com/wavesplatform/Waves/wiki/Export-and-import-of-the-blockchain#import-blocks-from-the-binary-file 3. Run ExtractInfo to
  *      collect queries for tests 4. Make Caches.MaxSize = 1 5. Run this test
  */
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
class WavesEnvironmentBenchmark {

  @Benchmark
  def resolveAddress_test(st: ResolveAddressSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.resolveAlias(st.aliases.random))
  }

  @Benchmark
  def transactionById_test(st: TransactionByIdSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.transactionById(st.allTxs.random))
  }

  @Benchmark
  def transactionHeightById_test(st: TransactionByIdSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.transactionById(st.allTxs.random))
  }

  @Benchmark
  def accountBalanceOf_waves_test(st: AccountBalanceOfWavesSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.accountBalanceOf(Recipient.Address(ByteStr(st.accounts.random)), None))
  }

  @Benchmark
  def accountBalanceOf_asset_test(st: AccountBalanceOfAssetSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.accountBalanceOf(Recipient.Address(ByteStr(st.accounts.random)), Some(st.assets.random)))
  }

  @Benchmark
  def data_test(st: DataSt, bh: Blackhole): Unit = {
    val x = st.data.random
    bh.consume(st.environment.data(Recipient.Address(x.addr), x.key, x.dataType))
  }

  @Benchmark
  def transferTransactionFromProto(st: TransferFromProtoSt, bh: Blackhole): Unit = {
    bh.consume(st.environment.transferTransactionFromProto(st.transferTxBytes))
  }

}

object WavesEnvironmentBenchmark {

  @State(Scope.Benchmark)
  class ResolveAddressSt extends BaseSt {
    val aliases: Vector[String] = load(benchSettings.aliasesFile)(x => Alias.fromString(x).explicitGet().name)
  }

  @State(Scope.Benchmark)
  class TransactionByIdSt extends BaseSt {
    val allTxs: Vector[Array[Byte]] = load(benchSettings.restTxsFile)(x => Base16.tryDecodeWithLimit(x).get)
  }

  @State(Scope.Benchmark)
  class TransactionHeightByIdSt extends TransactionByIdSt

  @State(Scope.Benchmark)
  class AccountBalanceOfWavesSt extends BaseSt {
    val accounts: Vector[Array[Byte]] = load(benchSettings.accountsFile)(x => AddressOrAlias.fromString(x).explicitGet().bytes)
  }

  @State(Scope.Benchmark)
  class AccountBalanceOfAssetSt extends AccountBalanceOfWavesSt {
    val assets: Vector[Array[Byte]] = load(benchSettings.assetsFile)(x => Base16.tryDecodeWithLimit(x).get)
  }

  @State(Scope.Benchmark)
  class DataSt extends BaseSt {
    val data: Vector[DataTestData] = load(benchSettings.dataFile) { line =>
      DataTestData.codec.decode(BitVector.fromBase64(line).get).require.value
    }
  }

  @State(Scope.Benchmark)
  class TransferFromProtoSt extends BaseSt {
    val transferTxBytesBase16: String =
      "2ed29fb960cbcd65c747838d77a27a0cab3e6eb5925b1bbfae10381cac1ade7579b2483eec3de494c5db95dc50f08e400c033f69702dc3ac9f25aba770a2" +
        "844a54ced4bf1b0aa6fdaa24d4893776522df01f403eb8d2b123535066394c27f3b2fcb95caddd3bfec5f1ce1b208d3505b5e38fe18120b8b2a503893718" +
        "06be6b488eee39fa00f024d29677e240feb71b8272efa094f5076e53e2be5383"

    val transferTxBytes: Array[Byte] =
      Base16.decode(transferTxBytesBase16)
  }

  @State(Scope.Benchmark)
  class BaseSt {
    protected val benchSettings: Settings = Settings.fromConfig(ConfigFactory.load())
    private val wavesSettings: WavesSettings = {
      val config = loadConfig(ConfigFactory.parseFile(new File(benchSettings.networkConfigFile)))
      WavesSettings.fromRootConfig(config)
    }

    AddressScheme.current = new AddressScheme {
      override val chainId: Byte = wavesSettings.blockchainSettings.addressSchemeCharacter.toByte
    }

    private val rdb: RDB = {
      val dir = new File(wavesSettings.dbSettings.directory)
      if (!dir.isDirectory) throw new IllegalArgumentException(s"Can't find directory at '${wavesSettings.dbSettings.directory}'")
      RDB.open(wavesSettings.dbSettings)
    }

    val state = RocksDBWriter(rdb, wavesSettings.blockchainSettings, wavesSettings.dbSettings, wavesSettings.enableLightMode)
    val environment: Environment[Id] = {
      WavesEnvironment(
        AddressScheme.current.chainId,
        Coeval.raiseError(new NotImplementedError("`tx` is not implemented")),
        Coeval(state.height),
        state,
        null,
        DirectiveSet.contractDirectiveSet,
        ByteStr.empty
      )
    }

    @TearDown
    def close(): Unit = {
      state.close()
      rdb.close()
    }

    protected def load[T](absolutePath: String)(f: String => T): Vector[T] = {
      Using.resource(scala.io.Source.fromFile(absolutePath)(using Codec.UTF8))(_.getLines().map(f).toVector)
    }
  }

  implicit class VectorOps[T](self: Vector[T]) {
    def random: T = self(ThreadLocalRandom.current().nextInt(self.size))
  }

}
