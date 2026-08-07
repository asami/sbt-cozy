import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.goldenport.cozy.CozyPlugin.autoImport._

import scala.collection.JavaConverters._

ThisBuild / resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven"

lazy val phase56FixtureRoot = settingKey[File]("Phase 56 isolated scripted-fixture data root")
lazy val startRepositorySource = taskKey[Unit]("Start the deterministic loopback CAR repository source")
lazy val stopRepositorySource = taskKey[Unit]("Stop the deterministic loopback CAR repository source")
lazy val verifyFixtureEvidence = taskKey[Unit]("Verify the complete namespace-qualified CAR repository evidence")
lazy val verifyConsumerOnline = taskKey[Unit]("Resolve both namespace-qualified CARs from the online source")
lazy val verifyConsumerOffline = taskKey[Unit]("Reuse both namespace-qualified CARs after source shutdown")

ThisBuild / phase56FixtureRoot := (ThisBuild / baseDirectory).value / "target" / "fixture"

def _write_marker_(path: File, value: String): Unit = {
  IO.createDirectory(path.getParentFile)
  IO.write(path, value + "\n", StandardCharsets.UTF_8)
}

def _required_file_(path: File, label: String): Unit =
  if (!path.isFile) sys.error(s"Phase 56 fixture is missing $label: ${path.getAbsolutePath}")

def _consumer_expected_(targetdir: File): Seq[File] = Seq(
  targetdir / "cozy" / "car-cache" / "org" / "alpha" / "textus" / "textus-shared" / "0.6.0" / "textus-shared-0.6.0.car",
  targetdir / "cozy" / "car-cache" / "org" / "beta" / "textus" / "textus-shared" / "0.6.0" / "textus-shared-0.6.0.car"
)

def _verify_consumer_files_(files: Seq[File], expected: Seq[File], label: String): Unit = {
  val actual = files.map(_.getAbsoluteFile.toPath.normalize()).toVector
  val wanted = expected.map(_.getAbsoluteFile.toPath.normalize()).toVector
  if (actual != wanted)
    sys.error(s"$label resolved namespace-qualified CAR paths disagree: actual=$actual expected=$wanted")
  if (files.map(_.getName).distinct.size != 1)
    sys.error(s"$label must retain the equal CAR filename projection")
  if (files.map(f => IO.readBytes(f).toVector).distinct.size != 2)
    sys.error(s"$label collapsed distinct namespace-qualified CAR payloads")
}

def _fixture_paths_(root: File): Seq[Path] =
  if (!root.isDirectory) Seq.empty
  else {
    val paths = Files.walk(root.toPath)
    try paths.iterator().asScala.toVector
    finally paths.close()
  }

def _debris_paths_(root: File): Seq[Path] = {
  val markers = Vector("-prepare-", "-rollback-", "snapshot-", "build-", "generation-provenance-", "archive-")
  _fixture_paths_(root).filter { path =>
    path.iterator().asScala.exists { segment =>
      val name = segment.toString
      name.endsWith(".tmp") || name.endsWith(".bak") || markers.exists(name.contains)
    }
  }
}

lazy val publisherProbe = (project in file("publisher-probe"))
  .settings(
    organization := "phase56.fixture",
    name := "namespace-qualified-car-publisher-probe",
    version := "0.1.0",
    scalaVersion := "2.12.18",
    target := phase56FixtureRoot.value / "publisher-target",
    libraryDependencies += "org.simplemodeling" %% "cozy" % sys.props.getOrElse("cozy.version", "0.3.1-SNAPSHOT"),
    dependencyOverrides += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.3.0",
    evictionErrorLevel := Level.Warn,
    Compile / run / fork := true,
    Compile / run / connectInput := false,
    Compile / run / mainClass := Some("fixture.PublisherProbe"),
    Compile / run / javaOptions += s"-Dphase56.fixture.root=${phase56FixtureRoot.value.getAbsolutePath}"
  )

lazy val consumer = (project in file("consumer"))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "phase56.fixture",
    name := "namespace-qualified-car-consumer",
    version := "0.1.0",
    scalaVersion := "2.12.18",
    target := phase56FixtureRoot.value / "consumer-target",
    cozyCarDependencies := Seq(
      CarDependency("org.alpha.textus", "Shared", "0.6.0"),
      CarDependency("org.beta.textus", "Shared", "0.6.0")
    ),
    cozyCarRepositories := Seq.empty,
    verifyConsumerOnline := {
      val files = cozyResolvedCarFiles.value
      _verify_consumer_files_(files, _consumer_expected_(target.value), "consumer online")
      _write_marker_(phase56FixtureRoot.value / "consumer-online.txt", files.map(_.getAbsolutePath).mkString("\n"))
    },
    verifyConsumerOffline := {
      if (FixtureRepositoryServer.running)
        sys.error("consumer offline verification requires the loopback source to be stopped")
      _required_file_(phase56FixtureRoot.value / "source-stopped.txt", "source-stopped marker")
      val files = cozyResolvedCarFiles.value
      _verify_consumer_files_(files, _consumer_expected_(target.value), "consumer offline")
      _write_marker_(phase56FixtureRoot.value / "consumer-offline.txt", files.map(_.getAbsolutePath).mkString("\n"))
    }
  )

lazy val cncfProbe = (project in file("cncf-probe"))
  .settings(
    organization := "phase56.fixture",
    name := "namespace-qualified-car-cncf-probe",
    version := "0.1.0",
    scalaVersion := "3.3.8",
    target := phase56FixtureRoot.value / "cncf-target",
    libraryDependencies += "org.goldenport" %% "goldenport-cncf" % sys.props.getOrElse("cncf.version", "0.5.2-SNAPSHOT"),
    libraryDependencies += "org.yaml" % "snakeyaml" % "2.4",
    Compile / run / fork := true,
    Compile / run / connectInput := false,
    Compile / run / mainClass := Some("fixture.CncfProbe"),
    Compile / run / javaOptions += s"-Dphase56.fixture.root=${phase56FixtureRoot.value.getAbsolutePath}"
  )

lazy val root = (project in file("."))
  .aggregate(publisherProbe, consumer, cncfProbe)
  .settings(
    organization := "phase56.fixture",
    name := "namespace-qualified-car-repository",
    version := "0.1.0",
    target := phase56FixtureRoot.value,
    startRepositorySource := {
      val fixture = phase56FixtureRoot.value
      val warehouse = fixture / "warehouse"
      IO.createDirectory(warehouse / "repository" / "car")
      IO.delete(fixture / "source-stopped.txt")
      val endpoint = FixtureRepositoryServer.start(warehouse)
      _write_marker_(fixture / "source-url.txt", endpoint)
    },
    stopRepositorySource := {
      FixtureRepositoryServer.stop()
      _write_marker_(phase56FixtureRoot.value / "source-stopped.txt", "stopped")
    },
    verifyFixtureEvidence := {
      val fixture = phase56FixtureRoot.value
      if (FixtureRepositoryServer.running)
        sys.error("final fixture verification requires the repository source to be stopped")
      _required_file_(fixture / "source-stopped.txt", "source-stopped marker")
      Vector(
        "publisher" -> (fixture / "publisher-evidence.txt"),
        "consumer-online" -> (fixture / "consumer-online.txt"),
        "consumer-offline" -> (fixture / "consumer-offline.txt"),
        "cncf-online" -> (fixture / "cncf-online.txt"),
        "cncf-offline" -> (fixture / "cncf-offline.txt")
      ).foreach { case (label, path) => _required_file_(path, s"$label evidence marker") }
      val alphapath = "/repository/car/org/alpha/textus/textus-shared/0.6.0/textus-shared-0.6.0.car"
      val betapath = "/repository/car/org/beta/textus/textus-shared/0.6.0/textus-shared-0.6.0.car"
      val indexpath = "/repository/catalog/index.json"
      val alphacatalogpath = "/repository/catalog/car/org/alpha/textus/textus-shared.yaml"
      val betacatalogpath = "/repository/catalog/car/org/beta/textus/textus-shared.yaml"
      val expectedcounts = Map(alphapath -> 2, betapath -> 2, indexpath -> 1, alphacatalogpath -> 1, betacatalogpath -> 1)
      if (FixtureRepositoryServer.counts != expectedcounts)
        sys.error(s"loopback request paths disagree: actual=${FixtureRepositoryServer.counts} expected=$expectedcounts")
      val expectedcars = (
        Seq(
          fixture / "publisher-projects" / "alpha" / "textus-shared-0.6.0.car",
          fixture / "publisher-projects" / "beta" / "textus-shared-0.6.0.car",
          fixture / "warehouse" / "repository" / "car" / "org" / "alpha" / "textus" / "textus-shared" / "0.6.0" / "textus-shared-0.6.0.car",
          fixture / "warehouse" / "repository" / "car" / "org" / "beta" / "textus" / "textus-shared" / "0.6.0" / "textus-shared-0.6.0.car"
        ) ++ _consumer_expected_(fixture / "consumer-target") ++
        Seq(
          fixture / "cncf-cache" / "org" / "alpha" / "textus" / "textus-shared" / "0.6.0" / "textus-shared-0.6.0.car",
          fixture / "cncf-cache" / "org" / "beta" / "textus" / "textus-shared" / "0.6.0" / "textus-shared-0.6.0.car"
        )
      ).map(_.getAbsoluteFile.toPath.normalize()).toSet
      val actualcars = _fixture_paths_(fixture).filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".car")).map(_.toAbsolutePath.normalize()).toSet
      if (actualcars != expectedcars)
        sys.error(s"fixture CAR outputs disagree: actual=$actualcars expected=$expectedcars")
      val debris = _debris_paths_(fixture)
      if (debris.nonEmpty)
        sys.error(s"fixture retains publication/cache debris: ${debris.mkString(", ")}")
      Vector("publish-car", "package-car").foreach { workname =>
        val work = fixture / "publisher-target" / "cozy" / "work" / workname
        if (work.isDirectory) {
          val children = Files.list(work.toPath)
          try if (children.iterator().hasNext)
            sys.error(s"publisher work directory is not empty: $work")
          finally children.close()
        }
      }
    }
  )
