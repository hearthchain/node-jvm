package com.wavesplatform.state

import com.google.common.base.CaseFormat
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.state.StateHash.Section
import org.bouncycastle.util.encoders.Hex
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{Json, JsObject}

final case class StateHash(totalHash: ByteStr, sectionHashes: Map[Section, ByteStr]) {
  require(totalHash.arr.length == crypto.DigestLength && sectionHashes.values.forall(_.arr.length == crypto.DigestLength))
}

object StateHash {
  enum Section {
    case WavesBalance, AssetBalance, LeaseBalance, LeaseStatus, NextCommittedGenerators, CommittedGeneratorBalances
  }

  private val converter = CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.LOWER_CAMEL)

  def toJson(sh: StateHash): JsObject = {
    def lowerCamel(sectionId: Section): String = converter.convert(sectionId.toString)
    def toHexString(bs: ByteStr)               = Hex.toHexString(bs.arr)
    Json.obj("stateHash" -> toHexString(sh.totalHash)) ++ Json.obj(
      Section.values
        .map(id => s"${lowerCamel(id)}Hash" -> (toHexString(sh.sectionHashes.getOrElse(id, StateHashBuilder.EmptySectionHash)): JsValueWrapper))*
    )
  }
}
