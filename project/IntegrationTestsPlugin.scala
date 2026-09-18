import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientImpl}
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import sbt.*
import sbt.Keys.*
import sbt.Tests.Group

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.control.NonFatal

// Separate projects for integration tests because of IDEA: https://youtrack.jetbrains.com/issue/SCL-14363#focus=streamItem-27-3061842.0-0
object IntegrationTestsPlugin extends AutoPlugin {

  object autoImport extends ItKeys
  import autoImport.*

  override def projectSettings: Seq[Def.Setting[?]] =
    inConfig(Test)(
      Seq(
        logDirectory := Def.uncached {
          val runId = Option(System.getenv("RUN_ID")).getOrElse {
            val formatter = DateTimeFormatter.ofPattern("MM-dd--HH_mm_ss")
            formatter.format(LocalDateTime.now()) // git branch?
          }
          val r = target.value / "logs" / runId
          IO.createDirectory(r)
          r
        },
        // Example: SCALATEST_EXCLUDE_TAGS="package1.Tag1 package2.Tag2 package3.Tag3"
        testOptions += {
          val excludeTags = sys.env.get("SCALATEST_EXCLUDE_TAGS").fold(Seq.empty[String])(Seq("-l", _))
          /* http://www.scalatest.org/user_guide/using_the_runner
           * f - select the file reporter with output directory
           * F - show full stack traces
           * W - without color
           */
          val args = Seq("-fFW", (logDirectory.value / "summary.log").toString) ++ excludeTags
          Tests.Argument(TestFrameworks.ScalaTest, args*)
        },
        // There is no include-by-tag counterpart to SCALATEST_EXCLUDE_TAGS's `-l`: ScalaTest's sbt Framework parses
        // `-n` and then ignores it (verified with a tag no suite carries - nothing was filtered), so the load-test
        // half of the split selects its suites by name instead, read off the annotation so the list cannot drift.
        loadTestNames := Def.uncached {
          val loader = testLoader.value
          val loadTest = Class
            .forName("tech.hearth.it.LoadTest", false, loader)
            .asSubclass(classOf[java.lang.annotation.Annotation])
          definedTests.value.map(_.name).filter(Class.forName(_, false, loader).isAnnotationPresent(loadTest)).sorted
        },
        loadTests := Def.taskDyn {
          loadTestNames.value match {
            case Nil   => Def.task(sys.error("No @LoadTest suites found - has the annotation moved?"))
            case names => testOnly.toTask(names.mkString(" ", " ", "")).map(_ => ())
          }
        }.value,
        parallelExecution := true,
        testGrouping := Def.uncached {
          // ffs, sbt!
          // https://github.com/sbt/sbt/issues/3266
          val javaHomeValue     = javaHome.value
          val logDirectoryValue = logDirectory.value
          val envVarsValue      = envVars.value
          val javaOptionsValue  = javaOptions.value

          for {
            group <- testGrouping.value
            suite <- group.tests
          } yield Group(
            suite.name,
            Seq(suite),
            Tests.SubProcess(
              ForkOptions(
                javaHome = javaHomeValue,
                outputStrategy = outputStrategy.value,
                bootJars = Vector.empty[java.io.File],
                workingDirectory = Option(baseDirectory.value),
                runJVMOptions = Vector(
                  "-Dhearth.it.logging.appender=FILE",
                  "--enable-native-access=ALL-UNNAMED",
                  s"-Dhearth.it.logging.dir=${logDirectoryValue / suite.name.replaceAll("""(\w)\w*\.""", "$1.")}" // foo.bar.Baz -> f.b.Baz
                ) ++ javaOptionsValue,
                connectInput = false,
                envVars = envVarsValue
              )
            )
          )
        }
      )
    ) ++ inScope(Global)(
      Seq(
        // Only the setting lives here; `concurrentRestrictions` that reads it is assigned in build.sbt, because a
        // second assignment of that Global key would replace the first instead of adding to it.
        maxParallelSuites := Option(Integer.getInteger("hearth.it.max-parallel-suites"))
          .getOrElse[Integer] {
            try {
              val config       = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
              val httpClient   = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost).build()
              val dockerClient = DockerClientImpl.getInstance(config, httpClient)
              try {
                val dockerCpu = dockerClient.infoCmd().exec().getNCPU
                sLog.value.info(s"Docker CPU count: $dockerCpu")
                dockerCpu * 2
              } finally {
                httpClient.close()
                dockerClient.close()
              }
            } catch {
              case NonFatal(e) =>
                sLog.value.warn(s"Could not connect to Docker, is the daemon running? ${e.getMessage}")
                sLog.value.info(s"System CPU count: ${EvaluateTask.SystemProcessors}")
                EvaluateTask.SystemProcessors
            }
          }
      )
    )

}

trait ItKeys {
  val logDirectory      = taskKey[File]("The directory where logs of integration tests are written")
  val maxParallelSuites = settingKey[Int]("Number of test suites to run in parallel")
  val loadTestNames     = taskKey[Seq[String]]("Names of the @LoadTest-annotated suites")
  val loadTests         = taskKey[Unit]("Runs only the @LoadTest-annotated suites")
}
