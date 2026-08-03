package tech.hearth.serialization.protobuf

import java.util.concurrent.TimeUnit

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.protobuf.transaction.PBTransactions
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.Proofs
import tech.hearth.transaction.transfer.MassTransferTransaction
import tech.hearth.transaction.transfer.MassTransferTransaction.Transfer
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.hearth.common.utils.EitherExt2.*

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
      val sender    = PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet()
      val recipient = sender.toAddress.toString
      val transfers = MassTransferTransaction
        .parseTransfersList(
          List(Transfer(recipient, 100000000L), Transfer(recipient, 200000000L))
        )
        .explicitGet()

      MassTransferTransaction
        .create(
          sender,
          Waves,
          transfers,
          200000,
          1518091313964L,
          ByteStr.decodeBase58("59QuUcqP6p").get,
          Proofs(Seq(ByteStr.decodeBase58("FXMNu3ecy5zBjn9b69VtpuYRwxjCbxdkZ3xZpLzB8ZeFDvcgTkmEDrD29wtGYRPtyLS3LPYrL2d5UM6TpFBMUGQ").get))
        )
        .explicitGet()
    }

    val tx = PBTransactions.protobuf(vanillaTx)
    bh.consume(tx.toByteArray)
  }

  @Benchmark
  def serializeMassTransferVanilla_test(bh: Blackhole): Unit = {
    val vanillaTx = {
      val sender    = PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet()
      val recipient = sender.toAddress.toString
      val transfers = MassTransferTransaction
        .parseTransfersList(
          List(Transfer(recipient, 100000000L), Transfer(recipient, 200000000L))
        )
        .explicitGet()

      MassTransferTransaction
        .create(
          sender,
          Waves,
          transfers,
          200000,
          1518091313964L,
          ByteStr.decodeBase58("59QuUcqP6p").get,
          Proofs(Seq(ByteStr.decodeBase58("FXMNu3ecy5zBjn9b69VtpuYRwxjCbxdkZ3xZpLzB8ZeFDvcgTkmEDrD29wtGYRPtyLS3LPYrL2d5UM6TpFBMUGQ").get))
        )
        .explicitGet()
    }

    bh.consume(vanillaTx.bytes())
  }
}
