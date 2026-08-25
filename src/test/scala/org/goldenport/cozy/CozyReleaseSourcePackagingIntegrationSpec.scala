package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.zip.{ZipEntry, ZipFile, ZipOutputStream}

import scala.collection.JavaConverters._

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Aug. 25, 2026
 * @version Aug. 25, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyReleaseSourcePackagingIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen {
  private val _component_namespace = "org.example.fixture"
  private val _component_id = "ComponentReleaseSourcePackaging"
  private val _component_artifact = "fixture-component-release-source-packaging"
  private val _component_qualified_identity = s"${_component_namespace}.${_component_id}"

  "Current Cozy release-source packaging bridge" should {
    "stage canonical public source and transport it into a CAR" in {
      val classpathfile = sys.props.get("cozy.integration.classpath.file").
        map(value => Path.of(value)).
        filter(Files.isRegularFile(_)).
        getOrElse(cancel(
          "Set -Dcozy.integration.classpath.file to an exported current Cozy compile classpath"
        ))
      val cozyroot = sys.props.get("cozy.integration.root").
        map(value => Path.of(value)).
        filter(Files.isDirectory(_)).
        getOrElse(cancel(
          "Set -Dcozy.integration.root to the current Cozy project"
        ))
      val cozyversion = sys.props.get("cozy.integration.version").
        map(_.trim).
        filter(_.nonEmpty).
        getOrElse(cancel(
          "Set -Dcozy.integration.version to the current Cozy build version"
        ))

      _with_temp_dir("sbt-cozy-release-source-packaging") { projectroot =>
        Given(s"the current compiled Cozy runtime at $cozyroot and a minimal CAR project with public release-source policy")
        val classpath = Files.readString(classpathfile, StandardCharsets.UTF_8).trim
        val delegatecommand = Seq("java", "-cp", classpath, "cozy.Cozy")
        val cncfversion = "0.5.17"
        val componentversion = "0.0.1-SNAPSHOT"
        _write(
          projectroot.resolve("build.sbt"),
          "ThisBuild / organization := \"org.example.fixture\"\n"
        )
        val projectyaml = _write(
          projectroot.resolve("project.yaml"),
          _project_yaml(cozyversion, cncfversion, componentversion)
        )
        val cml = _write(
          projectroot.resolve("src/main/cozy/component.cml"),
          "component ComponentReleaseSourcePackaging\n"
        )
        val source = _write(
          projectroot.resolve("src/main/scala/fixture/PublicApi.scala"),
          "package fixture\nfinal class PublicApi\n"
        )
        val mainmanagedroot = projectroot.resolve("target/scala-3/src_managed/main")
        val mainmanaged = _write(
          mainmanagedroot.resolve("fixture/GeneratedMain.scala"),
          "package fixture\nfinal class GeneratedMain\n"
        )
        val testmanagedroot = projectroot.resolve("target/scala-3/src_managed/test")
        val testmanaged = _write(
          testmanagedroot.resolve("fixture/GeneratedTest.scala"),
          "package fixture\nfinal class GeneratedTest\n"
        )
        val abimanifest = _write(
          projectroot.resolve("src/main/car/abi-manifest.json"),
          _abi_manifest(componentversion)
        )
        val mainjar = _write_zip(
          projectroot.resolve("target/sample.jar"),
          "sample.txt",
          "sample\n"
        )
        val runtimejar = _write_zip(
          projectroot.resolve("target/goldenport-cncf_3.jar"),
          "META-INF/cncf/runtime.yaml",
          _runtime_descriptor(cncfversion)
        )
        val staging = projectroot.resolve("target/cozy/release-source")
        val archive = projectroot.resolve(s"target/${_component_artifact}-${componentversion}.car")
        val abimanifestoutput = projectroot.resolve("target/cozy/abi-manifest.json")

        When("sbt-cozy stages source and sends it to current Cozy through the packaging bridge")
        CozySbtBridge.writeComponentReleaseSource(
          projectdir = projectroot.toFile,
          output = staging.toFile,
          mode = "public",
          license = "Apache-2.0",
          managedmainsources = Seq(mainmanaged.toFile),
          managedmainroots = Seq(mainmanagedroot.toFile),
          managedtestsources = Seq(testmanaged.toFile),
          managedtestroots = Seq(testmanagedroot.toFile),
          compilescalacoptions = Seq("-deprecation"),
          testscalacoptions = Seq("-source:future"),
          dependencies = Seq(s"org.goldenport:goldenport-cncf:$cncfversion"),
          generators = Seq(s"org.simplemodeling:cozy:$cozyversion"),
          verifyexisting = false,
          basedir = projectroot.toFile,
          delegateprojectdir = None,
          delegatecommand = delegatecommand,
          log = sbt.util.Logger.Null
        )
        CozySbtBridge.packageCar(
          archive = archive.toFile,
          mainjar = mainjar.toFile,
          libjars = Seq(runtimejar.toFile),
          spijars = Seq.empty,
          componentapidescriptor = None,
          modelmetadata = Seq.empty,
          abimanifestoutput = abimanifestoutput.toFile,
          projectdir = projectroot.toFile,
          name = _component_artifact,
          version = componentversion,
          component = _component_qualified_identity,
          extensions = Map.empty,
          config = Map.empty,
          basedir = projectroot.toFile,
          delegateprojectdir = None,
          delegatecommand = delegatecommand,
          log = sbt.util.Logger.Null,
          releasesourcedir = Some(staging.toFile),
          releasesourceverification = Some(CozyReleaseSourceVerification(
            Seq(mainmanaged.toFile),
            Seq(mainmanagedroot.toFile),
            Seq(testmanaged.toFile),
            Seq(testmanagedroot.toFile),
            Seq("-deprecation"),
            Seq("-source:future"),
            Seq(s"org.goldenport:goldenport-cncf:$cncfversion"),
            Seq(s"org.simplemodeling:cozy:$cozyversion")
          ))
        )

        Then("the stage and CAR contain exactly the admitted source projection and canonical manifest")
        val expectedentries = Set(
          "build.sbt",
          "project.yaml",
          "src/main/cozy/component.cml",
          "src/main/car/abi-manifest.json",
          "src/main/scala/fixture/PublicApi.scala",
          "generated-source/main/fixture/GeneratedMain.scala",
          "generated-source/test/fixture/GeneratedTest.scala"
        )
        _stage_entries(staging) shouldBe expectedentries
        val manifesttext = Files.readString(
          staging.resolve("release-source-manifest.json"),
          StandardCharsets.UTF_8
        )
        manifesttext should startWith("{\"schema\":\"cozy.component-release-source.v1\"")
        manifesttext should not endWith "\n"
        manifesttext should not include "\n"
        manifesttext should not include projectroot.toAbsolutePath.normalize().toString
        _zip_entries(archive).filter(_.startsWith("source/")) shouldBe
          (expectedentries.map(path => s"source/$path") + "source/release-source-manifest.json")
        (expectedentries + "release-source-manifest.json").foreach { path =>
          _zip_bytes(archive, s"source/$path").toVector shouldBe
            Files.readAllBytes(staging.resolve(path)).toVector
        }
        Files.readAllBytes(staging.resolve("project.yaml")).toVector shouldBe
          Files.readAllBytes(projectyaml).toVector
        Files.readAllBytes(staging.resolve("src/main/cozy/component.cml")).toVector shouldBe
          Files.readAllBytes(cml).toVector
        Files.readAllBytes(staging.resolve("src/main/car/abi-manifest.json")).toVector shouldBe
          Files.readAllBytes(abimanifest).toVector
        Files.readAllBytes(staging.resolve("src/main/scala/fixture/PublicApi.scala")).toVector shouldBe
          Files.readAllBytes(source).toVector
        Files.readAllBytes(staging.resolve("generated-source/main/fixture/GeneratedMain.scala")).toVector shouldBe
          Files.readAllBytes(mainmanaged).toVector
        Files.readAllBytes(staging.resolve("generated-source/test/fixture/GeneratedTest.scala")).toVector shouldBe
          Files.readAllBytes(testmanaged).toVector
      }
    }
  }

  private def _project_yaml(
    cozyversion: String,
    cncfversion: String,
    componentversion: String
  ): String =
    s"""project:
       |  namespace: ${_component_namespace}
       |  id: ${_component_id}
       |  kind: car
       |  name: ${_component_artifact}
       |  component:
       |    version: $componentversion
       |build:
       |  cozyVersion: $cozyversion
       |  dependencies:
       |    compile:
       |      - org.goldenport::goldenport-cncf:$cncfversion
       |packaging:
       |  kind: car
       |  car:
       |    release_source:
       |      mode: public
       |      license: Apache-2.0
       |    manifest_metadata:
       |      component: ${_component_qualified_identity}
       |      namespace: ${_component_namespace}
       |      id: ${_component_id}
       |      qualifiedIdentity: ${_component_qualified_identity}
       |    runtime:
       |      cncf:
       |        minimum: $cncfversion
       |        excluded: []
       |        tested:
       |          - $cncfversion
       |""".stripMargin

  private def _runtime_descriptor(cncfversion: String): String =
    s"""schemaVersion: 1
       |runtime: cncf
       |version: $cncfversion
       |module: org.goldenport:goldenport-cncf_3:$cncfversion
       |predefinedResults:
       |  schemaVersion: cncf.predefined-result.v1
       |  resultNames: []
       |""".stripMargin

  private def _abi_manifest(componentversion: String): String =
    s"""{
       |  "format": "cozy.car.abi-manifest.v2",
       |  "component": {
       |    "namespace": "${_component_namespace}",
       |    "id": "${_component_id}",
       |    "version": "$componentversion"
       |  },
       |  "abi": {
       |    "version": 1,
       |    "exports": {
       |      "components": [
       |        {
       |          "namespace": "${_component_namespace}",
       |          "id": "${_component_id}"
       |        }
       |      ],
       |      "operations": [],
       |      "entities": []
       |    },
       |    "dependencies": []
       |  }
       |}
       |""".stripMargin

  private def _stage_entries(staging: Path): Set[String] = {
    val stream = Files.walk(staging)
    try stream.iterator().asScala.collect {
      case path if Files.isRegularFile(path) && path.getFileName.toString != "release-source-manifest.json" =>
        staging.relativize(path).toString.replace('\\', '/')
    }.toSet
    finally stream.close()
  }

  private def _write(path: Path, content: String): Path = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(path, content, StandardCharsets.UTF_8)
    path.toAbsolutePath.normalize()
  }

  private def _write_zip(path: Path, entryname: String, content: String): Path = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    val output = new ZipOutputStream(Files.newOutputStream(path))
    try {
      output.putNextEntry(new ZipEntry(entryname))
      output.write(content.getBytes(StandardCharsets.UTF_8))
      output.closeEntry()
    } finally output.close()
    path.toAbsolutePath.normalize()
  }

  private def _zip_entries(path: Path): Set[String] = {
    val zip = new ZipFile(path.toFile)
    try zip.entries.asScala.map(_.getName).toSet
    finally zip.close()
  }

  private def _zip_bytes(path: Path, entryname: String): Array[Byte] = {
    val zip = new ZipFile(path.toFile)
    try {
      val entry = Option(zip.getEntry(entryname)).getOrElse(fail(s"missing ZIP entry: $entryname"))
      val input = zip.getInputStream(entry)
      try input.readAllBytes()
      finally input.close()
    } finally zip.close()
  }

  private def _with_temp_dir[A](prefix: String)(body: Path => A): A = {
    val directory = Files.createTempDirectory(prefix)
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try stream.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally stream.close()
    }
  }
}
