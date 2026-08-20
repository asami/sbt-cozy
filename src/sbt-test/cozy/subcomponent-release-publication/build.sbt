import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val phase58FixtureRoot = settingKey[File]("Phase 58.2 isolated subcomponent release fixture data root")

ThisBuild / resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven"

ThisBuild / phase58FixtureRoot := (ThisBuild / baseDirectory).value / "target" / "fixture"

lazy val publisherProbe = (project in file("publisher-probe"))
  .settings(
    organization := "phase58.fixture",
    name := "subcomponent-release-publication-probe",
    version := "0.1.0",
    scalaVersion := "2.12.18",
    target := phase58FixtureRoot.value / "publisher-target",
    libraryDependencies += "org.simplemodeling" %% "cozy" % sys.props.getOrElse("cozy.version", "0.3.3-SNAPSHOT"),
    dependencyOverrides += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.3.0",
    evictionErrorLevel := Level.Warn,
    Compile / run / fork := true,
    Compile / run / connectInput := false,
    Compile / run / mainClass := Some("fixture.SubcomponentReleasePublicationProbe"),
    Compile / run / javaOptions += s"-Dphase58.fixture.root=${phase58FixtureRoot.value.getAbsolutePath}"
  )

lazy val root = (project in file("."))
  .aggregate(publisherProbe)
  .settings(
    organization := "phase58.fixture",
    name := "subcomponent-release-publication",
    version := "0.1.0",
    target := phase58FixtureRoot.value
  )
