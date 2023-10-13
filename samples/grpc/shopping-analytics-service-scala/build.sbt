import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.dockerBuildxPlatforms

name := "shopping-analytics-service"

organization := "com.lightbend.akka.samples"
organizationHomepage := Some(url("https://akka.io"))
licenses := Seq(
  ("CC0", url("https://creativecommons.org/publicdomain/zero/1.0")))

scalaVersion := "2.13.10"

Compile / scalacOptions ++= Seq(
  "-release:11",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlog-reflective-calls",
  "-Xlint")
Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation")

Test / parallelExecution := false
Test / testOptions += Tests.Argument("-oDF")
Test / logBuffered := false

run / fork := true
// pass along config selection to forked jvm
run / javaOptions ++= sys.props
  .get("config.resource")
  .fold(Seq.empty[String])(res => Seq(s"-Dconfig.resource=$res"))
Global / cancelable := false // ctrl-c

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

val AkkaVersion = "2.9.0-M3"
val AkkaHttpVersion = "10.6.0-M2"
val AkkaManagementVersion = "1.5.0-M2"
val AkkaPersistenceR2dbcVersion = "1.2.0-M5"
val AlpakkaKafkaVersion = "5.0.0-M1"
val AkkaProjectionVersion =
  sys.props.getOrElse("akka-projection.version", "1.5.0-M5")

enablePlugins(AkkaGrpcPlugin)

enablePlugins(JavaAppPackaging, DockerPlugin)
dockerBaseImage := "eclipse-temurin:17.0.3_7-jre-jammy"
dockerUsername := sys.props.get("docker.username")
dockerRepository := sys.props.get("docker.registry")
//dockerRepository := Some("ghcr.io")
dockerUpdateLatest := true
dockerBuildxPlatforms := Seq("linux/amd64")
ThisBuild / dynverSeparator := "-"

libraryDependencies ++= Seq(
  // 1. Basic dependencies for a clustered application
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
  // Akka Management powers Health Checks and Akka Cluster Bootstrapping
  "com.lightbend.akka.management" %% "akka-management" % AkkaManagementVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
  "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
  "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % AkkaManagementVersion,
  // Common dependencies for logging and testing
  "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.2.11",
  "org.scalatest" %% "scalatest" % "3.1.2" % Test,
  // 2. Using Akka Persistence
  "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
  "com.lightbend.akka" %% "akka-persistence-r2dbc" % AkkaPersistenceR2dbcVersion,
  "com.typesafe.akka" %% "akka-persistence-testkit" % AkkaVersion % Test,
  // 3. Querying or projecting data from Akka Persistence
  "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
  "com.lightbend.akka" %% "akka-projection-r2dbc" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-grpc" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-kafka" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-testkit" % AkkaProjectionVersion % Test,
  "org.hdrhistogram" % "HdrHistogram" % "2.1.12")
