/* IDEA notes
 * May require to delete .idea and re-import with all checkboxes
 * Worksheets may not work: https://youtrack.jetbrains.com/issue/SCL-6726
 * To work with worksheets, make sure:
   1. You've selected the appropriate project
   2. You've checked "Make project before run"
 */

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

lazy val repl = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .settings(
    libraryDependencies ++= Dependencies.circe.value ++ Seq(
      Dependencies.protoSchemasLib % "protobuf"
    ),
    inConfig(Compile)(
      Seq(
        PB.targets += scalapb.gen(flatPackage = true) -> sourceManaged.value,
        PB.protoSources += PB.externalIncludePath.value,
        PB.generate / includeFilter := { (f: File) =>
          (** / "hearth" / "*.proto").matches(f.toPath)
        },
        PB.deleteTargetDirectory := false
      )
    )
  )

lazy val `waves-node` = (project in file("."))
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
    organization         := "com.wavesplatform",
    organizationName     := "Waves Platform",
    organizationHomepage := Some(url("https://wavesplatform.com")),
    licenses             := Seq(("MIT", url("https://github.com/wavesplatform/Waves/blob/master/LICENSE"))),
    publish / skip       := true,
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-language:postfixOps",
      "-Xmax-inlines",
      "50", // Required for FunctionalitySettings compilation
      "-Wunused:all",
      "-Wconf:cat=deprecation&origin=com.wavesplatform.api.grpc.*:s",                                // Ignore gRPC warnings
      "-Wconf:cat=deprecation&origin=com.wavesplatform.protobuf.transaction.InvokeScriptResult.*:s", // Ignore deprecated argsBytes
      "-Wconf:cat=deprecation&origin=com.wavesplatform.state.InvokeScriptResult.*:s",
      "-Wconf:cat=deprecation&origin=com\\.wavesplatform\\.(lang\\..*|JsApiUtils)&origin=com\\.wavesplatform\\.lang\\.v1\\.compiler\\.Terms\\.LET_BLOCK:s",
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
    testOptions += Tests.Setup(_ => sys.props("sbt-testing") = "true"),
    network := Network.default(),
    resolvers ++= Resolver.sonatypeCentralSnapshots +: Seq(Resolver.mavenLocal),
    Compile / packageDoc / publishArtifact := false,
    concurrentRestrictions                 := Seq(Tags.limit(Tags.Test, math.min(EvaluateTask.SystemProcessors, 8))),
    excludeLintKeys ++= Set(
      node / Universal / configuration,
      node / Linux / configuration,
      node / Debian / configuration,
      Global / maxParallelSuites
    )
  )
)

commands += Command.command("packageAll") { state =>
  "node / assembly" :: "buildDebPackages" :: "buildTarballsForDocker" :: state
}

lazy val buildTarballsForDocker = taskKey[Unit]("Package node and grpc-server tarballs and copy them to docker/target")
buildTarballsForDocker := {
  IO.copyFile(
    (node / Universal / packageZipTarball).value,
    baseDirectory.value / "docker" / "target" / "waves.tgz"
  )
  IO.copyFile(
    (`grpc-server` / Universal / packageZipTarball).value,
    baseDirectory.value / "docker" / "target" / "waves-grpc-server.tgz"
  )
}

lazy val compilePRRaw = taskKey[Unit]("Compile the project")
compilePRRaw := Def
  .sequential(
    clean.all(ScopeFilter(inAnyProject)),
    scalafmtCheck.all(ScopeFilter(inAnyProject, inConfigurations(Compile))),
    compile.all(ScopeFilter(inAnyProject, inConfigurations(Test)))
  )
  .value

lazy val checkPRRaw = taskKey[Unit]("Compile the project and run unit tests")
checkPRRaw := Def
  .sequential(
    compilePRRaw,
    Def.sequential(
      test.all(
        ScopeFilter(inProjects(`grpc-server`, `node-tests`), inConfigurations(Test))
      ),
      assembly.all(ScopeFilter(inProjects(node))),
      buildTarballsForDocker
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
buildPlatformIndependentArtifacts := {
  (node / assembly).value
  (`grpc-server` / Universal / packageZipTarball).value
}

commands += Command("buildReleaseArtifacts")(_ => Network.networkParser) { (state, args) =>
  args.toSet[Network].toList.flatMap { n =>
    s"set Global / network := $n" :: "buildDebPackages" :: Nil
  } ::: "buildPlatformIndependentArtifacts" :: state
}

/** Command: generateGenesis <path-to-config>
  * Runs: node / runMain com.wavesplatform.GenesisBlockGenerator <path>
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

    val input = s" com.wavesplatform.GenesisBlockGenerator ${absFile.getAbsolutePath}"

    Project
      .extract(stateWithSettings)
      .runInputTask(node / Compile / runMain, input, stateWithSettings)

    state
  }

commands ++= Seq(compilePR, checkPR, generateGenesisCommand)
