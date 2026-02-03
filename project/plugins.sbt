// gRPC code generation from protobuf
addSbtPlugin("org.typelevel" % "sbt-fs2-grpc" % "2.7.4")

// Generate BuildInfo object with version and build metadata
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")

// Create fat/uber jar for deployment
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.1.5")
