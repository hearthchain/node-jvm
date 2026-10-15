package tech.hearth.state.diffs

import tech.hearth.state.diffs.BlockDiffer.Fraction
import tech.hearth.test.*

class FractionTest extends FreeSpec {
  "Fraction.floorOf" - {
    "gives a value below the divider its share" in {
      Fraction(6, 10).floorOf(3L) shouldBe 1L
    }

    "does not overflow where the value times the dividend exceeds a Long" in {
      Fraction(6, 10).floorOf(Long.MaxValue) shouldBe (BigInt(Long.MaxValue) * 6 / 10).toLong
    }
  }
}
