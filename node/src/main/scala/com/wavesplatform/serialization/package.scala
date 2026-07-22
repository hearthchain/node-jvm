package com.wavesplatform

import com.google.common.primitives.Shorts
import com.wavesplatform.account.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.{KeyLength, SignatureLength}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.{Asset, Proofs}

import java.nio.ByteBuffer

package object serialization {
  implicit class ByteBufferOps(private val buf: ByteBuffer) extends AnyVal {
    def getIssuedAsset: IssuedAsset =
      Asset.IssuedAsset(ByteStr(getByteArray(transaction.AssetIdLength)))

    // More explicit name
    def getByte: Byte =
      buf.get()

    def getByteArray(size: Int): Array[Byte] = {
      require(size < (10 << 20), s"requested array size $size exceeds 10MB limit")
      val result = new Array[Byte](size)
      buf.get(result)
      result
    }

    def getShortArray(size: Int): Array[Short] = {
      val result = new Array[Short](size)
      buf.asShortBuffer().get(result)
      buf.position(buf.position() + Shorts.BYTES * size)
      result
    }

    def getSignature: ByteStr = ByteStr(getByteArray(SignatureLength))

    def getPublicKey: PublicKey = PublicKey(getByteArray(KeyLength))

    def getProofs: Proofs = Proofs.fromBytes(buf.getByteArray(buf.remaining())).explicitGet()

  }
}
