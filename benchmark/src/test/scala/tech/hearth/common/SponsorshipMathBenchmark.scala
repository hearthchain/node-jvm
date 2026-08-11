package tech.hearth.common

import java.util.concurrent.TimeUnit

import tech.hearth.state.diffs.FeeValidation
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Threads(4)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
class SponsorshipMathBenchmark {
  @Benchmark
  def bigDecimal_test(bh: Blackhole): Unit = {
    def toHearth(assetFee: Long, sponsorship: Long): Long = {
      val hearth = (BigDecimal(assetFee) * BigDecimal(FeeValidation.FeeUnit)) / BigDecimal(sponsorship)
      if (hearth > Long.MaxValue) {
        throw new java.lang.ArithmeticException("Overflow")
      }
      hearth.toLong
    }

    bh.consume(toHearth(100000, 100000000))
  }

  @Benchmark
  def bigInt_test(bh: Blackhole): Unit = {
    def toHearth(assetFee: Long, sponsorship: Long): Long = {
      val hearth = BigInt(assetFee) * FeeValidation.FeeUnit / sponsorship
      hearth.bigInteger.longValueExact()
    }

    bh.consume(toHearth(100000, 100000000))
  }
}
