package tech.hearth.consensus

import tech.hearth.test.*

class WorkBoostTest extends FreeSpec {
  "WorkBoost" - {
    "applies no boost when totalWork is zero" in {
      WorkBoost(balance = 1000L, work = 0L, totalWork = 0L) shouldBe 1000L
    }

    "applies no boost when this validator's own work is zero, even if others contributed" in {
      WorkBoost(balance = 1000L, work = 0L, totalWork = 500L) shouldBe 1000L
    }

    "boosts proportionally to this validator's share of the total tracked work" in {
      // Sole contributor of all tracked work: full MaxBoost applies.
      WorkBoost(balance = 1000L, work = 100L, totalWork = 100L) shouldBe 1000L + 1000L * WorkBoost.MaxBoost

      // Half the tracked work: half of MaxBoost applies.
      WorkBoost(balance = 1000L, work = 50L, totalWork = 100L) shouldBe 1000L + 1000L * WorkBoost.MaxBoost / 2
    }

    "truncates rather than rounds, matching Fraction's own convention elsewhere in this codebase" in {
      // work/totalWork = 1/3, MaxBoost=2: extra = 1000 * 2 * 1 / 3 = 666.66..., truncated to 666.
      WorkBoost(balance = 1000L, work = 1L, totalWork = 3L) shouldBe 1666L
    }

    "never exceeds balance * (1 + MaxBoost), the same bound the spec's own formula guarantees" in {
      WorkBoost(balance = 1000L, work = 1L, totalWork = 1L) shouldBe 1000L * (1 + WorkBoost.MaxBoost)
    }
  }
}
