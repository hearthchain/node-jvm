resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.sbtPluginRepo("releases")
)

// Pinned to an RC: 1.1.0-RC2 is the newest sbt-2-compatible build as of this writing, no stable
// sbt2 release exists yet. Bump to a stable release once sbt-protoc cuts one.
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.1.0-RC2")

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "compilerplugin" % "1.0.0-alpha.6",
  "org.slf4j"             % "slf4j-nop"      % "2.0.18"
)

Seq(
  "com.eed3si9n"       % "sbt-assembly"        % "2.4.1",
  "com.github.sbt"     % "sbt-git"             % "2.1.0",
  "com.github.sbt"     % "sbt-native-packager" % "1.11.7",
  "com.github.sbt"     % "sbt-pgp"             % "2.3.1",
  "org.scalameta"      % "sbt-scalafmt"        % "2.6.2",
  "pl.project13.scala" % "sbt-jmh"             % "0.4.8",
  "com.github.sbt"     % "sbt2-compat"         % "0.2.0"
).map(addSbtPlugin)

val dockerJavaVersion = "3.7.1"

libraryDependencies ++= Seq(
  "com.fasterxml.jackson.module" %% "jackson-module-scala"              % "2.22.1",
  "org.hjson"                     % "hjson"                             % "3.1.0",
  ("org.vafer"                    % "jdeb"                              % "1.14").artifacts(Artifact("jdeb", "jar", "jar")),
  "org.slf4j"                     % "jcl-over-slf4j"                    % "2.0.18",
  "com.github.docker-java"        % "docker-java-core"                  % dockerJavaVersion,
  "com.github.docker-java"        % "docker-java-transport-httpclient5" % dockerJavaVersion
)
