package com.wavesplatform.features.api

import com.wavesplatform.state.{GenerationPeriod, Height}
import com.wavesplatform.test.*
import play.api.libs.json.Json

class FinalityStatusSpec extends FreeSpec {
  "FinalityStatus.parse reconstructs a GenerationPeriod's length from the start/end pair the API writes" in {
    val period = GenerationPeriod(Height(1), 1000000)
    period.end shouldBe Height(1000000)

    val json = Json.obj(
      "height"                  -> 1,
      "finalizedHeight"         -> 1,
      "currentGenerationPeriod" -> Json.obj("start" -> period.start, "end" -> period.end),
      "nextGenerationPeriod"    -> Json.obj("start" -> period.next.start, "end" -> period.next.end)
    )

    val parsed = json.as[FinalityStatus](using FinalityStatus.parse(Some(Height(1))))
    parsed.currentGenerationPeriod shouldBe Some(period)
    parsed.nextGenerationPeriod shouldBe Some(period.next)
  }
}
