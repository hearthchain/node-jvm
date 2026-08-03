import sbt.*
import sbt.Keys.*

object RunApplicationSettings extends AutoPlugin {
  override def projectSettings: Seq[Def.Setting[_]] =
    inConfig(Compile)(
      Seq(
        mainClass             := Some("tech.hearth.Application"),
        discoveredMainClasses := (Compile / mainClass).value.toSeq,
        run / fork            := true
      )
    )
}
