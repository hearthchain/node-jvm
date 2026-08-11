package tech.hearth.state

import tech.hearth.consensus.{FairPoSCalculator, PoSCalculator}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/*
[info] Benchmark                                (configFile)  Mode  Cnt  Score   Error  Units
[info] CalculateDelayBenchmark.calculateDelay1    hearth.conf  avgt   10  1,616 ± 0,244  us/op
[info] CalculateDelayBenchmark.calculateDelay2    hearth.conf  avgt   10  1,671 ± 0,073  us/op
[info] CalculateDelayBenchmark.calculateDelay3    hearth.conf  avgt   10  1,688 ± 0,228  us/op
[info] CalculateDelayBenchmark.calculateDelay4    hearth.conf  avgt   10  1,656 ± 0,020  us/op
 */

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
class CalculateDelayBenchmark {
  @Benchmark
  def calculateDelay1(bh: Blackhole): Unit =
    bh.consume(FairPoSCalculator.V2.calculateDelay(PoSCalculator.hit(Array.empty), 1, 0))

  @Benchmark
  def calculateDelay2(bh: Blackhole): Unit =
    bh.consume(FairPoSCalculator.V2.calculateDelay(PoSCalculator.hit(Array.fill[Byte](26)(127)), Long.MaxValue, Long.MaxValue))

  @Benchmark
  def calculateDelay3(bh: Blackhole): Unit =
    bh.consume(FairPoSCalculator.V2.calculateDelay(PoSCalculator.hit(Array.fill[Byte](26)(-128)), Long.MinValue, Long.MinValue))

  @Benchmark
  def calculateDelay4(bh: Blackhole): Unit =
    bh.consume(FairPoSCalculator.V2.calculateDelay(PoSCalculator.hit(Array.fill[Byte](26)(32)), 100_000_000, 100_000_000))
}
