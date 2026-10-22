package tech.hearth.crypto.dcap

import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.traverse.*
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.crypto.dcap.TdxTcbInfo.*
import play.api.libs.json.{JsDefined, JsLookupResult, JsValue, Json, Reads}

import java.time.Instant
import scala.util.Try

/** Intel's TDX TCB Info V3 for one platform model (FMSPC), as UpdateCollateral stores it, and the TCB evaluation of
  * Intel's DCAP Quote Verification Library over it: which of its TCB levels a TD's platform and TDX module meet.
  * Parsed from a payload DcapCollateral already verified, so this checks structure only, never Intel's signature.
  */
case class TdxTcbInfo(nextUpdate: Instant, tdxModule: TdxModule, tdxModuleIdentities: Map[String, TdxModuleIdentity], tcbLevels: Seq[PlatformLevel]) {

  def evaluate(pck: IntelPki.PckTcb, report: DcapQuote.Td10ReportBody): Either[String, TcbLevel] = {
    val teeTcbSvn     = report.teeTcbSvn.arr.toSeq.map(_ & 0xff)
    val moduleVersion = teeTcbSvn(1)
    for {
      (module, moduleLevel) <- moduleOf(moduleVersion, teeTcbSvn.head)
      _ <- Either.raiseUnless(report.mrSignerSeam == module.mrSigner)(s"TDX module signer ${report.mrSignerSeam} is not ${module.mrSigner}")
      _ <- Either.raiseUnless(masked(report.seamAttributes, module.attributesMask) == module.attributes) {
        s"TDX module attributes ${report.seamAttributes} do not match ${module.attributes}"
      }
      // Bytes 0 and 1 are the module's own SVN and version, which its identity rates instead when there is one
      from = if (moduleVersion == 0) 0 else 2
      platform <- tcbLevels
        .find(l => meets(pck.cpuSvn, l.sgxSvn) && pck.pceSvn >= l.pceSvn && meets(teeTcbSvn.drop(from), l.tdxSvn.drop(from)))
        .toRight("Platform TCB is below every TCB level")
        .map(_.level)
      status <- moduleLevel.fold(platform.status.asRight[String])(m => converge(platform.status, m.status))
    } yield TcbLevel(status, (platform.advisoryIds ++ moduleLevel.toSeq.flatMap(_.advisoryIds)).distinct)
  }

  private def moduleOf(version: Int, svn: Int): Either[String, (TdxModule, Option[TcbLevel])] =
    if (version == 0) Right((tdxModule, None))
    else {
      val id = f"TDX_$version%02X"
      for {
        identity <- tdxModuleIdentities.get(id).toRight(s"No TDX module identity $id")
        level    <- identity.tcbLevels.find(svn >= _.isvSvn).toRight(s"TDX module $id SVN $svn is below every TCB level")
      } yield (identity.module, Some(level.level))
    }

  private def meets(svn: Seq[Int], level: Seq[Int]): Boolean = svn.zip(level).forall(_ >= _)

  private def masked(value: ByteStr, mask: ByteStr): ByteStr = ByteStr(value.arr.zip(mask.arr).map((v, m) => (v & m).toByte))

  // As Intel's QVL: an out-of-date module makes an otherwise current platform out of date
  private def converge(platform: TcbStatus, module: TcbStatus): Either[String, TcbStatus] = module match {
    case TcbStatus.UpToDate => Right(platform)
    case TcbStatus.OutOfDate =>
      Right(platform match {
        case TcbStatus.UpToDate | TcbStatus.SWHardeningNeeded                            => TcbStatus.OutOfDate
        case TcbStatus.ConfigurationNeeded | TcbStatus.ConfigurationAndSWHardeningNeeded => TcbStatus.OutOfDateConfigurationNeeded
        case other                                                                       => other
      })
    case TcbStatus.Revoked => Right(TcbStatus.Revoked)
    case other             => Left(s"Unexpected TDX module TCB status $other")
  }
}

object TdxTcbInfo {
  enum TcbStatus {
    case UpToDate, SWHardeningNeeded, ConfigurationNeeded, ConfigurationAndSWHardeningNeeded, OutOfDate, OutOfDateConfigurationNeeded, Revoked
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

  def parse(payload: Array[Byte]): Either[String, TdxTcbInfo] =
    for {
      json <- Try(Json.parse(payload)).toEither.leftMap(e => s"invalid JSON: ${e.getMessage}")
      info = json \ "tcbInfo"
      id      <- read[String](info \ "id", "id")
      version <- read[Int](info \ "version", "version")
      _       <- Either.raiseUnless(id == "TDX" && version == 3)(s"Not a TDX TCB Info V3: id $id, version $version")
      nextUpdate <- read[String](info \ "nextUpdate", "nextUpdate").flatMap { s =>
        Try(Instant.parse(s)).toEither.leftMap(_ => s"invalid 'nextUpdate': $s")
      }
      tdxModule  <- module(info \ "tdxModule")
      identities <- (info \ "tdxModuleIdentities").asOpt[Seq[JsValue]].getOrElse(Seq.empty).traverse(moduleIdentity)
      levels     <- read[Seq[JsValue]](info \ "tcbLevels", "tcbLevels").flatMap(_.traverse(platformLevel))
    } yield TdxTcbInfo(nextUpdate, tdxModule, identities.toMap, levels)

  private def moduleIdentity(json: JsValue): Either[String, (String, TdxModuleIdentity)] =
    for {
      id     <- read[String](json \ "id", "id")
      module <- module(JsDefined(json))
      levels <- read[Seq[JsValue]](json \ "tcbLevels", "tcbLevels").flatMap(_.traverse { l =>
        (read[Int](l \ "tcb" \ "isvsvn", "isvsvn"), tcbLevel(l)).mapN(ModuleLevel(_, _))
      })
    } yield id -> TdxModuleIdentity(module, levels)

  private def module(json: JsLookupResult): Either[String, TdxModule] =
    (hex(json \ "mrsigner", "mrsigner"), hex(json \ "attributes", "attributes"), hex(json \ "attributesMask", "attributesMask"))
      .mapN(TdxModule(_, _, _))

  private def platformLevel(json: JsValue): Either[String, PlatformLevel] =
    (
      components(json \ "tcb" \ "sgxtcbcomponents", "sgxtcbcomponents"),
      read[Int](json \ "tcb" \ "pcesvn", "pcesvn"),
      components(json \ "tcb" \ "tdxtcbcomponents", "tdxtcbcomponents"),
      tcbLevel(json)
    ).mapN(PlatformLevel(_, _, _, _))

  private def tcbLevel(json: JsValue): Either[String, TcbLevel] =
    read[String](json \ "tcbStatus", "tcbStatus").flatMap(TcbStatus.parse).map { status =>
      TcbLevel(status, (json \ "advisoryIDs").asOpt[Seq[String]].getOrElse(Seq.empty))
    }

  private def components(json: JsLookupResult, name: String): Either[String, Seq[Int]] =
    read[Seq[JsValue]](json, name)
      .flatMap(_.traverse(c => read[Int](c \ "svn", s"$name svn")))
      .filterOrElse(_.size == ComponentCount, s"'$name' must have $ComponentCount components")

  private def hex(json: JsLookupResult, name: String): Either[String, ByteStr] =
    read[String](json, name).flatMap(s => Base16.tryDecode(s).toEither.bimap(_ => s"invalid '$name': $s", ByteStr(_)))

  private def read[T: Reads](json: JsLookupResult, name: String): Either[String, T] =
    json.validate[T].asEither.leftMap(_ => s"missing or invalid '$name'")
}
