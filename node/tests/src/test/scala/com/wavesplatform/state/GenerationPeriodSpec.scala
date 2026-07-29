package com.wavesplatform.state

import cats.syntax.option.*
import com.wavesplatform.test.*

class GenerationPeriodSpec extends FreeSpec {
  private val defaultGenerationPeriodLength = 3

  "from" - {
    "start is expected" - {
      "another generation period length" in {
        generationPeriodFrom(h = 10, len = 100).start shouldBe Height(1)
      }
    }
  }

  "end" in {
    generationPeriodOf(start = 7, len = 3).end shouldBe Height(9)
    generationPeriodOf(start = 11, len = 3).end shouldBe Height(13)
  }

  "next" in forAll(
    Table(
      ("title", "start", "expectedStart"),
      ("Zero period", 7, 10),
      ("First period", 11, 14)
    )
  ) { (_, start, expectedStart) =>
    generationPeriodOf(start).next shouldBe generationPeriodOf(expectedStart)
  }

  "prev" in forAll(
    Table(
      ("title", "start", "expectedStart"),
      ("First period", 4, 1.some),
      ("Second period", 14, 11.some)
    )
  ) { (_, start, expectedStart) =>
    generationPeriodOf(start).prev.map(_.start) shouldBe expectedStart
  }

  "enclosedPeriods" in forAll(
    Table(
      ("title", "start", "end", "expected"),
      (
        "First two",
        1,
        5,
        (generationPeriodN(0), generationPeriodN(1)).some
      ),
      (
        "end < start",
        5,
        1,
        None
      ),
      (
        "3rd thru 5th",
        7,
        14,
        (generationPeriodN(2), generationPeriodN(4)).some
      )
    )
  ) { (_, start, end, expected) =>
    enclosedPeriods(start, end) shouldBe expected
  }

  private def generationPeriodFrom(h: Int, len: Int) =
    GenerationPeriod.from(Height(h), len)

  private def generationPeriodOf(start: Int, len: Int = defaultGenerationPeriodLength) =
    GenerationPeriod(Height(start), len)

  private def generationPeriodN(n: Int, len: Int = defaultGenerationPeriodLength) =
    generationPeriodOf(n * len + 1, len)

  private def enclosedPeriods(
      start: Int,
      end: Int,
      len: Int = defaultGenerationPeriodLength
  ): Option[(start: GenerationPeriod, end: GenerationPeriod)] =
    GenerationPeriod.enclosedPeriods(len, Height(start), Height(end))
}
