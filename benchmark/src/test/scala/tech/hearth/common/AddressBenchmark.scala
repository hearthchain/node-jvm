package tech.hearth.common

import java.util.concurrent.TimeUnit

import tech.hearth.account.{Address, PublicKey}
import tech.hearth.common.AddressBenchmark.{CachedAddress, PublicKeySt, UncachedAddress}
import tech.hearth.crypto.KeyLength
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import scala.util.Random

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(4)
@Fork(1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
class AddressBenchmark {
  @Benchmark
  def createAddress_test(st: PublicKeySt, bh: Blackhole): Unit = {
    bh.consume(new UncachedAddress(st.publicKey).toAddress)
  }

  @Benchmark
  def readAddress_test(st: PublicKeySt, bh: Blackhole): Unit = {
    val addr = new UncachedAddress(st.publicKey)
    (0 to 1000).foreach(_ => bh.consume(addr.toAddress))
  }

  @Benchmark
  def readCachedAddress_test(st: PublicKeySt, bh: Blackhole): Unit = {
    val addr = new CachedAddress(st.publicKey)
    (0 to 1000).foreach(_ => bh.consume(addr.toAddress))
  }
}

object AddressBenchmark {
  @State(Scope.Benchmark)
  class PublicKeySt {
    val publicKey = new Array[Byte](KeyLength)
    Random.nextBytes(publicKey)
  }

  class UncachedAddress(publicKey: Array[Byte]) {
    def toAddress = Address.fromPublicKey(PublicKey(publicKey))
  }

  class CachedAddress(publicKey: Array[Byte]) {
    lazy val toAddress = Address.fromPublicKey(PublicKey(publicKey))
  }
}
