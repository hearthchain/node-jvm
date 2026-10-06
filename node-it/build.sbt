import sbt.*
import sbt.Keys.*

import scala.sys.process.*

enablePlugins(IntegrationTestsPlugin)

description := "NODE integration tests"
libraryDependencies ++= Dependencies.it

val docker = taskKey[Unit]("Build docker image for integration tests")
// Its output is the image in the docker daemon, invisible to ActionCache: a cache hit (persisted across CI runs by
// setup-java's sbt cache) skips the build on a fresh runner with no image (see "SBT 2 action-cache" in
// docs/notes/build-tooling.md).
docker := Def.uncached {
  val log = streams.value.log

  val cwd   = baseDirectory.value.getParentFile / "docker"
  val image = "hearth/node-it:latest"

  val cmd = Seq("docker", "build", "-t", image, ".")
  log.info(s"Running `${cmd.mkString(" ")}` from $cwd")

  val processLogger = ProcessLogger(
    (out: String) => log.info(out),
    (err: String) => log.info(err) // Redirect STDERR to info
  )

  val exit = Process(cmd, cwd).!(processLogger)
  if (exit != 0) sys.error(s"Docker build failed with exit code $exit")
}

val stageForDocker = taskKey[Unit]("stage node files into the docker build context")

// To solve "Error response from daemon: No such image: " see:
// https://github.com/marcus-drake/sbt-docker/issues/133#issuecomment-2718354260
docker := docker.dependsOn(LocalProject("hearth-node") / stageForDocker).value
