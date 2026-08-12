import sbt.nio.file.FileAttributes

name := "hearth-grpc-server"

libraryDependencies ++= Dependencies.grpc

extensionClasses ++= Seq(
  "tech.hearth.api.grpc.GRPCServerExtension",
  "tech.hearth.events.BlockchainUpdates"
)

inConfig(Compile)(
  Seq(
    Compile / PB.protoSources := Seq(PB.externalIncludePath.value),
    PB.generate / includeFilter := new SimpleFileFilter(
      (f: File) =>
        ((** / "hearth" / "node" / "grpc" / ** / "*.proto") || (** / "hearth" / "events" / ** / "*.proto"))
          .accept(f.toPath, FileAttributes(f.toPath).getOrElse(FileAttributes.NonExistent))
    ),
    PB.targets += PB.Target(scalapb.gen(flatPackage = true), sourceManaged.value)
  )
)

enablePlugins(RunApplicationSettings, ExtensionPackaging)
Universal / maintainer := "tech.hearth"
Debian / debianControlFile := {
  val generatedFile = (Debian / debianControlFile).value
  IO.append(generatedFile, s"""Conflicts: grpc-server${network.value.packageSuffix}
      |Replaces: grpc-server${network.value.packageSuffix}
      |""".stripMargin)
  generatedFile
}
