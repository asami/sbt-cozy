import sbt.ScriptedPlugin
import sbt.ScriptedPlugin.autoImport._

ThisBuild / organization := "org.goldenport"
ThisBuild / version := "0.1.17-SNAPSHOT"
ThisBuild / description := "sbt plugin for cozy/CML Scala source generation and CAR/SAR packaging"

ThisBuild / publishArtifact := true
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }

commands += Command.command("publishLocal") { state =>
  val extracted = Project.extract(state)
  val releaseversion = extracted.get(ThisBuild / version)
  if (!releaseversion.endsWith("-SNAPSHOT"))
    sys.error(s"publishLocal is prohibited for release version $releaseversion. Resolve published releases through the repository/cache path.")
  else {
    val (nextstate, _) = extracted.runTask(LocalRootProject / publishLocal, state)
    nextstate
  }
}

ThisBuild / publishTo := {
  val repo = sys.env.get("SIMPLEMODELING_MAVEN_LOCAL")
    .map(file)
    .getOrElse(baseDirectory.value / "maven-local")

  Some(
    Resolver.file(
      "local-simplemodeling-maven",
      repo
    )
  )
}

lazy val root = (project in file("."))
  .enablePlugins(ScriptedPlugin)
  .settings(
    name := "sbt-cozy",
    sbtPlugin := true,
    scalaVersion := "2.12.20",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.13",
      "io.circe" %% "circe-parser" % "0.14.13",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.scalacheck" %% "scalacheck" % "1.19.0" % Test
    ),
    scriptedBufferLog := false,
    scriptedLaunchOpts += s"-Dplugin.version=${version.value}"
  )
