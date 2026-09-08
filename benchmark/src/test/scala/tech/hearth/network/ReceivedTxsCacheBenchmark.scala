package tech.hearth.network

import com.google.common.cache.CacheBuilder
import com.google.common.primitives.Longs
import tech.hearth.common.utils.Base64
import tech.hearth.network.ReceivedTxsCacheBenchmark.CacheSt
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.time.Duration
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import scala.util.Random

/** The transaction dedup cache of LegacyFrameCodecL1, keyed as it was (a Base64 string of the checksum, looked up and
  * then written) and as it is (the checksum's leading bytes as a long, written only if absent).
  */
//noinspection ScalaStyle
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Threads(4)
@Fork(1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
class ReceivedTxsCacheBenchmark {
  @Benchmark
  def base64StringKey_test(st: CacheSt, bh: Blackhole): Unit = bh.consume(st.byBase64String(st.checksum()))

  @Benchmark
  def longKeyPutIfAbsent_test(st: CacheSt, bh: Blackhole): Unit = bh.consume(st.byLongPutIfAbsent(st.checksum()))

  @Benchmark
  def longKeyGetThenPut_test(st: CacheSt, bh: Blackhole): Unit = bh.consume(st.byLongGetThenPut(st.checksum()))
}

object ReceivedTxsCacheBenchmark {
  @State(Scope.Benchmark)
  class CacheSt {
    private val checksums: Array[Array[Byte]] = Array.fill(1000) {
      val checksum = new Array[Byte](32)
      Random.nextBytes(checksum)
      checksum
    }

    private val dummy       = new Object
    private val stringKeyed = CacheBuilder.newBuilder().expireAfterWrite(Duration.ofMinutes(3)).build[String, Object]()
    private val longKeyed   = CacheBuilder.newBuilder().expireAfterWrite(Duration.ofMinutes(3)).build[java.lang.Long, java.lang.Boolean]()

    checksums.foreach { checksum =>
      stringKeyed.put(Base64.encode(checksum), dummy)
      longKeyed.put(Long.box(Longs.fromByteArray(checksum)), java.lang.Boolean.TRUE)
    }

    def checksum(): Array[Byte] = checksums(ThreadLocalRandom.current().nextInt(checksums.length))

    def byBase64String(checksum: Array[Byte]): Boolean = {
      val key = Base64.encode(checksum)
      if (stringKeyed.getIfPresent(key) == null) {
        stringKeyed.put(key, dummy)
        true
      } else false
    }

    def byLongPutIfAbsent(checksum: Array[Byte]): Boolean =
      longKeyed.asMap().putIfAbsent(Long.box(Longs.fromByteArray(checksum)), java.lang.Boolean.TRUE) == null

    def byLongGetThenPut(checksum: Array[Byte]): Boolean = {
      val key = Long.box(Longs.fromByteArray(checksum))
      if (longKeyed.getIfPresent(key) == null) {
        longKeyed.put(key, java.lang.Boolean.TRUE)
        true
      } else false
    }
  }
}
