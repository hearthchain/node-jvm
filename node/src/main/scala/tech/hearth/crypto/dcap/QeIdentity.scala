package tech.hearth.crypto.dcap

import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.traverse.*
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.dcap.DcapJson.*
import tech.hearth.crypto.dcap.QeIdentity.Level
import tech.hearth.crypto.dcap.TdxTcbInfo.{TcbLevel, TcbStatus}

import java.nio.{ByteBuffer, ByteOrder}
import java.time.Instant

/** Intel's Enclave Identity V2 of the TD Quoting Enclave, as UpdateCollateral stores it: which QE may vouch for a
  * TD's attestation key, and how current its ISVSVN is. Evaluated as Intel's QVL does (EnclaveReportVerifier).
  */
case class QeIdentity(
    nextUpdate: Instant,
    miscSelect: Long,
    miscSelectMask: Long,
    attributes: ByteStr,
    attributesMask: ByteStr,
    mrSigner: ByteStr,
    isvProdId: Int,
    tcbLevels: Seq[Level]
) {
  def evaluate(report: DcapQuote.EnclaveReportBody): Either[String, TcbLevel] =
    for {
      _     <- Either.raiseUnless((report.miscSelect & miscSelectMask) == miscSelect)(s"QE MISCSELECT ${report.miscSelect} does not match")
      _     <- Either.raiseUnless(masked(report.sgxAttributes, attributesMask) == attributes)(s"QE attributes ${report.sgxAttributes} do not match")
      _     <- Either.raiseUnless(report.mrSigner == mrSigner)(s"QE signer ${report.mrSigner} is not $mrSigner")
      _     <- Either.raiseUnless(report.isvProdId == isvProdId)(s"QE ISVPRODID ${report.isvProdId} is not $isvProdId")
      level <- tcbLevels.find(_.isvSvn <= report.isvSvn).toRight(s"QE ISVSVN ${report.isvSvn} is below every TCB level")
    } yield level.level
}

object QeIdentity {
  case class Level(isvSvn: Int, level: TcbLevel)

  private val ValidStatuses =
    Set(TcbStatus.UpToDate, TcbStatus.OutOfDate, TcbStatus.ConfigurationNeeded, TcbStatus.Revoked, TcbStatus.OutOfDateConfigurationNeeded)

  def parse(payload: Array[Byte]): Either[String, QeIdentity] =
    for {
      identity       <- DcapJson.parse(payload, "enclaveIdentity")
      id             <- read[String](identity \ "id", "id")
      version        <- read[Int](identity \ "version", "version")
      _              <- Either.raiseUnless(id == "TD_QE" && version == 2)(s"Not a TD Quoting Enclave Identity V2: id $id, version $version")
      nextUpdate     <- instant(identity \ "nextUpdate", "nextUpdate")
      miscSelect     <- hex(identity \ "miscselect", "miscselect").flatMap(uint32("miscselect"))
      miscSelectMask <- hex(identity \ "miscselectMask", "miscselectMask").flatMap(uint32("miscselectMask"))
      attributes     <- hex(identity \ "attributes", "attributes")
      attributesMask <- hex(identity \ "attributesMask", "attributesMask")
      mrSigner       <- hex(identity \ "mrsigner", "mrsigner")
      isvProdId      <- read[Int](identity \ "isvprodid", "isvprodid")
      levels <- array(identity \ "tcbLevels", "tcbLevels").flatMap(_.traverse { l =>
        (read[Int](l \ "tcb" \ "isvsvn", "isvsvn"), tcbLevel(l, ValidStatuses)).mapN(Level(_, _))
      })
    } yield QeIdentity(nextUpdate, miscSelect, miscSelectMask, attributes, attributesMask, mrSigner, isvProdId, levels)

  // The JSON spells MISCSELECT in the report's own byte order, which is little-endian
  private def uint32(name: String)(bytes: ByteStr): Either[String, Long] =
    Either.cond(
      bytes.arr.length == 4,
      Integer.toUnsignedLong(ByteBuffer.wrap(bytes.arr).order(ByteOrder.LITTLE_ENDIAN).getInt),
      s"'$name' must be 4 bytes"
    )
}
