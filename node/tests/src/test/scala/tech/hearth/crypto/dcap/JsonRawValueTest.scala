package tech.hearth.crypto.dcap

import tech.hearth.test.FreeSpec

import java.nio.charset.StandardCharsets.UTF_8

class JsonRawValueTest extends FreeSpec {
  private def bytes(s: String): Array[Byte] = s.getBytes(UTF_8)
  private def str(b: Array[Byte]): String   = new String(b, UTF_8)

  "extractObject" - {
    "extracts a simple nested object verbatim" in {
      val json = bytes("""{"tcbInfo":{"fmspc":"00A067110000"},"signature":"aa"}""")
      JsonRawValue.extractObject(json, "tcbInfo").map(str) shouldBe Right("""{"fmspc":"00A067110000"}""")
    }

    "extracts an object containing nested braces at multiple depths" in {
      val json = bytes("""{"tcbInfo":{"a":{"b":{"c":1}},"d":[1,2]},"signature":"aa"}""")
      JsonRawValue.extractObject(json, "tcbInfo").map(str) shouldBe Right("""{"a":{"b":{"c":1}},"d":[1,2]}""")
    }

    "is not confused by braces inside string values" in {
      val json = bytes("""{"tcbInfo":{"note":"contains } and { chars","fmspc":"00A067110000"}}""")
      JsonRawValue.extractObject(json, "tcbInfo").map(str) shouldBe
        Right("""{"note":"contains } and { chars","fmspc":"00A067110000"}""")
    }

    "is not confused by an escaped quote just before a brace" in {
      val json = bytes("""{"tcbInfo":{"note":"ends with \\\""},"signature":"aa"}""")
      JsonRawValue.extractObject(json, "tcbInfo").map(str) shouldBe Right("""{"note":"ends with \\\""}""")
    }

    "tolerates whitespace between the key and the object" in {
      val json = bytes("""{"tcbInfo"  :   {"fmspc":"00A067110000"}}""")
      JsonRawValue.extractObject(json, "tcbInfo").map(str) shouldBe Right("""{"fmspc":"00A067110000"}""")
    }

    "rejects a missing key" in {
      JsonRawValue.extractObject(bytes("""{"other":{}}"""), "tcbInfo") shouldBe a[Left[?, ?]]
    }

    "rejects a key whose value is not an object" in {
      JsonRawValue.extractObject(bytes("""{"tcbInfo":"not an object"}"""), "tcbInfo") shouldBe a[Left[?, ?]]
    }

    "rejects an unterminated object" in {
      JsonRawValue.extractObject(bytes("""{"tcbInfo":{"a":1"""), "tcbInfo") shouldBe a[Left[?, ?]]
    }
  }
}
