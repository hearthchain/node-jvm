package tech.hearth.crypto.dcap

import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.traverse.*
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.dcap.DcapJson.*
import tech.hearth.crypto.dcap.TdxTcbInfo.*
import play.api.libs.json.{JsDefined, JsLookupResult, JsValue}

import java.time.Instant

/** Intel's TDX TCB Info V3 for one platform model (FMSPC), as UpdateCollateral stores it, and the TCB evaluation of
  * Intel's DCAP Quote Verification Library over it (QuoteVerifier's TDX module check, then checkTcbLevel): which of
  * its TCB levels a TD's platform, TDX module and Quoting Enclave meet.
  */
case class TdxTcbInfo(
    nextUpdate: Instant,
    pceId: ByteStr,
    tdxModule: TdxModule,
    tdxModuleIdentities: Map[String, TdxModuleIdentity],
    tcbLevels: Seq[PlatformLevel]
) {

  /** `qe` is the Quoting Enclave's level, from QeIdentity.evaluate. */
  def evaluate(pck: IntelPki.PckTcb, quote: DcapQuote.Quote, qe: TcbLevel): Either[String, TcbLevel] =
    for {
      (report, teeTcbSvn2) <- quote.body match {
        case DcapQuote.Td10QuoteBody(body) => Right((body, None))
        case DcapQuote.Td15QuoteBody(body) => Right((body.td10, Some(body.teeTcbSvn2)))
        case DcapQuote.SgxQuoteBody(_)     => Left("TDX TCB Info can't rate an SGX quote")
      }
      _      <- Either.raiseUnless(pck.pceId == pceId)(s"PCE ID ${pck.pceId} is not the TCB Info's $pceId")
      _      <- checkModule(report)
      launch <- evaluateSvn(pck, svns(report.teeTcbSvn), qe)
      // A TD 1.5 module updated in place reports its current SVN apart from the one the TD launched on
      current <- teeTcbSvn2.traverse(svn2 => evaluateSvn(pck, svns(svn2), qe))
    } yield current.fold(launch)(c => launch.copy(status = relaunch(launch.status, c.status)))

  private def checkModule(report: DcapQuote.Td10ReportBody): Either[String, Unit] = {
    val version = svns(report.teeTcbSvn)(1)
    for {
      module <- if (version == 0) Right(tdxModule) else identity(version).map(_.module)
      _      <- Either.raiseUnless(report.mrSignerSeam == module.mrSigner)(s"TDX module signer ${report.mrSignerSeam} is not ${module.mrSigner}")
      // As QVL, which compares no mask here: any SEAM attribute set fails
      _ <- Either.raiseUnless(report.seamAttributes.arr.forall(_ == 0) && report.seamAttributes == module.attributes) {
        s"TDX module attributes ${report.seamAttributes} do not match ${module.attributes}"
      }
    } yield ()
  }

  private def evaluateSvn(pck: IntelPki.PckTcb, teeTcbSvn: Seq[Int], qe: TcbLevel): Either[String, TcbLevel] = {
    val version = teeTcbSvn(1)
    // Bytes 0 and 1 are the module's own SVN and version, which its identity rates instead when there is one
    val from = if (version == 0) 0 else 2
    for {
      platform <- tcbLevels
        .find(l => meets(pck.cpuSvn, l.sgxSvn) && pck.pceSvn >= l.pceSvn && meets(teeTcbSvn.drop(from), l.tdxSvn.drop(from)))
        .toRight("Platform TCB is below every TCB level")
        .map(_.level)
      module <-
        if (version == 0) Right(None)
        else
          identity(version).flatMap { i =>
            i.tcbLevels.find(teeTcbSvn.head >= _.isvSvn).toRight(s"TDX module SVN ${teeTcbSvn.head} is below every TCB level").map(l => Some(l.level))
          }
    } yield TcbLevel(
      converge(platform.status, module.map(_.status).toSeq :+ qe.status),
      platform.advisoryIds ++ qe.advisoryIds ++ module.toSeq.flatMap(_.advisoryIds)
    )
  }

  private def identity(version: Int): Either[String, TdxModuleIdentity] = {
    val id = f"TDX_$version%02X"
    tdxModuleIdentities.get(id).toRight(s"No TDX module identity $id")
  }

  private def svns(teeTcbSvn: ByteStr): Seq[Int] = teeTcbSvn.arr.toSeq.map(_ & 0xff)

  private def meets(svn: Seq[Int], level: Seq[Int]): Boolean = svn.zip(level).forall(_ >= _)
}

object TdxTcbInfo {
  enum TcbStatus {
    case UpToDate, SWHardeningNeeded, ConfigurationNeeded, ConfigurationAndSWHardeningNeeded, OutOfDate, OutOfDateConfigurationNeeded, Revoked,
      TDRelaunchAdvised, TDRelaunchAdvisedConfigurationNeeded
  }

  object TcbStatus {
    def parse(s: String): Either[String, TcbStatus] = values.find(_.toString == s).toRight(s"Unknown TCB status $s")
  }

  case class TcbLevel(status: TcbStatus, advisoryIds: Seq[String])
  case class TdxModule(mrSigner: ByteStr, attributes: ByteStr, attributesMask: ByteStr)
  case class ModuleLevel(isvSvn: Int, level: TcbLevel)
  case class TdxModuleIdentity(module: TdxModule, tcbLevels: Seq[ModuleLevel])
  case class PlatformLevel(sgxSvn: Seq[Int], pceSvn: Int, tdxSvn: Seq[Int], level: TcbLevel)

  private val ComponentCount = 16

  private val PlatformStatuses = Set(
    TcbStatus.UpToDate,
    TcbStatus.SWHardeningNeeded,
    TcbStatus.ConfigurationNeeded,
    TcbStatus.ConfigurationAndSWHardeningNeeded,
    TcbStatus.OutOfDate,
    TcbStatus.OutOfDateConfigurationNeeded,
    TcbStatus.Revoked
  )
  private val ModuleStatuses = Set(TcbStatus.UpToDate, TcbStatus.OutOfDate, TcbStatus.Revoked)

  /** As QVL's convergeTcbStatuses: an out-of-date module or Quoting Enclave makes a current platform out of date, a
    * revoked one revokes it, and their other statuses leave it as it is.
    */
  private def converge(platform: TcbStatus, components: Seq[TcbStatus]): TcbStatus =
    if (components.contains(TcbStatus.Revoked)) TcbStatus.Revoked
    else if (components.contains(TcbStatus.OutOfDate)) platform match {
      case TcbStatus.UpToDate | TcbStatus.SWHardeningNeeded                            => TcbStatus.OutOfDate
      case TcbStatus.ConfigurationNeeded | TcbStatus.ConfigurationAndSWHardeningNeeded => TcbStatus.OutOfDateConfigurationNeeded
      case other                                                                       => other
    }
    else platform

  /** As QVL's checkForRelaunch: a TD launched on an out-of-date module that has since been updated in place to a
    * current one only needs relaunching.
    */
  private def relaunch(launch: TcbStatus, current: TcbStatus): TcbStatus = {
    val currentIsCurrent =
      Set(TcbStatus.UpToDate, TcbStatus.SWHardeningNeeded, TcbStatus.ConfigurationNeeded, TcbStatus.ConfigurationAndSWHardeningNeeded)
        .contains(current)
    val needsConfiguration =
      Set(TcbStatus.ConfigurationNeeded, TcbStatus.OutOfDateConfigurationNeeded, TcbStatus.ConfigurationAndSWHardeningNeeded)
    if ((launch == TcbStatus.OutOfDate || launch == TcbStatus.OutOfDateConfigurationNeeded) && currentIsCurrent) {
      if (needsConfiguration.contains(launch) || needsConfiguration.contains(current)) TcbStatus.TDRelaunchAdvisedConfigurationNeeded
      else TcbStatus.TDRelaunchAdvised
    } else launch
  }

  def parse(payload: Array[Byte]): Either[String, TdxTcbInfo] =
    for {
      info       <- DcapJson.parse(payload, "tcbInfo")
      id         <- read[String](info \ "id", "id")
      version    <- read[Int](info \ "version", "version")
      _          <- Either.raiseUnless(id == "TDX" && version == 3)(s"Not a TDX TCB Info V3: id $id, version $version")
      nextUpdate <- instant(info \ "nextUpdate", "nextUpdate")
      pceId      <- hex(info \ "pceId", "pceId")
      tdxModule  <- module(info \ "tdxModule")
      // Needed only for a module version above 0, where its absence fails the evaluation
      identities <- (info \ "tdxModuleIdentities").asOpt[Seq[JsValue]].getOrElse(Seq.empty).traverse(moduleIdentity)
      levels     <- array(info \ "tcbLevels", "tcbLevels").flatMap(_.traverse(platformLevel))
    } yield TdxTcbInfo(nextUpdate, pceId, tdxModule, identities.toMap, levels)

  // Keyed upper-case, as QVL matches ids
  private def moduleIdentity(json: JsValue): Either[String, (String, TdxModuleIdentity)] =
    for {
      id     <- read[String](json \ "id", "id")
      module <- module(JsDefined(json))
      levels <- array(json \ "tcbLevels", "tcbLevels").flatMap(_.traverse { l =>
        (read[Int](l \ "tcb" \ "isvsvn", "isvsvn"), tcbLevel(l, ModuleStatuses)).mapN(ModuleLevel(_, _))
      })
    } yield id.toUpperCase -> TdxModuleIdentity(module, levels)

  private def module(json: JsLookupResult): Either[String, TdxModule] =
    (hex(json \ "mrsigner", "mrsigner"), hex(json \ "attributes", "attributes"), hex(json \ "attributesMask", "attributesMask"))
      .mapN(TdxModule(_, _, _))

  private def platformLevel(json: JsValue): Either[String, PlatformLevel] =
    (
      components(json \ "tcb" \ "sgxtcbcomponents", "sgxtcbcomponents", ComponentCount),
      read[Int](json \ "tcb" \ "pcesvn", "pcesvn"),
      components(json \ "tcb" \ "tdxtcbcomponents", "tdxtcbcomponents", ComponentCount),
      tcbLevel(json, PlatformStatuses)
    ).mapN(PlatformLevel(_, _, _, _))
}
