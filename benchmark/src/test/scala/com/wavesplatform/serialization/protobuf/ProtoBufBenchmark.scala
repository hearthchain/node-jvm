package com.wavesplatform.serialization.protobuf

import java.util.concurrent.TimeUnit

import com.wavesplatform.account.PublicKey
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.protobuf.transaction.PBTransactions
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.transaction.transfer.MassTransferTransaction.Transfer
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import com.wavesplatform.common.utils.EitherExt2.*

//noinspection ScalaStyle
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
class ProtoBufBenchmark {

  @Benchmark
  def serializeMassTransferPB_test(bh: Blackhole): Unit = {
    val vanillaTx = {
      val transfers = MassTransferTransaction
        .parseTransfersList(
          List(Transfer("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", 100000000L), Transfer("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", 200000000L))
        )
        .explicitGet()

      MassTransferTransaction
        .create(
          1.toByte,
          PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet(),
          Waves,
          transfers,
          200000,
          1518091313964L,
          ByteStr.decodeBase16("6d617373706179").get,
          Proofs(Seq(ByteStr.decodeBase16("0c863b41d8c03da0d9c07a645c120477b5d0644fc4ee2862fffbf7462cdda96d9a9693340d6249e8f7322ce39c61b781bcb271e3d5efdae0938083081088b289").get))
        )
        .explicitGet()
    }

    val tx = PBTransactions.protobuf(vanillaTx)
    bh.consume(tx.toByteArray)
  }

  @Benchmark
  def serializeMassTransferVanilla_test(bh: Blackhole): Unit = {
    val vanillaTx = {
      val transfers = MassTransferTransaction
        .parseTransfersList(
          List(Transfer("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", 100000000L), Transfer("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", 200000000L))
        )
        .explicitGet()

      MassTransferTransaction
        .create(
          1.toByte,
          PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet(),
          Waves,
          transfers,
          200000,
          1518091313964L,
          ByteStr.decodeBase16("6d617373706179").get,
          Proofs(Seq(ByteStr.decodeBase16("0c863b41d8c03da0d9c07a645c120477b5d0644fc4ee2862fffbf7462cdda96d9a9693340d6249e8f7322ce39c61b781bcb271e3d5efdae0938083081088b289").get))
        )
        .explicitGet()
    }

    bh.consume(vanillaTx.bytes())
  }
}
