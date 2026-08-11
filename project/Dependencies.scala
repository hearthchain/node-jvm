import sbt.Keys.scalaVersion
import sbt.{Def, *}
import scalapb.compiler.Version.scalapbVersion

object Dependencies {
  private def nettyModule(module: String) = "io.netty" % s"netty-$module" % "4.2.17.Final"

  val gProtoVersion = "4.35.1"
  val gProto        = "com.google.protobuf" % "protobuf-java" % Dependencies.gProtoVersion
  val overrides = Def.setting(
    Seq(
      "org.scala-lang"           %% "scala3-library" % scalaVersion.value,
      // monix 3.4.1 -> cats-effect 2.5.4 drags in cats-core 2.6.1, which predates
      // Either.raiseWhen/raiseUnless (added in cats 2.10.0). Force the version used by `lang`.
      "org.typelevel"            %% "cats-core"      % "2.13.0",
      "org.typelevel"            %% "cats-kernel"    % "2.13.0",
      "com.google.code.gson"      % "gson"           % "2.14.0",
      "com.squareup.okio"         % "okio-jvm"       % "3.17.0",
      "org.apache.httpcomponents" % "httpclient"     % "4.5.14",
      "org.slf4j"                 % "slf4j-api"      % "2.0.18",
      "org.msgpack"               % "msgpack-core"   % "0.9.12",
      nettyModule("codec-http2"),
      nettyModule("codec-http"),
      nettyModule("handler-proxy"),
      nettyModule("codec-socks"),
      nettyModule("transport-native-unix-common"),
      nettyModule("resolver-dns"),
      jacksonModule("core", "core"),
      jacksonModule("core", "databind"),
      jacksonModule("datatype", "datatype-jdk8"),
      jacksonModule("datatype", "datatype-jsr310"),
      "tools.jackson.core" % "jackson-databind" % "3.2.1",
      gProto
    )
  )

  // Node protobuf schemas
  lazy val protoSchemasLib =
    ("tech.hearth" % "protobuf-schemas" % "0.1.0-SNAPSHOT").classifier("protobuf-src").intransitive()

  private def pekkoModule(module: String) = "org.apache.pekko" %% s"pekko-$module" % "1.6.0"

  private def pekkoHttpModule(module: String, version: String = "1.4.0") = "org.apache.pekko" %% module % version

  private def kamonModule(module: String) = "io.kamon" %% s"kamon-$module" % "2.8.1"

  private def jacksonModule(group: String, module: String, version: String = "2.21.4") = s"com.fasterxml.jackson.$group" % s"jackson-$module" % version

  def monixModule(module: String): ModuleID = "io.monix" %% s"monix-$module" % "3.4.1"

  private def grpcModule(module: String) = "io.grpc" % module % "1.83.1"

  val pekkoHttp       = pekkoHttpModule("pekko-http")
  val googleGuava     = "com.google.guava"    % "guava"             % "33.6.0-jre"
  val kamonCore       = kamonModule("core")
  val machinist       = "org.typelevel"      %% "machinist"         % "0.6.8"
  val logback         = "ch.qos.logback"      % "logback-classic"   % "1.6.1"
  val asyncHttpClient = "org.asynchttpclient" % "async-http-client" % "3.0.13"
  val curve25519      = "com.wavesplatform"   % "curve25519-java"   % "0.6.6"
  val nettyHandler    = nettyModule("handler")

  val playJson = "org.playframework" %% "play-json" % "3.0.6"

  val scalaTest = "org.scalatest" %% "scalatest" % "3.2.20" % Test

  val cli = Seq("com.github.scopt" %% "scopt" % "4.1.0")

  def amazonCorretto(c: String): ModuleID = "software.amazon.cryptools" % "AmazonCorrettoCryptoProvider" % "2.5.0" classifier c

  val cryptoProviders = Seq(
    // Windows x86_64, Windows x86, macOS x86_64, linux x86_64
    "org.conscrypt" % "conscrypt-openjdk-uber" % "2.6.1",
    // macOS aarch64
    amazonCorretto("osx-aarch_64"),
    // fallback Java
    "org.bouncycastle" % "bcprov-jdk18on" % "1.85.2",
    "tech.hearth" % "crypto" % "0.1.0-SNAPSHOT"
  )

  lazy val it = scalaTest +: Seq(
    logback,
    "com.github.jnr" % "jnr-unixsocket" % "0.39.2", // To support Apple ARM
    "com.spotify"    % "docker-client"  % "8.16.0",
    jacksonModule("dataformat", "dataformat-properties", "2.22.1"),
    asyncHttpClient
  ).map(_ % Test)

  lazy val test = scalaTest +: Seq(
    logback,
    "org.scalatestplus" %% "scalacheck-1-16" % "3.2.14.0",
    "org.scalacheck"    %% "scalacheck"      % "1.19.0",
    "org.scalamock"     %% "scalamock"       % "7.5.5"
  ).map(_ % Test)

  lazy val logDeps = Seq(
    logback              % Runtime,
    pekkoModule("slf4j") % Runtime
  )

  // Check https://github.com/facebook/rocksdb/issues/13893 before bumping
  private val rocksdb = "org.rocksdb" % "rocksdbjni" % "10.10.1.1"

  val scalaLogging: ModuleID = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6"
  lazy val node = Def.setting(
    Seq(
      rocksdb,
      "commons-net"            % "commons-net"               % "3.13.0",
      "commons-io"             % "commons-io"                % "2.22.0",
      "com.github.pureconfig" %% "pureconfig-core"           % "0.17.10",
      "com.github.pureconfig" %% "pureconfig-generic-scala3" % "0.17.10",
      "net.logstash.logback"   % "logstash-logback-encoder"  % "9.0" % Runtime,
      kamonCore,
      kamonModule("pekko-http"),
      kamonModule("executors"),
      "org.influxdb" % "influxdb-java" % "2.25",
      googleGuava,
      "com.google.code.findbugs" % "jsr305" % "3.0.2" % Compile, // javax.annotation stubs
      playJson,
      pekkoModule("actor"),
      pekkoModule("stream"),
      pekkoHttp,
      "org.bitlet" % "weupnp" % "0.1.4",
      monixModule("reactive"),
      nettyHandler,
      scalaLogging,
      "eu.timepit"        %% "refined"  % "0.11.4" exclude ("org.scala-lang.modules", "scala-xml_2.13"),
      "com.esaulpaugh"     % "headlong" % "13.3.1",
      amazonCorretto("linux-x86_64") % Optional,
      amazonCorretto("linux-aarch_64") % Optional,
    ) ++ cryptoProviders ++ cli ++ logDeps ++ protobuf.value
  )

  lazy val nodeTests = Seq(
    pekkoModule("testkit"),
    pekkoHttpModule("pekko-http-testkit")
  ) ++ test ++ logDeps

  lazy val scalapbRuntime = Def.setting(
    Seq(
      gProto,
      gProto % "protobuf"
    )
  )

  lazy val protobuf = Def.setting {
    scalapbRuntime.value :+ protoSchemasLib % "protobuf"
  }

  lazy val grpc: Seq[ModuleID] = Seq(
    grpcModule("grpc-netty"),
    grpcModule("grpc-services"),
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapbVersion,
    protoSchemasLib         % "protobuf"
  )

  // https://github.com/sbt/sbt-javaagent#scopes
  // dist (only sbt-native-packager), because causes using logs before needed, so System.setProperty in RideRunnerWithPreparedStateApp has no effect.
  lazy val kanela =
    Seq("io.kamon" % "kanela-agent" % "2.0.0" % "dist")
}
