package com.wavesplatform.common.utils

import java.util.HexFormat

/** Canonical hex codec: bare lowercase, case-insensitive decode, no 0x prefix. 0x-prefixed hex is Ethereum interop only, see EthEncoding. */
object Base16 extends BaseXXEncDec {
  // 140-byte payloads (the largest encoded values, e.g. attachments) are 280 hex chars.
  override val defaultDecodeLimit: Int = 280

  private val format = HexFormat.of()

  override def encode(array: Array[Byte]): String = format.formatHex(array)

  override def decode(str: String): Array[Byte] = format.parseHex(str)
}
