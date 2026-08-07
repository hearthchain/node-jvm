package tech.hearth.events

import com.google.protobuf.ByteString
import tech.hearth.common.state.ByteStr
import tech.hearth.events.StateUpdate.AssetStateUpdate
import tech.hearth.state.{AssetDescription, Height, MinAssetFee}
import tech.hearth.test.*

class AssetStateUpdateSpec extends FreeSpec {
  "AssetStateUpdate" - {
    "round-trips minAssetFee through the repurposed sponsorship wire field" in {
      val description = AssetDescription(
        ByteString.copyFromUtf8("name"),
        ByteString.copyFromUtf8("description"),
        2,
        BigInt(1000),
        1,
        Height(1),
        MinAssetFee.unsafeFrom(12345L)
      )
      val update = AssetStateUpdate(ByteStr.fill(32)(1), before = None, after = Some(description))

      val roundTripped = AssetStateUpdate.fromPB(AssetStateUpdate.toPB(update))

      roundTripped.after.value.minAssetFee.value shouldBe 12345L
    }
  }
}
