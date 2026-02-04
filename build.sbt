name := "uscis-case-tracker"
version := "0.1.0"
scalaVersion := "2.13.14"

// Enable fs2-grpc plugin and BuildInfo
enablePlugins(Fs2Grpc, BuildInfoPlugin)

// BuildInfo configuration
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoPackage := "uscis"
buildInfoOptions += BuildInfoOption.BuildTime

// Fork JVM for run to ensure proper Cats Effect IOApp behavior
Compile / run / fork := true

val http4sVersion = "0.23.25"
val circeVersion = "0.14.6"

libraryDependencies ++= Seq(
  // Cats Effect for functional programming
  "org.typelevel" %% "cats-effect" % "3.5.4",
  "org.typelevel" %% "cats-core" % "2.10.0",
  
  // HTTP Client (http4s)
  "org.http4s" %% "http4s-ember-client" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  
  // JSON (Circe)
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  
  // gRPC and Protobuf
  "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion,
  "io.grpc" % "grpc-services" % scalapb.compiler.Version.grpcJavaVersion,
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
  // Include well-known protobuf types (Timestamp, Duration, etc.)
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
  
  // Configuration
  "com.typesafe" % "config" % "1.4.3",
  
  // Logging
  "org.typelevel" %% "log4cats-slf4j" % "2.6.0",
  "ch.qos.logback" % "logback-classic" % "1.4.14",
  "org.slf4j" % "jul-to-slf4j" % "2.0.9", // Bridge JUL (used by gRPC/Netty) to SLF4J
  
  // Testing
  "org.scalatest" %% "scalatest" % "3.2.18" % Test,
  "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test
)

// Assembly configuration for fat jar
assembly / assemblyJarName := "uscis-case-tracker.jar"
assembly / mainClass := Some("uscis.Main")

// Merge strategy for assembly (handle duplicates)
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", _*) => MergeStrategy.discard
  case PathList("reference.conf") => MergeStrategy.concat
  case PathList("application.conf") => MergeStrategy.concat
  case PathList("logback.xml") => MergeStrategy.first
  case "module-info.class" => MergeStrategy.discard
  case x if x.endsWith(".proto") => MergeStrategy.first
  case x if x.endsWith(".class") => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}
