import com.typesafe.sbt.SbtNativePackager.Debian
import sbtcompat.PluginCompat.toFileRef

import scala.annotation.nowarn

enablePlugins(
  RunApplicationSettings,
  JavaServerAppPackaging,
  UniversalDeployPlugin,
  JDebPackaging,
  SystemdPlugin,
  VersionObject,
  PublishedModule
)

libraryDependencies ++= Dependencies.node.value

homepage := Some(uri("https://waves.tech/"))
developers := List(
  Developer("ismagin", "Ilya Smagin", "ilya.smagin@gmail.com", uri("https://github.com/ismagin")),
  Developer("asayadyan", "Artyom Sayadyan", "xrtm000@gmail.com", uri("https://github.com/xrtm000")),
  Developer("mpotanin", "Mike Potanin", "mpotanin@wavesplatform.com", uri("https://github.com/potan")),
  Developer("irakitnykh", "Ivan Rakitnykh", "mrkr.reg@gmail.com", uri("https://github.com/mrkraft")),
  Developer("akiselev", "Alexey Kiselev", "alexey.kiselev@gmail.com>", uri("https://github.com/alexeykiselev")),
  Developer("phearnot", "Sergey Nazarov", "phearnot@renee.ru", uri("https://github.com/phearnot")),
  Developer("tolsi", "Sergey Tolmachev", "tolsi.ru@gmail.com", uri("https://github.com/tolsi")),
  Developer("vsuharnikov", "Vyatcheslav Suharnikov", "arz.freezy@gmail.com", uri("https://github.com/vsuharnikov")),
  Developer("ivan-mashonskiy", "Ivan Mashonskii", "ivan.mashonsky@gmail.com", uri("https://github.com/ivan-mashonskiy"))
)

inConfig(Compile)(
  Seq(
    PB.targets += PB.Target(scalapb.gen(flatPackage = true), sourceManaged.value),
    PB.protoSources += PB.externalIncludePath.value,
    PB.generate / includeFilter := { (f: File) =>
      (** / "hearth" / "*.proto").matches(f.toPath)
    },
    PB.deleteTargetDirectory := false
  )
)

// sbt 1's inTask(assembly)(...) helper was dropped in sbt 2 (ProjectExtra keeps only inConfig/inScope),
// so this expands it manually: inTask(key)(settings) == inScope(ThisScope.copy(task = Select(key.key)))(settings).
inScope(ThisScope.copy(task = Select(assembly.key)))(
  Seq(
    name := "hearth",
    fullClasspath := {
      implicit val conv: xsbti.FileConverter = fileConverter.value
      val optional = (Optional / update).value.select(configurationFilter("optional")).map(f => toFileRef(f)).toSet
      (Runtime / fullClasspath).value.filterNot(item => optional.contains(item.data))
    }
  ) ++ CommonSettings.assemblySettings
)

// Adds "$lib_dir/*" to app_classpath in the executable file, this is needed for extensions
scriptClasspath += "*"

bashScriptExtraDefines +=
  """# Workaround to ignore the -h option
    |process_args() {
    |  local no_more_snp_opts=0
    |  while [[ $# -gt 0 ]]; do
    |    case "$1" in
    |    --) shift && no_more_snp_opts=1 && break ;;
    |    -no-version-check) no_version_check=1 && shift ;;
    |    -java-home) require_arg path "$1" "$2" && jre=$(eval echo $2) && java_cmd="$jre/bin/java" && shift 2 ;;
    |     -D*|-agentlib*|-agentpath*|-javaagent*|-XX*) addJava "$1" && shift ;;
    |                                             -J*) addJava "${1:2}" && shift ;;
    |                                               *) addResidual "$1" && shift ;;
    |    esac
    |  done
    |
    |  if [[ no_more_snp_opts ]]; then
    |    while [[ $# -gt 0 ]]; do
    |      addResidual "$1" && shift
    |    done
    |  fi
    |
    |  is_function_defined process_my_args && {
    |    myargs=("${residual_args[@]}")
    |    residual_args=()
    |    process_my_args "${myargs[@]}"
    |  }
    |}
    |""".stripMargin

bashScriptExtraDefines += bashScriptEnvConfigLocation.value.fold("")(envFile => s"[[ -f $envFile ]] && . $envFile")

inConfig(Universal)(
  Seq(
    maintainer  := "tech.hearth",
    packageName := s"hearth-jvm-${version.value}",
    mappings += {
      implicit val conv: xsbti.FileConverter = fileConverter.value
      toFileRef(baseDirectory.value / s"hearth-sample.conf") -> "doc/hearth.conf.sample"
    },
    // Only what the node cannot run without. Heap and GC tuning belong in JAVA_OPTS, and application.ini would
    // silently outrank it: the launcher lists $JAVA_OPTS before the options it reads from this file. Every entry
    // point reads it, `-main tech.hearth.Importer` included, so nothing here may be instance-specific either.
    javaOptions ++= Seq(
      // -J prefix is required by the bash script
      "-J-XX:+ExitOnOutOfMemoryError",
      // JVM default charset for proper and deterministic getBytes behaviour
      "-J-Dfile.encoding=UTF-8",
      "-J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "-J--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
      "-J--enable-native-access=ALL-UNNAMED"
    )
  )
)

inConfig(Linux)(
  Seq(
    packageSummary     := "Hearth node",
    packageDescription := "Hearth node",
    name               := "hearth-jvm",
    normalizedName     := name.value,
    packageName        := normalizedName.value
  )
)

def fixScriptName(path: String, name: String, packageName: String): String =
  path.replace(s"/bin/$name", s"/bin/$packageName")

linuxPackageMappings := linuxPackageMappings.value.map { lpm =>
  lpm.copy(mappings = lpm.mappings.map {
    case (file, path) if path.endsWith(s"/bin/${name.value}") => file -> fixScriptName(path, name.value, (Linux / packageName).value)
    // The package default network; an instance config file overrides it, and the systemd unit adds the
    // per-instance directories
    case (file, path) if path.endsWith("/conf/application.ini") =>
      val dest = (Debian / target).value / path
      IO.write(dest, "-J-Dhearth.defaults.blockchain.type=mainnet\n")
      IO.append(dest, IO.readBytes(file))
      dest -> path
    case other => other
  })
}

linuxPackageSymlinks := linuxPackageSymlinks.value.filterNot(_.link == s"/etc/${(Linux / packageName).value}").map { lsl =>
  if (lsl.link.endsWith(s"/bin/${name.value}"))
    lsl.copy(
      fixScriptName(lsl.link, name.value, (Linux / packageName).value),
      fixScriptName(lsl.destination, name.value, (Linux / packageName).value)
    )
  else lsl
}

// The scripts go to the deb straight from the source tree: packageBin copies each into DEBIAN/ and substitutes
// ${{app_name}}/${{header}} in the copy, so the round trip through the generic maintainerScripts (lines in, temp
// files back out under (Universal / target)/tmp/debian) buys nothing here and only adds a path sbt 2 cannot track.
//
// Def.uncached is mandatory, not an optimisation: sbt 2 refuses to cache a task whose result carries java.io.File,
// and Seq[(File, String)] is what JDebPackaging reads. The key is deprecated in favour of the generic
// maintainerScripts, but JDebPackaging still reads this one, so there is nothing to migrate to.
@nowarn("cat=deprecation")
val debianMaintainerScriptsFromSource = debianMaintainerScripts := Def.uncached {
  val scriptDir = (Debian / packageSource).value / "debian"
  Seq("preinst", "postinst", "prerm", "postrm").map(name => (scriptDir / name) -> name)
}

debianMaintainerScriptsFromSource

inConfig(Debian)(
  Seq(
    packageArchitecture      := DebArchitecture.Arm64.debString,
    maintainer               := "tech.hearth",
    packageSource            := sourceDirectory.value / "package",
    linuxStartScriptTemplate := (packageSource.value / "systemd.service").toURI.toURL,
    // Template unit: one enabled instance per node, `systemctl start hearth-jvm@<instance>`
    linuxStartScriptName := Some(s"${(Linux / packageName).value}@.service"),
    // Not /lib/systemd/system: on merged-usr systems dpkg chokes on a package that ships the aliased ./lib path
    defaultLinuxStartScriptLocation := "/usr/lib/systemd/system",
    debianPackageDependencies += "java17-runtime-headless",
    linuxPackageMappings := {
      val classifier = DebArchitecture(packageArchitecture.value).correttoClassifier
      val platformSpecificMappings = packageMapping(
        (Optional / update).value
          .select(artifactFilter(classifier = classifier))
          .map(f => f -> (defaultLinuxInstallLocation.value + "/" + (Debian / packageName).value + "/lib/software.amazon.cryptools." + f.getName))*
      )

      val instanceDirs = Seq("/etc", "/var/lib").map { parent =>
        packageTemplateMapping(s"$parent/${(Debian / packageName).value}")()
          .withUser((Linux / daemonUser).value)
          .withGroup((Linux / daemonGroup).value)
          .withPerms("0750")
      }

      // /etc/default/<pkg> is deliberately not shipped: the launcher sources it after systemd has applied the
      // instance's env file, so a JAVA_OPTS set there would override every instance's own
      val etcDefault = s"/etc/default/${(Debian / packageName).value}"

      linuxPackageMappings.value.map(m =>
        m.copy(mappings = m.mappings.filterNot { case (f, path) =>
          f.name.contains("AmazonCorretto") || f.name.contains("conscrypt") || path == etcDefault
        })
      ) ++ instanceDirs :+ platformSpecificMappings
    }
  )
)

V.scalaPackage := "tech.hearth"
