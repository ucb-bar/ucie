import Tests._

val chisel6Version = "6.7.0"
val chiselTestVersion = "6.0.0"
val scalaVersionFromChisel = "2.13.16"

ThisBuild / organization := "edu.berkeley.cs"
ThisBuild / version := "0.0.1-SNAPSHOT"

ThisBuild / scalaVersion := scalaVersionFromChisel
ThisBuild / scalacOptions := Seq(
  "-deprecation",
  "-feature",
  "-language:reflectiveCalls",
  "-Xcheckinit",
  "-Xlint"
)

Compile / doc / scalacOptions += "-groups"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/service/local/repositories/snapshots/content"

lazy val testchipip = ProjectRef(
  uri(
    "https://github.com/ucb-bar/testchipip.git#b337914c1204355c44b800da16c6c1880482592a"
  ),
  "testchipip"
)

val rocketchip =
  "edu.berkeley.cs" %% "rocketchip-6.0.0" % "1.6-6.0.0-1b9f43352-SNAPSHOT"

lazy val uciedigital = (project in file("."))
  .settings(
    name := "uciedigital",
    libraryDependencies ++=
      Seq(
        rocketchip,
        "org.chipsalliance" %% "chisel" % chisel6Version,
        "edu.berkeley.cs" %% "chiseltest" % chiselTestVersion % Test,
        "org.scalatest" %% "scalatest" % "3.2.18" % Test
      ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chisel6Version cross CrossVersion.full
    ),
    Test / fork := true,
    Test / testGrouping := (Test / testGrouping).value.flatMap { group =>
      import Tests._
      group.tests.map { test =>
        Group(test.name, Seq(test), SubProcess(ForkOptions()))
      }
    },
    concurrentRestrictions := Seq(Tags.limit(Tags.ForkedTestGroup, 72))
  )
  .dependsOn(testchipip)
