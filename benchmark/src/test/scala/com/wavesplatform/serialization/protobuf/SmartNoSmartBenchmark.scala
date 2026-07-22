package com.wavesplatform.serialization.protobuf

import java.util.concurrent.TimeUnit

import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.serialization.protobuf.SmartNoSmartBenchmark.ExchangeTransactionSt
import com.wavesplatform.transaction.assets.exchange.*
import com.wavesplatform.transaction.{Proofs, TxExchangeAmount, TxExchangePrice, TxMatcherFee, TxOrderPrice, TxPositiveAmount, TxVersion}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

//noinspection ScalaStyle
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
class SmartNoSmartBenchmark {
  @Benchmark
  def smartExchangeTX_test(st: ExchangeTransactionSt, bh: Blackhole): Unit = {
    import st.*
    val exchangeTransaction = ExchangeTransaction.create(TxVersion.V2, buy, sell, 2, 5000000000L, 1, 1, 1, 1526992336241L, proofs)
    bh.consume(exchangeTransaction.explicitGet())
  }

  @Benchmark
  def unsafeExchangeTX_test(st: ExchangeTransactionSt, bh: Blackhole): Unit = {
    import st.*
    val exchangeTransaction = ExchangeTransaction(
      TxVersion.V2,
      buy,
      sell,
      TxExchangeAmount.unsafeFrom(2),
      TxExchangePrice.unsafeFrom(5000000000L),
      1,
      1,
      TxPositiveAmount.unsafeFrom(1),
      1526992336241L,
      proofs,
      AddressScheme.current.chainId
    )
    bh.consume(exchangeTransaction)
  }
}

object SmartNoSmartBenchmark {
  @State(Scope.Benchmark)
  class ExchangeTransactionSt {
    val buy = Order(
      TxVersion.V2,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase16String("a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e").explicitGet(),
        Proofs(
          Seq(
            ByteStr
              .decodeBase16(
                "01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984"
              )
              .get
          )
        )
      ),
      PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
      AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
      OrderType.BUY,
      TxExchangeAmount.unsafeFrom(2),
      TxOrderPrice.unsafeFrom(6000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(1)
    )

    val sell = Order(
      TxVersion.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase16String("5c845a492f0442dc2436d2fc6ff81135ea2b0303fde95c73a8fcbb8a03104f60").explicitGet(),
        Proofs(
          ByteStr
            .decodeBase16(
              "46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388"
            )
            .get
        )
      ),
      PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
      AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val proofs = Proofs(
      Seq(
        ByteStr
          .decodeBase16(
            "db003c16b5ca17661e989e4c61013a4c20e82d277407acdab261799a54f6e6f3f36ce1cfbe6364ebb0c7076b16a0845644bd352a339334fed738aac0491cf58d"
          )
          .get
      )
    )
  }
}
