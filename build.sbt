ThisBuild / organization := "org.goldenport"
ThisBuild / version := "0.1.5"
ThisBuild / description := "sbt plugin for cozy/CML Scala source generation and CAR/SAR packaging"

ThisBuild / publishArtifact := true
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }

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
  .settings(
    name := "sbt-cozy",
    sbtPlugin := true,
    scalaVersion := "2.12.20",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test
  )
