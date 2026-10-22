package tech.hearth.crypto.dcap

import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.dcap.TdxTcbInfo.{TcbLevel, TcbStatus}
import tech.hearth.test.FreeSpec

/** Evaluated against real chain data: the QE report of testnet's StartBoost quote and the QE Identity testnet held
  * (src/test/resources/dcap, see SOURCE.md there).
  */
class QeIdentityTest extends FreeSpec {
  private def resourceBytes(name: String): Array[Byte] =
    getClass.getResourceAsStream(s"/dcap/$name").readAllBytes()

  private val qeReport =
    DcapQuote.parse(Base16.decode(new String(resourceBytes("testnet-startboost-51018.hex")).trim)).explicitGet().signature.qeReportBody
  private val qeIdentity = QeIdentity.parse(resourceBytes("testnet-qe-identity.json")).explicitGet()

  "evaluate" - {
    "rates testnet's Quoting Enclave UpToDate" in {
      qeIdentity.evaluate(qeReport) shouldBe Right(TcbLevel(TcbStatus.UpToDate, Seq.empty))
    }

    "rates an older Quoting Enclave by the first level at or below its ISVSVN" in {
      val levels = qeIdentity.copy(tcbLevels =
        Seq(QeIdentity.Level(8, TcbLevel(TcbStatus.UpToDate, Seq.empty)), QeIdentity.Level(4, TcbLevel(TcbStatus.OutOfDate, Seq("INTEL-SA-00615"))))
      )
      levels.evaluate(qeReport) shouldBe Right(TcbLevel(TcbStatus.OutOfDate, Seq("INTEL-SA-00615")))
    }

    "rejects a Quoting Enclave below every level" in {
      qeIdentity.evaluate(qeReport.copy(isvSvn = 3)) shouldBe Left("QE ISVSVN 3 is below every TCB level")
    }

    "rejects a Quoting Enclave not signed by Intel" in {
      qeIdentity.evaluate(qeReport.copy(mrSigner = ByteStr(Array.fill[Byte](32)(1)))) shouldBe a[Left[?, ?]]
    }

    "rejects a different enclave product" in {
      qeIdentity.evaluate(qeReport.copy(isvProdId = qeReport.isvProdId + 1)) shouldBe a[Left[?, ?]]
    }

    "compares MISCSELECT and attributes under their masks" in {
      // Attribute bit 0x04 of byte 0 is masked out, so setting it changes nothing; bit 0x01 is not
      val maskedBit   = qeReport.sgxAttributes.arr.updated(0, (qeReport.sgxAttributes.arr(0) | 0x04).toByte)
      val unmaskedBit = qeReport.sgxAttributes.arr.updated(0, (qeReport.sgxAttributes.arr(0) ^ 0x01).toByte)
      qeIdentity.evaluate(qeReport.copy(sgxAttributes = ByteStr(maskedBit))).map(_.status) shouldBe Right(TcbStatus.UpToDate)
      qeIdentity.evaluate(qeReport.copy(sgxAttributes = ByteStr(unmaskedBit))) shouldBe a[Left[?, ?]]
      qeIdentity.evaluate(qeReport.copy(miscSelect = 1)) shouldBe a[Left[?, ?]]
    }
  }

  "parse" - {
    "rejects the identity of an SGX Quoting Enclave" in {
      val sgxQe = new String(resourceBytes("testnet-qe-identity.json")).replace("\"TD_QE\"", "\"QE\"").getBytes
      QeIdentity.parse(sgxQe) shouldBe Left("Not a TD Quoting Enclave Identity V2: id QE, version 2")
    }

    "reads the validity end" in {
      qeIdentity.nextUpdate shouldBe java.time.Instant.parse("2026-11-13T04:33:08Z")
    }
  }
}
