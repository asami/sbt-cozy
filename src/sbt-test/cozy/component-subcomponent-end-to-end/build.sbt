import java.nio.charset.StandardCharsets

import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val phase58FixtureRoot = settingKey[File]("Phase 58.8 isolated Component/Subcomponent fixture data root")
lazy val rsc09ResolveOnline = taskKey[Unit]("Resolve and verify all four RSC09 CAR dependencies online")
lazy val rsc09DeleteRepository = taskKey[Unit]("Delete the RSC09 fixture CAR repository")
lazy val rsc09ResolveOffline = taskKey[Unit]("Resolve and verify all four RSC09 CAR dependencies from cache")
lazy val rsc09ResolveProductionPrimaryOnly = taskKey[Unit]("Resolve and verify the RSC09 production primary CAR")

ThisBuild / resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven"

ThisBuild / phase58FixtureRoot := (ThisBuild / baseDirectory).value / "target" / "fixture"

def _require_file_(path: File, label: String): Unit =
  if (!path.isFile) sys.error(s"RSC09 fixture is missing $label: ${path.getAbsolutePath}")

def _rsc09_input_cars_(fixture: File): Seq[File] = Seq(
  fixture / "inputs" / "rsc-parent.car",
  fixture / "inputs" / "rsc-documentation.car",
  fixture / "inputs" / "rsc-source-code.car",
  fixture / "inputs" / "rsc-web-presentation.car"
)

def _rsc09_assert_resolved_(resolved: Seq[File], expected: Seq[File], label: String): Unit = {
  if (resolved.size != expected.size)
    sys.error(s"$label resolver returned ${resolved.size} CARs instead of ${expected.size}")
  resolved.zip(expected).foreach { case (actual, input) =>
    _require_file_(actual, s"$label resolved CAR")
    _require_file_(input, s"$label fixture input CAR")
    if (!java.util.Arrays.equals(IO.readBytes(actual), IO.readBytes(input)))
      sys.error(s"$label resolver bytes disagree with fixture input: resolved=$actual input=$input")
  }
}

lazy val publisherProbe = (project in file("publisher-probe"))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "phase58.fixture",
    name := "component-subcomponent-end-to-end-probe",
    version := "0.1.0",
    scalaVersion := "2.12.18",
    target := phase58FixtureRoot.value / "publisher-target",
    libraryDependencies += "org.simplemodeling" %% "cozy" % sys.props.getOrElse("cozy.version", "0.3.3-SNAPSHOT"),
    dependencyOverrides += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.3.0",
    evictionErrorLevel := Level.Warn,
    Compile / run / fork := true,
    Compile / run / connectInput := false,
    Compile / run / mainClass := Some("fixture.ComponentSubcomponentEndToEndProbe"),
    Compile / run / javaOptions += s"-Drsc09.fixture.root=${phase58FixtureRoot.value.getAbsolutePath}"
  )

lazy val resolverOnline = (project in file("resolver-online"))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "phase58.fixture",
    name := "component-subcomponent-end-to-end-online-resolver",
    version := "0.1.0",
    scalaVersion := "2.12.18",
    target := phase58FixtureRoot.value / "resolver-online-target",
    cozyCarDependencies := Seq(
      CarDependency("org.goldenport.cncf.phase58", "RscParent", "0.1.0-SNAPSHOT"),
      CarDependency("org.goldenport.cncf.phase58", "RscDocumentation", "0.1.0-SNAPSHOT"),
      CarDependency("org.goldenport.cncf.phase58", "RscSourceCode", "0.1.0-SNAPSHOT"),
      CarDependency("org.goldenport.cncf.phase58", "RscWebPresentation", "0.1.0-SNAPSHOT")
    ),
    cozyCarRepositories := Seq(
      (phase58FixtureRoot.value / "warehouse" / "repository" / "car").getAbsolutePath
    )
  )

lazy val root = (project in file("."))
  .aggregate(publisherProbe, resolverOnline)
  .settings(
    organization := "phase58.fixture",
    name := "component-subcomponent-end-to-end",
    version := "0.1.0",
    target := phase58FixtureRoot.value,
    rsc09ResolveOnline := {
      val fixture = phase58FixtureRoot.value
      val resolved = (resolverOnline / cozyResolvedCarFiles).value
      val inputs = _rsc09_input_cars_(fixture)
      _rsc09_assert_resolved_(resolved, inputs, "RSC09 online")
      val cacheroot = ((resolverOnline / target).value / "cozy" / "car-cache").getCanonicalFile.toPath
      resolved.foreach { actual =>
        if (!actual.getCanonicalFile.toPath.startsWith(cacheroot))
          sys.error(s"RSC09 online SNAPSHOT resolver did not capture CAR under cache root: $actual")
      }
      IO.write(
        fixture / "resolver-online-all.txt",
        resolved.map(_.getAbsolutePath).mkString("\n") + "\n",
        StandardCharsets.UTF_8
      )
    },
    rsc09DeleteRepository := {
      val repository = phase58FixtureRoot.value / "warehouse" / "repository" / "car"
      IO.delete(repository)
      if (repository.exists)
        sys.error(s"RSC09 repository deletion left source directory: ${repository.getAbsolutePath}")
    },
    rsc09ResolveOffline := {
      val fixture = phase58FixtureRoot.value
      val repository = fixture / "warehouse" / "repository" / "car"
      if (repository.exists)
        sys.error(s"RSC09 offline resolver repository is still present: ${repository.getAbsolutePath}")
      val resolved = (resolverOnline / cozyResolvedCarFiles).value
      _rsc09_assert_resolved_(resolved, _rsc09_input_cars_(fixture), "RSC09 offline")
      IO.write(
        fixture / "resolver-offline-all.txt",
        resolved.map(_.getAbsolutePath).mkString("\n") + "\n",
        StandardCharsets.UTF_8
      )
    },
    rsc09ResolveProductionPrimaryOnly := {
      val fixture = phase58FixtureRoot.value
      val repository = fixture / "warehouse" / "repository" / "car"
      if (repository.exists)
        sys.error(s"RSC09 production resolver repository is still present: ${repository.getAbsolutePath}")
      val resolved = (resolverOnline / cozyResolvedCarFiles).value
      val parent = Seq(fixture / "inputs" / "rsc-parent.car")
      _rsc09_assert_resolved_(resolved, parent, "RSC09 Production primary-only")
      IO.write(
        fixture / "resolver-production-primary.txt",
        resolved.map(_.getAbsolutePath).mkString("\n") + "\n",
        StandardCharsets.UTF_8
      )
    }
  )
