package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
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
final class CozyScaladocPackagingIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen {
  private val _component_namespace = "org.example.fixture"
  private val _component_id = "ComponentScaladocPackaging"
  private val _component_artifact = "fixture-component-scaladoc-packaging"
  private val _component_qualified_identity = s"${_component_namespace}.${_component_id}"

  "Current Cozy Scaladoc packaging bridge" should {
    "transport canonical public staging bytes into a CAR" in {
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

      _with_temp_dir("sbt-cozy-scaladoc-packaging") { projectroot =>
        Given(s"the current compiled Cozy runtime at $cozyroot and a minimal CAR project with public Scaladoc staging")
        val classpath = Files.readString(classpathfile, StandardCharsets.UTF_8).trim
        val delegatecommand = Seq("java", "-cp", classpath, "cozy.Cozy")
        val cncfversion = "0.5.17"
        val componentversion = "0.0.1-SNAPSHOT"
        val source = _write(
          projectroot.resolve("src/main/scala/fixture/PublicApi.scala"),
          "package fixture\nfinal class PublicApi\n"
        )
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
          _runtime_descriptor(cncfversion)
        )
        val staging = _write_scaladoc_staging(projectroot, source)
        val archive = projectroot.resolve(s"target/${_component_artifact}-${componentversion}.car")
        val abimanifestoutput = projectroot.resolve("target/cozy/abi-manifest.json")

        When("sbt-cozy sends the staged directory to current Cozy through the packaging bridge")
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
          scaladocdir = Some(staging.toFile)
        )

        Then("the CAR contains exactly the public staged entries and their original bytes")
        val stagedentries = _scaladoc_entries(staging)
        _zip_entries(archive).filter(_.startsWith("scaladoc/")) shouldBe
          (stagedentries.map { case (path, _) => s"scaladoc/$path" }.toSet +
            "scaladoc/scaladoc-manifest.json")
        stagedentries.foreach { case (path, _) =>
          _zip_bytes(archive, s"scaladoc/$path").toVector shouldBe
            Files.readAllBytes(staging.resolve(path)).toVector
        }
        _zip_bytes(archive, "scaladoc/scaladoc-manifest.json").toVector shouldBe
          Files.readAllBytes(staging.resolve("scaladoc-manifest.json")).toVector
        stagedentries.map(_._1) should contain allOf ("index.html", "index.js")
        stagedentries.map(_._1) should not contain "src-html/PublicApi.scala.html"
        Files.readString(staging.resolve("index.js"), StandardCharsets.UTF_8) should not include "source"
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

  private def _write_scaladoc_staging(projectroot: Path, source: Path): Path = {
    val staging = projectroot.resolve("target/cozy/scaladoc")
    _write(staging.resolve("index.html"), "<html><body>PublicApi</body></html>")
    _write(staging.resolve("index.js"), "window.searchIndex = ['PublicApi']")
    val entries = _scaladoc_entries(staging)
    val sourceentries = Vector(
      projectroot.relativize(source).toString.replace('\\', '/') -> _sha256(Files.readAllBytes(source))
    )
    val sourcedigest = _inventory_digest(sourceentries)
    val contentdigest = _inventory_digest(entries)
    val entriesjson = entries.map { case (path, digest) =>
      s"""{"path":"$path","sha256":"$digest"}"""
    }.mkString("[", ",", "]")
    _write(
      staging.resolve("scaladoc-manifest.json"),
      s"""{"schema":"cozy.component-scaladoc.v1","sourceDigest":"$sourcedigest","contentDigest":"$contentdigest","entries":$entriesjson}"""
    )
    staging
  }

  private def _scaladoc_entries(staging: Path): Vector[(String, String)] = {
    val stream = Files.walk(staging)
    try {
      stream.iterator().asScala.toVector.collect {
        case path if Files.isRegularFile(path) && path.getFileName.toString != "scaladoc-manifest.json" =>
          staging.relativize(path).toString.replace('\\', '/') -> _sha256(Files.readAllBytes(path))
      }.sortBy(_._1)
    } finally stream.close()
  }

  private def _inventory_digest(entries: Vector[(String, String)]): String =
    _sha256(entries.map { case (path, digest) => s"$path\t$digest\n" }.mkString.getBytes(StandardCharsets.UTF_8))

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

  private def _sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

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
