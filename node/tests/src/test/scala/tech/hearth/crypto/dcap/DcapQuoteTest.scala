package tech.hearth.crypto.dcap

import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.FreeSpec

/** Real fixtures (quotev3.hex, quotev4.hex, quotev5.dat) are genuine Intel-generated quotes, one per supported
  * quote version/body shape, vendored unmodified from `automata-network/automata-dcap-attestation` (see
  * `SOURCE.md`) - the same "real fixtures over only synthetic ones" convention `IntelPkiTest` follows. Expected
  * field values below are transcribed from independently parsing the same bytes with a throwaway Python reference
  * script against the documented Intel quote layout, not derived from `DcapQuote` itself, so a bug shared between
  * the transcription and the implementation would still be caught by disagreement with the real byte layout.
  */
class DcapQuoteTest extends FreeSpec {

  private def resourceBytes(name: String): Array[Byte] =
    getClass.getResourceAsStream(s"/dcap/$name").readAllBytes()

  private def hexResource(name: String): Array[Byte] =
    Base16.decode(new String(resourceBytes(name)).trim)

  "against real quote fixtures" - {
    "parses a v3 SGX quote" in {
      val quote = DcapQuote.parse(hexResource("quotev3.hex")).explicitGet()

      quote.header.version shouldBe 3
      quote.header.attestationKeyType shouldBe 2
      quote.header.teeType shouldBe DcapQuote.SgxTeeType
      quote.header.qeSvn shouldBe 9
      quote.header.pceSvn shouldBe 14
      quote.header.qeVendorId shouldBe DcapQuote.IntelQeVendorId

      quote.body shouldBe a[DcapQuote.SgxQuoteBody]
      val body = quote.body.asInstanceOf[DcapQuote.SgxQuoteBody].body
      body.mrEnclave shouldBe hex("a4f45c39dac622cb1dd32ddb35a52ec92db41d0fa88a1c911c49e59c534f61cd")
      body.mrSigner shouldBe hex("8f2dbc0f9c5d3378d596974b2deed1f93223cc49242899f83809bcc92546132c")
      body.isvProdId shouldBe 0
      body.isvSvn shouldBe 0

      quote.signature.isvSignature.arr.take(4) shouldBe hex("0241b027").arr
      quote.signature.attestationPubKey.arr.take(4) shouldBe hex("b1fb4d19").arr
      quote.signature.certData.certKeyType shouldBe 5
      quote.signature.certData.isPckCertChain shouldBe true
      new String(quote.signature.certData.certData.arr).startsWith("-----BEGIN CERTIFICATE-----") shouldBe true
    }

    "parses a v4 TDX TD1.0 quote embedded in a larger buffer" in {
      // quotev4.hex has trailing bytes beyond the quote's own declared signature length - parse must not
      // choke on them, only on trailing bytes *inside* the declared signature frame.
      val quote = DcapQuote.parse(hexResource("quotev4.hex")).explicitGet()

      quote.header.version shouldBe 4
      quote.header.teeType shouldBe DcapQuote.TdxTeeType

      quote.body shouldBe a[DcapQuote.Td10QuoteBody]
      val body = quote.body.asInstanceOf[DcapQuote.Td10QuoteBody].body
      body.teeTcbSvn shouldBe hex("04010700000000000000000000000000")
      body.mrSeam shouldBe hex("ffc97a88587660fb04e1f7c851300c96ae0b5a463ac46d035d16c2d9f36d0ed1d23775bcbd27deb219e3a3cc28023895")
      body.mrTd shouldBe hex("935be7742dd89c6a4df6dba8353d89041ae0f052beef993b1e7f4524d3bc57650df20e5582158352e1240b3f1fed55d8")
      body.rtmr0 shouldBe ByteStr(new Array[Byte](48))

      // The outer cert-data frame (type 6, ECDSA sig aux data) is a wire-format wrapper, not itself the PCK
      // collateral - DcapQuote unwraps it, so the exposed certData is the nested PCK cert chain (type 5).
      quote.signature.certData.certKeyType shouldBe 5
      quote.signature.certData.isPckCertChain shouldBe true
    }

    "parses a v5 TDX TD1.5 quote" in {
      val quote = DcapQuote.parse(resourceBytes("quotev5.dat")).explicitGet()

      quote.header.version shouldBe 5
      quote.header.teeType shouldBe DcapQuote.TdxTeeType

      quote.body shouldBe a[DcapQuote.Td15QuoteBody]
      val body = quote.body.asInstanceOf[DcapQuote.Td15QuoteBody].body
      body.td10.mrTd shouldBe hex("157768a71a6a31f5561978c4cde665809d22976ef5dead2952839b7b3ea23b6c2931c9148fe1d117c99faefac18bb73b")
      body.td10.rtmr0 shouldBe hex("cdf337cef3d59099c809ca0dd38c7ba70d026b8680726e394268ad97c9d620bbd805b3148bf10034a4d205f3fd1d6723")
      body.teeTcbSvn2 shouldBe hex("05010200000000000000000000000000")
      body.mrServiceTd shouldBe hex("383c87d3bbb047b2d171eaca95312ede99f258088dc788f6ae2ccf8b6dd848fe8d47629e08b3f6cbd4a00dd47a5a033d")

      quote.signature.certData.certKeyType shouldBe 5
    }
  }

  "structural rejection" - {
    val v4 = hexResource("quotev4.hex")

    "rejects a buffer shorter than the header" in {
      DcapQuote.parse(v4.take(10)) shouldBe a[Left[?, ?]]
    }

    "rejects a truncated body" in {
      DcapQuote.parse(v4.take(48 + 100)) shouldBe a[Left[?, ?]]
    }

    "rejects a truncated signature" in {
      DcapQuote.parse(v4.take(632 + 4 + 100)) shouldBe a[Left[?, ?]]
    }

    "rejects an unsupported quote version" in {
      val mutated = v4.clone()
      mutated(0) = 2
      mutated(1) = 0
      DcapQuote.parse(mutated) shouldBe a[Left[?, ?]]
    }

    "rejects an unsupported attestation key type" in {
      val mutated = v4.clone()
      mutated(2) = 3
      mutated(3) = 0
      DcapQuote.parse(mutated) shouldBe a[Left[?, ?]]
    }

    "rejects a QE vendor id other than Intel's" in {
      val mutated = v4.clone()
      mutated(12) = (mutated(12) ^ 1).toByte
      DcapQuote.parse(mutated) shouldBe a[Left[?, ?]]
    }

    "rejects a declared signature length shorter than the actual signature" in {
      val signatureLengthOffset = 632
      val declaredLength        = readU32LE(v4, signatureLengthOffset)
      val mutated               = v4.clone()
      writeU32LE(mutated, signatureLengthOffset, declaredLength - 1)
      DcapQuote.parse(mutated) shouldBe a[Left[?, ?]]
    }

    "rejects a declared signature length longer than the actual signature" in {
      val signatureLengthOffset = 632
      val declaredLength        = readU32LE(v4, signatureLengthOffset)
      val mutated               = v4.clone()
      writeU32LE(mutated, signatureLengthOffset, declaredLength + 1)
      DcapQuote.parse(mutated) shouldBe a[Left[?, ?]]
    }
  }

  private def hex(s: String): ByteStr = ByteStr(Base16.decode(s))

  private def readU32LE(bytes: Array[Byte], offset: Int): Int =
    (bytes(offset) & 0xff) | ((bytes(offset + 1) & 0xff) << 8) | ((bytes(offset + 2) & 0xff) << 16) | ((bytes(offset + 3) & 0xff) << 24)

  private def writeU32LE(bytes: Array[Byte], offset: Int, value: Int): Unit = {
    bytes(offset) = (value & 0xff).toByte
    bytes(offset + 1) = ((value >> 8) & 0xff).toByte
    bytes(offset + 2) = ((value >> 16) & 0xff).toByte
    bytes(offset + 3) = ((value >> 24) & 0xff).toByte
  }
}
