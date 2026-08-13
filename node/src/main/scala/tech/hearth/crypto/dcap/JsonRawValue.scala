package tech.hearth.crypto.dcap

import java.nio.charset.StandardCharsets

/** Extracts the exact raw bytes of a top-level `"key":{...}` object value from a JSON document, verbatim - not
  * reserialized - so a signature Intel computed over its own exact encoding (tcbInfo/enclaveIdentity's "signature"
  * field, see DcapCollateral) can still be verified byte-for-byte. Re-parsing and re-serializing through a general
  * JSON library first would only reproduce Intel's exact byte encoding by coincidence - field order, whitespace and
  * number formatting aren't part of the JSON model.
  *
  * Operates on raw bytes throughout rather than a decoded String: JSON's structural characters ('"', '{', '}',
  * '\\') are always single-byte ASCII in UTF-8, even when a string value elsewhere in the document contains
  * multi-byte characters, so byte-level scanning never needs to decode the document to stay correctly aligned.
  */
object JsonRawValue {
  private val Quote      = '"'.toByte
  private val OpenBrace  = '{'.toByte
  private val CloseBrace = '}'.toByte
  private val Backslash  = '\\'.toByte
  private val Colon      = ':'.toByte

  def extractObject(json: Array[Byte], key: String): Either[String, Array[Byte]] = {
    val keyBytes = ("\"" + key + "\"").getBytes(StandardCharsets.UTF_8)
    indexOf(json, keyBytes) match {
      case -1 => Left(s"key '$key' not found")
      case keyIdx =>
        val colonIdx = indexOfByte(json, Colon, keyIdx + keyBytes.length)
        if (colonIdx == -1) Left(s"malformed JSON: no ':' after '$key'")
        else {
          val start = skipWhitespace(json, colonIdx + 1)
          if (start >= json.length || json(start) != OpenBrace) Left(s"'$key' value is not an object")
          else
            matchingBrace(json, start) match {
              case -1  => Left(s"malformed JSON: unterminated object for '$key'")
              case end => Right(json.slice(start, end + 1))
            }
        }
    }
  }

  private def matchingBrace(json: Array[Byte], openIdx: Int): Int = {
    var i        = openIdx
    var depth    = 0
    var inString = false
    var escaped  = false
    while (i < json.length) {
      val b = json(i)
      if (inString) {
        if (escaped) escaped = false
        else if (b == Backslash) escaped = true
        else if (b == Quote) inString = false
      } else if (b == Quote) inString = true
      else if (b == OpenBrace) depth += 1
      else if (b == CloseBrace) {
        depth -= 1
        if (depth == 0) return i
      }
      i += 1
    }
    -1
  }

  private def skipWhitespace(json: Array[Byte], from: Int): Int = {
    var i = from
    while (i < json.length && isWhitespace(json(i))) i += 1
    i
  }

  private def isWhitespace(b: Byte): Boolean = b == ' ' || b == '\t' || b == '\n' || b == '\r'

  private def indexOfByte(haystack: Array[Byte], needle: Byte, from: Int): Int = {
    var i = from
    while (i < haystack.length) {
      if (haystack(i) == needle) return i
      i += 1
    }
    -1
  }

  private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int = {
    val limit = haystack.length - needle.length
    var i     = 0
    while (i <= limit) {
      var j = 0
      while (j < needle.length && haystack(i + j) == needle(j)) j += 1
      if (j == needle.length) return i
      i += 1
    }
    -1
  }
}
