package tech.hearth.crypto.dcap

import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.dcap.TdxTcbInfo.{TcbLevel, TcbStatus}
import tech.hearth.test.FreeSpec

/** Evaluated against real chain data: testnet's StartBoost quote and the TCB Info testnet held for its platform
  * (src/test/resources/dcap, see SOURCE.md there).
  */
class TdxTcbInfoTest extends FreeSpec {
  private def resourceBytes(name: String): Array[Byte] =
    getClass.getResourceAsStream(s"/dcap/$name").readAllBytes()

  private val quote = DcapQuote.parse(Base16.decode(new String(resourceBytes("testnet-startboost-51018.hex")).trim)).explicitGet()
  private val report = quote.body match {
    case DcapQuote.Td10QuoteBody(body) => body
    case other                         => fail(s"Expected a TD 1.0 quote, got $other")
  }
  private val pck     = IntelPki.pckTcb(quote.signature.certData.certData.arr).explicitGet()
  private val tcbInfo = TdxTcbInfo.parse(resourceBytes("testnet-tcb-info-b0c06f000000.json")).explicitGet()

  "evaluate" - {
    "rates testnet's StartBoost platform UpToDate" in {
      rate(pck, report) shouldBe Right(TcbLevel(TcbStatus.UpToDate, Seq.empty))
    }

    "rates a platform behind the newest SGX components by the first level it meets, with that level's advisories" in {
      val olderCpu = pck.copy(cpuSvn = 3 +: 3 +: pck.cpuSvn.drop(2))
      rate(olderCpu, report) shouldBe Right(
        TcbLevel(TcbStatus.OutOfDate, Seq("INTEL-SA-01192", "INTEL-SA-01245", "INTEL-SA-01312", "INTEL-SA-01313"))
      )
    }

    "rates a platform behind the newest TDX components by the first level it meets" in {
      rate(pck, withTeeTcbSvn(0x0e, 0x01, 0x03)).map(_.status) shouldBe Right(TcbStatus.OutOfDate)
    }

    "rejects a platform below every level" in {
      rate(pck.copy(pceSvn = 4), report) shouldBe Left("Platform TCB is below every TCB level")
    }

    "rates the TDX module by its identity, making a current platform out of date and adding the module's advisories" in {
      rate(pck, withTeeTcbSvn(0x06, 0x01, 0x04)) shouldBe Right(
        TcbLevel(TcbStatus.OutOfDate, Seq("INTEL-SA-01192", "INTEL-SA-01245", "INTEL-SA-01312"))
      )
    }

    "leaves the module's SVN to its identity rather than the platform levels' TDX components" in {
      // 4 is below every platform level's TDX component 0 (5), but TDX_01 rates it
      rate(pck, withTeeTcbSvn(0x04, 0x01, 0x04)).map(_.status) shouldBe Right(TcbStatus.OutOfDate)
    }

    "rejects a TDX module version the TCB Info has no identity for" in {
      rate(pck, withTeeTcbSvn(0x0e, 0x02, 0x04)) shouldBe Left("No TDX module identity TDX_02")
    }

    "rejects a TDX module not signed by the module identity's signer" in {
      val foreignModule = report.copy(mrSignerSeam = ByteStr(Array.fill[Byte](48)(1)))
      rate(pck, foreignModule) shouldBe a[Left[?, ?]]
    }

    "makes a platform that needs configuration OutOfDateConfigurationNeeded when the module is out of date" in {
      val needsConfiguration =
        tcbInfo.copy(tcbLevels = tcbInfo.tcbLevels.map(l => l.copy(level = l.level.copy(status = TcbStatus.ConfigurationNeeded))))
      needsConfiguration.evaluate(pck, quoteOf(withTeeTcbSvn(0x06, 0x01, 0x04)), currentQe).map(_.status) shouldBe Right(
        TcbStatus.OutOfDateConfigurationNeeded
      )
    }

    "makes a current platform out of date when the Quoting Enclave is, adding the QE's advisories" in {
      rate(pck, report, TcbLevel(TcbStatus.OutOfDate, Seq("INTEL-SA-00615"))) shouldBe Right(TcbLevel(TcbStatus.OutOfDate, Seq("INTEL-SA-00615")))
    }

    "revokes the platform when the Quoting Enclave is revoked" in {
      rate(pck, report, TcbLevel(TcbStatus.Revoked, Seq.empty)).map(_.status) shouldBe Right(TcbStatus.Revoked)
    }

    "leaves the platform's status alone for a Quoting Enclave that needs configuration, as QVL does" in {
      rate(pck, report, TcbLevel(TcbStatus.ConfigurationNeeded, Seq.empty)).map(_.status) shouldBe Right(TcbStatus.UpToDate)
    }

    "rejects a TDX module with any SEAM attribute set" in {
      rate(pck, report.copy(seamAttributes = ByteStr(Array[Byte](1, 0, 0, 0, 0, 0, 0, 0)))) shouldBe a[Left[?, ?]]
    }

    "rejects a PCK certificate for another PCE than the TCB Info's" in {
      rate(pck.copy(pceId = ByteStr(Array[Byte](0, 1))), report) shouldBe Left("PCE ID 0001 is not the TCB Info's 0000")
    }

    "rejects an SGX quote" in {
      val sgx = DcapQuote.parse(Base16.decode(new String(resourceBytes("quotev3.hex")).trim)).explicitGet()
      tcbInfo.evaluate(pck, sgx, currentQe) shouldBe Left("TDX TCB Info can't rate an SGX quote")
    }
  }

  "evaluate a TD 1.5 quote" - {
    def td15(launch: DcapQuote.Td10ReportBody, current: DcapQuote.Td10ReportBody): DcapQuote.Quote =
      quote.copy(body = DcapQuote.Td15QuoteBody(DcapQuote.Td15ReportBody(launch, current.teeTcbSvn, ByteStr(new Array[Byte](48)))))

    "advises relaunching a TD launched on an out-of-date module since updated in place to a current one" in {
      tcbInfo.evaluate(pck, td15(withTeeTcbSvn(0x06, 0x01, 0x04), report), currentQe).map(_.status) shouldBe Right(TcbStatus.TDRelaunchAdvised)
    }

    "rates a TD whose module is still out of date out of date" in {
      tcbInfo.evaluate(pck, td15(withTeeTcbSvn(0x06, 0x01, 0x04), withTeeTcbSvn(0x06, 0x01, 0x04)), currentQe).map(_.status) shouldBe
        Right(TcbStatus.OutOfDate)
    }

    "rates a TD launched on a current module by its launch" in {
      tcbInfo.evaluate(pck, td15(report, report), currentQe) shouldBe Right(TcbLevel(TcbStatus.UpToDate, Seq.empty))
    }
  }

  "parse" - {
    "rejects an SGX TCB Info" in {
      TdxTcbInfo.parse(resourceBytes("tcb_info_v3_sgx.json")) shouldBe a[Left[?, ?]]
    }

    "reads the validity end" in {
      tcbInfo.nextUpdate shouldBe java.time.Instant.parse("2026-11-13T05:08:51Z")
    }
  }

  private val currentQe = TcbLevel(TcbStatus.UpToDate, Seq.empty)

  private def quoteOf(report: DcapQuote.Td10ReportBody): DcapQuote.Quote = quote.copy(body = DcapQuote.Td10QuoteBody(report))

  private def rate(pck: IntelPki.PckTcb, report: DcapQuote.Td10ReportBody, qe: TcbLevel = currentQe) =
    tcbInfo.evaluate(pck, quoteOf(report), qe)

  private def withTeeTcbSvn(leading: Int*): DcapQuote.Td10ReportBody =
    report.copy(teeTcbSvn = ByteStr((leading ++ Seq.fill(16 - leading.size)(0)).map(_.toByte).toArray))
}
