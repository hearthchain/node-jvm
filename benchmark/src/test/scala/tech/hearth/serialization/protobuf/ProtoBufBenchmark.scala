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
      val sender    = PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet()
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
          ByteStr.decodeBase16("6d617373706179").get,
          Proofs(
            Seq(
              ByteStr
                .decodeBase16(
                  "0c863b41d8c03da0d9c07a645c120477b5d0644fc4ee2862fffbf7462cdda96d9a9693340d6249e8f7322ce39c61b781bcb271e3d5efdae0938083081088b289"
                )
                .get
            )
          )
        )
        .explicitGet()
    }

    val tx = PBTransactions.protobuf(vanillaTx)
    bh.consume(tx.toByteArray)
  }

  @Benchmark
  def serializeMassTransferVanilla_test(bh: Blackhole): Unit = {
    val vanillaTx = {
      val sender    = PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet()
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
          ByteStr.decodeBase16("6d617373706179").get,
          Proofs(
            Seq(
              ByteStr
                .decodeBase16(
                  "0c863b41d8c03da0d9c07a645c120477b5d0644fc4ee2862fffbf7462cdda96d9a9693340d6249e8f7322ce39c61b781bcb271e3d5efdae0938083081088b289"
                )
                .get
            )
          )
        )
        .explicitGet()
    }

    bh.consume(vanillaTx.bytes())
  }
}
