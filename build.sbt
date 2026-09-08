import java.nio.file.Files

import scala.jdk.CollectionConverters.*

Global / onChangedBuildSource := ReloadOnSourceChanges

enablePlugins(GitVersioning)

git.uncommittedSignifier       := Some("DIRTY")
ThisBuild / git.useGitDescribe := true
ThisBuild / PB.protocVersion   := Dependencies.gProtoVersion

ThisBuild / dependencyOverrides ++= Dependencies.overrides.value

ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle    := true
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

lazy val node = project

lazy val `node-testkit` = project
  .in(file("node/testkit"))
  .dependsOn(`node`)
  .enablePlugins(PublishedModule)
  .settings(libraryDependencies ++= Dependencies.nodeTests.map(_.withConfigurations(Some("compile"))))

lazy val `node-tests` = project
  .in(file("node/tests"))
  .dependsOn(`node-testkit`)
  .settings(libraryDependencies ++= Dependencies.logDeps)

lazy val `grpc-server` =
  project.dependsOn(node % "compile;runtime->provided", `node-testkit` % "test")

lazy val `node-it` = project.dependsOn(`grpc-server`, `node-testkit`)

lazy val `node-generator` = project.dependsOn(node, `node-testkit`)

lazy val benchmark = project.dependsOn(node, `node-testkit`)

lazy val `hearth-node` = (project in file("."))
  .aggregate(
    node,
    `node-it`,
    `node-testkit`,
    `node-tests`,
    `node-generator`,
    `grpc-server`,
    benchmark
  )

inScope(Global)(
  Seq(
    scalaVersion         := "3.8.4",
    organization         := "tech.hearth",
    organizationName     := "Hearth Chain",
    organizationHomepage := Some(uri("https://hearth.tech")),
    licenses             := Seq(("MIT", uri("https://github.com/hearthchain/node-jvm/blob/main/LICENSE"))),
    publish / skip       := true,
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-language:postfixOps",
      "-Wunused:all",
      "-Wconf:src=src_managed/.*:s"
    ),
    crossPaths        := false,
    cancelable        := true,
    parallelExecution := true,
    /* http://www.scalatest.org/user_guide/using_the_runner
     * o - select the standard output reporter
     * I - show reminder of failed and canceled tests without stack traces
     * D - show all durations
     * O - drop InfoProvided events
     * F - show full stack traces
     * u - select the JUnit XML reporter with output directory
     */
    testOptions += Tests.Argument("-oIDOF", "-u", "target/test-reports"),
    testOptions += Tests.Setup(() => sys.props("sbt-testing") = "true"),
    resolvers ++= Resolver.mavenLocal +: Seq(Resolver.sonatypeCentralSnapshots),
    Compile / packageDoc / publishArtifact := false,
    concurrentRestrictions                 := Seq(Tags.limit(Tags.Test, math.min(EvaluateTask.SystemProcessors, 8))),
    // Dead settings sbt 2's project-load lint now catches that sbt 1 missed (same keys, same plugin
    // wiring, unused under sbt 1 too - see "SBT 2 unused-settings lint" in CLAUDE.md for the investigation).
    excludeLintKeys ++= Set(
      node / Universal / configuration,
      node / Linux / configuration,
      node / Debian / configuration,
      Global / maxParallelSuites,
      node / Rpm / daemonGroupGid,
      node / Rpm / daemonUserUid,
      node / Rpm / executableScriptName,
      node / Rpm / name,
      node / Linux / javaOptions,
      node / Debian / executableScriptName,
      node / Debian / daemonUser,
      node / Debian / daemonUserUid,
      node / Debian / daemonGroup,
      node / Debian / daemonGroupGid,
      node / debianControlScriptsDirectory,
      node / Universal / executableScriptName,
      node / UniversalSrc / name,
      `grpc-server` / Debian / executableScriptName,
      `grpc-server` / Debian / sourceDirectory,
      `grpc-server` / Universal / executableScriptName,
      `grpc-server` / UniversalSrc / name,
      node / git.gitDescribedVersion,
      `grpc-server` / git.gitDescribedVersion,
      benchmark / git.gitDescribedVersion,
      `node-generator` / git.gitDescribedVersion,
      `node-it` / git.gitDescribedVersion,
      `node-testkit` / git.gitDescribedVersion,
      `node-tests` / git.gitDescribedVersion
    )
  )
)

commands += Command.command("packageAll") { state =>
  "node / assembly" :: "buildDebPackages" :: "stageForDocker" :: state
}

// The image runs on Linux only, so the cross-platform jars the universal package carries are ~90MB it would transfer
// and throw away: conscrypt is the crypto provider for the platforms the image is not, and the Linux-only rocksdb and
// Corretto builds are staged in their place (see stageForDocker below).
lazy val dockerExcludedJars = Seq("AmazonCorrettoCryptoProvider", "conscrypt", "rocksdbjni")

// Corretto ships one jar per platform and the image needs the one matching its own, so they are staged under the
// name docker gives that platform in TARGETARCH and the Dockerfile copies just that directory.
lazy val dockerCorrettoArchs = Map("linux-x86_64" -> "amd64", "linux-aarch_64" -> "arm64")

// A configuration of its own, because a dependency reachable from node - Optional included - is packaged into the
// release tarball and the deb, which ship the cross-platform rocksdb jar on purpose.
lazy val DockerNative = config("docker-native")
ivyConfigurations += DockerNative
libraryDependencies += Dependencies.rocksdbForLinux % DockerNative

lazy val stageForDocker = taskKey[Unit]("Stage node and grpc-server files into docker/target, the image build context")
// Writes outside sbt2's tracked output paths (docker/target/**), so ActionCache has no way to know
// those files are this task's real output; on a cache hit it replays success without writing anything,
// silently leaving docker/target stale (see "SBT 2 action-cache" in docs/notes/build-tooling.md).
// Def.uncached forces this task to actually run its body every time, like every other
// filesystem-side-effecting task in this build.
stageForDocker := Def.uncached {
  val conv = fileConverter.value
  val dest = baseDirectory.value / "docker" / "target"

  // Third-party jars change only when a dependency does, so they are staged apart from hearth's own jars and scripts:
  // the image copies each directory as its own layer, and the big one stays cached across code-only builds.
  val universal = ((node / Universal / mappings).value ++ (`grpc-server` / Universal / mappings).value)
    .map { case (ref, path) => conv.toPath(ref).toFile -> path }

  val corretto = universal.flatMap { case (file, path) =>
    dockerCorrettoArchs.collectFirst {
      case (classifier, arch) if path.contains("AmazonCorrettoCryptoProvider") && path.contains(s"-$classifier.") =>
        file -> dest / "native" / arch / path.stripPrefix("lib/")
    }
  }

  val rocksdb = update.value
    .select(configurationFilter(DockerNative.name))
    .map(file => file -> dest / "lib" / s"org.rocksdb.${file.getName}")

  val staged = universal
    .filterNot { case (_, path) => dockerExcludedJars.exists(path.contains) }
    .map { case (file, path) =>
      val target =
        if (path.startsWith("lib/") && !path.startsWith("lib/tech.hearth.")) dest / "lib" / path.stripPrefix("lib/")
        else dest / "app" / path
      file -> target
    } ++ corretto ++ rocksdb

  val wanted = staged.map(_._2).toSet
  if (dest.isDirectory) {
    val walk = Files.walk(dest.toPath)
    val stale =
      try walk.iterator().asScala.map(_.toFile).filter(f => f.isFile && !wanted.contains(f)).toList
      finally walk.close()
    stale.foreach(IO.delete)
  }

  staged.foreach { case (source, target) =>
    // Copying only what changed keeps BuildKit's context transfer incremental: it detects an unchanged file by
    // size and modification time, and re-sends nothing for one it has already seen.
    if (!target.exists() || target.length() != source.length() || target.lastModified() != source.lastModified())
      IO.copyFile(source, target, preserveLastModified = true)
  }
}

lazy val compilePRRaw = taskKey[Unit]("Compile the project")
compilePRRaw := Def.uncached(
  Def
    .sequential(
      clean.all(ScopeFilter(inAnyProject)),
      scalafmtCheck.all(ScopeFilter(inAnyProject, inConfigurations(Compile))),
      compile.all(ScopeFilter(inAnyProject, inConfigurations(Test)))
    )
    .value
)

lazy val checkPRRaw = taskKey[Unit]("Compile the project and run unit tests")
checkPRRaw := Def
  .sequential(
    compilePRRaw,
    Def.sequential(
      testFull.all(
        ScopeFilter(inProjects(`grpc-server`, `node-tests`), inConfigurations(Test))
      ),
      assembly.all(ScopeFilter(inProjects(node))),
      stageForDocker
    )
  )
  .value

def commandWithFatalWarnings(commandName: String, task: TaskKey[Unit]): Command =
  Command.command(commandName) { state =>
    val extracted = Project.extract(state)
    val newState = extracted.appendWithoutSession(
      Seq(Global / scalacOptions ++= Seq("-Werror")),
      state
    )

    Project.extract(newState).runTask(task, newState)
    state
  }

def compilePR: Command = commandWithFatalWarnings("compilePR", compilePRRaw)
def checkPR: Command   = commandWithFatalWarnings("checkPR", checkPRRaw)

commands += Command.command("buildDebPackages") { state =>
  "set node / Debian / packageArchitecture := \"arm64\"" ::
    "node/ Debian / packageBin" ::
    "set node / Debian / packageArchitecture := \"amd64\"" ::
    "node / Debian / packageBin" ::
    "grpc-server / Debian / packageBin" ::
    state
}

lazy val buildPlatformIndependentArtifacts = taskKey[Unit]("Build fat JARs for node and TGZ for grpc-server")
buildPlatformIndependentArtifacts := Def.uncached {
  (node / assembly).value
  (`grpc-server` / Universal / packageZipTarball).value
}

commands += Command.command("buildReleaseArtifacts") { state =>
  "buildDebPackages" :: "buildPlatformIndependentArtifacts" :: state
}

/** Command: generateGenesis <path-to-config>
  * Runs: node / runMain tech.hearth.GenesisBlockGenerator <path>
  * Path is always resolved relative to build root, output without "[info]".
  */
def generateGenesisCommand: Command =
  Command.single("generateGenesis") { (state, rawPath) =>
    val ex = Project.extract(state)

    val rootBase = ex.get(LocalRootProject / baseDirectory)
    val absFile = {
      val f = file(rawPath)
      if (f.isAbsolute) f else rootBase / rawPath
    }

    val stateWithSettings = ex.appendWithoutSession(
      Seq(
        ThisBuild / useSuperShell             := false,
        node / Compile / run / outputStrategy := Some(StdoutOutput),
        node / Compile / run / logLevel       := Level.Error
      ),
      state
    )

    val input = s" tech.hearth.GenesisBlockGenerator ${absFile.getAbsolutePath}"

    Project
      .extract(stateWithSettings)
      .runInputTask(node / Compile / runMain, input, stateWithSettings)

    state
  }

commands ++= Seq(compilePR, checkPR, generateGenesisCommand)
