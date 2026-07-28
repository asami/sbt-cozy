package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Comparator
import java.util.zip.{ZipEntry, ZipFile, ZipOutputStream}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._
import scala.sys.process._

/*
 * @since   Jul. 28, 2026
 * @version Jul. 28, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyGenerationProvenanceIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen {
  "Actual Cozy bridge provenance integration" should {
    "generate, validate, rebind, and package final project evidence through sbt-cozy" in {
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

      _with_temp_dir("sbt-cozy-real-provenance") { projectroot =>
        Given("the current compiled Cozy runtime and one CNCF-aware CML CAR project")
        val classpath = Files.readString(classpathfile, StandardCharsets.UTF_8).trim
        val delegatecommand = Seq("java", "-cp", classpath, "cozy.Cozy")
        val cncfversion = "0.5.17"
        val componentversion = "0.0.1-SNAPSHOT"
        val sourceidentity = "src/main/cozy/derived-attributes.cml"
        val source = _write(
          projectroot.resolve(sourceidentity),
          Files.readString(
            cozyroot.resolve("src/test/resources/modeler/derived-attributes.cml"),
            StandardCharsets.UTF_8
          )
        )
        val descriptorcontent = _runtime_descriptor(cncfversion)
        val descriptor = _write(
          projectroot.resolve("target/runtime.yaml"),
          descriptorcontent
        )
        val descriptordigest = _sha256(descriptor)
        _write(
          projectroot.resolve("project.yaml"),
          _project_yaml(cozyversion, cncfversion, componentversion)
        )
        _write(
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
          descriptorcontent
        )
        val targetbasedir = projectroot.resolve("target").toFile
        val targetdir =
          (targetbasedir / "scala-2.12" / "src_managed" / "main")
        val archive = projectroot.resolve(s"target/sample-$componentversion.car")

        When("sbt-cozy runs generation, the authoritative validator, and CAR packaging through actual Cozy")
        val generated = CozyDelegatedGenerator.generate(
          sourcedir = source.getParent.toFile,
          cozyfiles = Seq(source.toFile),
          targetdir = targetdir,
          targetbasedir = targetbasedir,
          basedir = projectroot.toFile,
          delegateprojectdir = None,
          delegatecommand = delegatecommand,
          settings = Map(
            "generation.versions.cncf" -> cncfversion,
            "runtime.cncf.descriptor" -> descriptor.toString,
            "runtime.cncf.descriptor.sha256" -> descriptordigest
          ),
          log = sbt.util.Logger.Null
        )
        val provenance =
          projectroot.resolve("target/cozy/generation-provenance.json")
        val validationexit = Process(
          delegatecommand ++ Seq(
            "generation-provenance-validate",
            source.toString,
            "--save",
            projectroot.toString,
            "--cncf-version",
            cncfversion,
            "--cncf-runtime-descriptor-sha256",
            descriptordigest,
            "--cozy-generator-version",
            cozyversion,
            "--generation-source-identity",
            sourceidentity,
            "--generation-source-sha256",
            _sha256(source)
          ),
          projectroot.toFile
        ).!
        CozySbtBridge.packageCar(
          archive = archive.toFile,
          mainjar = mainjar.toFile,
          libjars = Seq(runtimejar.toFile),
          spijars = Seq.empty,
          componentapidescriptor = None,
          modelmetadata = Seq.empty,
          abimanifestoutput =
            projectroot.resolve("target/cozy/abi-manifest.json").toFile,
          projectdir = projectroot.toFile,
          name = "sample",
          version = componentversion,
          component = "sample",
          extensions = Map.empty,
          config = Map.empty,
          basedir = projectroot.toFile,
          delegateprojectdir = None,
          delegatecommand = delegatecommand,
          log = sbt.util.Logger.Null
        )

        Then("the installed evidence is valid and the CAR preserves its exact bytes")
        generated should not be empty
        generated.foreach(_.isFile shouldBe true)
        validationexit shouldBe 0
        Files.isRegularFile(provenance) shouldBe true
        Files.exists(projectroot.resolve("target/sbt-cozy/delegate-work")) shouldBe false
        _zip_text(archive, "generation-provenance.json") shouldBe
          Files.readString(provenance, StandardCharsets.UTF_8)
      }
    }
  }

  private def _runtime_descriptor(cncfversion: String): String =
    s"""schemaVersion: 1
       |runtime: cncf
       |version: $cncfversion
       |module: org.goldenport:goldenport-cncf_3:$cncfversion
       |predefinedResults:
       |  schemaVersion: cncf.predefined-result.v1
       |  resultNames: []
       |""".stripMargin

  private def _project_yaml(
    cozyversion: String,
    cncfversion: String,
    componentversion: String
  ): String =
    s"""project:
       |  name: sample
       |  kind: car
       |  organization: com.example
       |  scalaPackage: domain
       |  component:
       |    name: sample
       |    className: domain.SampleComponent
       |    version: $componentversion
       |build:
       |  cozyVersion: $cozyversion
       |  dependencies:
       |    compile:
       |      - org.goldenport::goldenport-cncf:$cncfversion
       |packaging:
       |  kind: car
       |  car:
       |    runtime:
       |      cncf:
       |        minimum: $cncfversion
       |        excluded: []
       |        tested:
       |          - $cncfversion
       |""".stripMargin

  private def _abi_manifest(componentversion: String): String =
    s"""{
       |  "format": "cozy.car.abi-manifest.v1",
       |  "car": {
       |    "name": "sample",
       |    "version": "$componentversion"
       |  },
       |  "abi": {
       |    "version": 1,
       |    "exports": {
       |      "components": [
       |        {
       |          "name": "sample"
       |        }
       |      ],
       |      "operations": [],
       |      "entities": []
       |    },
       |    "dependencies": []
       |  }
       |}
       |""".stripMargin

  private def _write(path: Path, content: String): Path = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(path, content, StandardCharsets.UTF_8)
    path.toAbsolutePath.normalize()
  }

  private def _write_zip(
    path: Path,
    entryname: String,
    content: String
  ): Path = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    val output = new ZipOutputStream(Files.newOutputStream(path))
    try {
      output.putNextEntry(new ZipEntry(entryname))
      output.write(content.getBytes(StandardCharsets.UTF_8))
      output.closeEntry()
    } finally {
      output.close()
    }
    path.toAbsolutePath.normalize()
  }

  private def _zip_text(path: Path, entryname: String): String = {
    val zip = new ZipFile(path.toFile)
    try {
      val entry = Option(zip.getEntry(entryname)).getOrElse {
        fail(s"missing ZIP entry: $entryname")
      }
      val input = zip.getInputStream(entry)
      try new String(input.readAllBytes(), StandardCharsets.UTF_8)
      finally input.close()
    } finally {
      zip.close()
    }
  }

  private def _sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256").
      digest(Files.readAllBytes(path)).
      map(byte => f"${byte & 0xff}%02x").
      mkString

  private def _with_temp_dir[A](prefix: String)(body: Path => A): A = {
    val directory = Files.createTempDirectory(prefix)
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try
        stream.sorted(Comparator.reverseOrder()).
          forEach(path => Files.deleteIfExists(path))
      finally stream.close()
    }
  }
}
