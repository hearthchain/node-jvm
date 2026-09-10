import sbt.*
import sbt.Keys.{test, version, name}
import sbt.protocol.testing.TestResult
import sbtassembly.AssemblyKeys.{assembly, assemblyMergeStrategy}
import sbtassembly.AssemblyPlugin.autoImport.assemblyJarName
import sbtassembly.{MergeStrategy, PathList}

object CommonSettings extends AutoPlugin {
  object autoImport extends CommonKeys

  override def trigger: PluginTrigger = allRequirements

  override def projectSettings: Seq[Def.Setting[?]] = Seq()

  val assemblySettings: Seq[Def.Setting[?]] = Seq(
    assemblyJarName := s"${name.value}-all-${version.value}.jar",
    test            := TestResult.Passed,
    assemblyMergeStrategy := {
      case p
          if p.endsWith(".proto") ||
            p.endsWith("module-info.class") ||
            p.endsWith("io.netty.versions.properties") ||
            p.contains("OSGI-INF") ||
            p.endsWith(".kotlin_module") =>
        MergeStrategy.discard

      case "scala-collection-compat.properties" | "META-INF/versions/9/OSGI-INF/MANIFEST.MF" =>
        MergeStrategy.discard

      case "logback.xml" |
           PathList("scala", "util", "control", "compat") |
           PathList("scala", "collection", "compat") |
           PathList("api-docs", "openapi.yaml") |
           "META-INF/FastDoubleParser-LICENSE" =>
        MergeStrategy.last
      case other => (assembly / assemblyMergeStrategy).value(other)
    }
  )
}

// The architectures node debs are built for. Each value carries every derivation the build needs from it, so an arch
// is named once and the Debian control field cannot drift away from the Corretto native jar it ships.
sealed abstract class DebArchitecture(val debString: String, val correttoClassifier: String)
object DebArchitecture {
  case object Amd64 extends DebArchitecture("amd64", "linux-x86_64")
  case object Arm64 extends DebArchitecture("arm64", "linux-aarch_64")

  val all: Seq[DebArchitecture] = Seq(Amd64, Arm64)

  def apply(debString: String): DebArchitecture =
    all.find(_.debString == debString).getOrElse(sys.error(s"Unsupported deb architecture: $debString"))
}

trait CommonKeys {
  val packageSource = settingKey[File]("Additional files for DEB")
}
