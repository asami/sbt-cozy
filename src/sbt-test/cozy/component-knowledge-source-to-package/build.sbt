import org.goldenport.cozy.CozyPlugin.autoImport._

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import scala.collection.JavaConverters._
import scala.util.parsing.json.JSON

def _doc09_sha256_(bytes: Array[Byte]): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

def _doc09_json_object_(value: Any, context: String): Map[String, Any] =
  value match {
    case json: Map[_, _] => json.asInstanceOf[Map[String, Any]]
    case _ => sys.error(s"DOC09 $context is not a JSON object")
  }

def _doc09_json_(file: File, context: String): Map[String, Any] =
  JSON.parseFull(IO.read(file)).map(_doc09_json_object_(_, context)).getOrElse(
    sys.error(s"DOC09 $context is invalid JSON")
  )

def _doc09_required_string_(objectvalue: Map[String, Any], field: String, context: String): String =
  objectvalue.get(field).collect { case value: String => value }.getOrElse(
    sys.error(s"DOC09 $context lacks string field '$field'")
  )

def _doc09_carrier_declaration_(descriptor: File, context: String): Map[String, Any] =
  _doc09_json_(descriptor, context).get("componentKnowledge").map(
    _doc09_json_object_(_, s"$context componentKnowledge")
  ).getOrElse(sys.error(s"DOC09 $context lacks the generated Component knowledge carrier declaration"))

def _doc09_require_carrier_declaration_(declaration: Map[String, Any], digest: String, context: String): Unit = {
  val expected = Map[String, Any](
    "carrierSchema" -> "cncf.component-knowledge-carrier.v1",
    "consumerContractSchema" -> "cncf.component-knowledge-consumer.v1",
    "logicalPath" -> "component-knowledge.json",
    "sha256" -> digest
  )
  if (declaration != expected)
    sys.error(s"DOC09 $context has the wrong carrier declaration: $declaration")
}

def _doc09_runtime_evidence_(manifest: File): Vector[(String, String, String)] =
  _doc09_json_(manifest, "development runtime manifest").get("evidence").collect {
    case entries: List[_] => entries.toVector.map { entry =>
      val value = _doc09_json_object_(entry, "development runtime manifest evidence entry")
      (
        _doc09_required_string_(value, "path", "development runtime manifest evidence entry"),
        _doc09_required_string_(value, "sha256", "development runtime manifest evidence entry"),
        value.get("logicalSha256").collect { case logical: String => logical }.getOrElse("")
      )
    }
  }.getOrElse(sys.error("DOC09 development runtime manifest lacks evidence"))

def _doc09_runtime_evidence_digest_(manifest: File): String =
  _doc09_json_(manifest, "development runtime manifest").get("integrity").map(
    _doc09_json_object_(_, "development runtime manifest integrity")
  ).map(_doc09_required_string_(_, "evidenceSha256", "development runtime manifest integrity")).getOrElse(
    sys.error("DOC09 development runtime manifest lacks integrity evidence digest")
  )

def _doc09_assert_runtime_evidence_(manifest: File, digest: String): Unit = {
  val evidence = _doc09_runtime_evidence_(manifest)
  val carrier = evidence.filter(_._1 == "target/cncf.d/component-knowledge.json")
  if (carrier != Vector(("target/cncf.d/component-knowledge.json", digest, "")))
    sys.error(s"DOC09 development runtime manifest lacks exact Component knowledge target evidence: $carrier")
  val computed = _doc09_sha256_(evidence.map { case (path, sha256, logicalsha256) =>
    s"$path\t$sha256\t$logicalsha256"
  }.mkString("\n").getBytes(StandardCharsets.UTF_8))
  if (_doc09_runtime_evidence_digest_(manifest) != computed)
    sys.error("DOC09 development runtime aggregate evidence digest does not cover the Component knowledge entry")
}

def _doc09_zip_entry_bytes_(archive: File, path: String): Array[Byte] = {
  val zip = new ZipFile(archive)
  try {
    val matches = zip.entries.asScala.filter(_.getName == path).toVector
    if (matches.size != 1)
      sys.error(s"DOC09 packaged CAR must contain exactly one '$path' entry, found ${matches.size}")
    val stream = zip.getInputStream(matches.head)
    try stream.readAllBytes()
    finally stream.close()
  } finally zip.close()
}

def _doc09_zip_entry_names_(archive: File): Vector[String] = {
  val zip = new ZipFile(archive)
  try zip.entries.asScala.map(_.getName).toVector
  finally zip.close()
}

lazy val doc09TamperSourceConsumerIdentity = taskKey[Unit](
  "DOC09 failing-first: replace the source consumer Component identity before runtime preparation"
)
lazy val doc09RestoreSourceConsumerIdentity = taskKey[Unit](
  "DOC09 restore the source consumer contract bytes after the expected failing runtime preparation"
)
lazy val doc09VerifyDevelopmentEvidence = taskKey[Unit](
  "DOC09-AC-01 verify source-to-development byte, declaration, and runtime-inventory integrity"
)
lazy val doc09BuildAndVerifyPackage = taskKey[Unit](
  "DOC09-AC-01 verify source-to-packaged-CAR byte, declaration, and path integrity"
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "org.example.fixture",
    name := "component-knowledge-source-to-package",
    version := "0.1.0-SNAPSHOT",
    cozyManifestMetadata := Map(
      "component" -> "org.example.fixture.ComponentKnowledgeSourceToPackage",
      "namespace" -> "org.example.fixture",
      "id" -> "ComponentKnowledgeSourceToPackage",
      "qualifiedIdentity" -> "org.example.fixture.ComponentKnowledgeSourceToPackage"
    ),
    scalaVersion := "3.3.8",
    resolvers += "CNCF workspace Maven repository" at "file:///Users/asami/src/maven-repository/repository/maven",
    libraryDependencies += "org.goldenport" %% "goldenport-cncf" % "0.5.2",
    doc09TamperSourceConsumerIdentity := {
      val source = baseDirectory.value / "src" / "main" / "car" / "component-knowledge.json"
      val backup = target.value / "doc09" / "component-knowledge-original.json"
      val original = Files.readAllBytes(source.toPath)
      val expected = "org.example.fixture.ComponentKnowledgeSourceToPackage"
      val replacement = "org.example.fixture.NonmatchingComponentKnowledgeSourceToPackage"
      val text = new String(original, StandardCharsets.UTF_8)
      if (!text.contains(expected) || text.contains(replacement))
        sys.error("DOC09 cannot prepare the source consumer identity tamper from the expected contract bytes")
      IO.createDirectory(backup.getParentFile)
      Files.write(backup.toPath, original)
      Files.write(source.toPath, text.replace(expected, replacement).getBytes(StandardCharsets.UTF_8))
    },
    doc09RestoreSourceConsumerIdentity := {
      val source = baseDirectory.value / "src" / "main" / "car" / "component-knowledge.json"
      val backup = target.value / "doc09" / "component-knowledge-original.json"
      if (!backup.isFile)
        sys.error("DOC09 cannot restore source consumer contract bytes because the tamper backup is absent")
      Files.write(source.toPath, Files.readAllBytes(backup.toPath))
      Files.delete(backup.toPath)
    },
    doc09VerifyDevelopmentEvidence := {
      val sourcecarrier = baseDirectory.value / "src" / "main" / "car" / "component-knowledge.json"
      val sourcedescriptor = baseDirectory.value / "src" / "main" / "car" / "component-descriptor.json"
      val carrier = target.value / "cncf.d" / "component-knowledge.json"
      val descriptor = target.value / "cncf.d" / "component-descriptor.json"
      val manifest = target.value / "cncf.d" / "car-runtime-manifest.json"
      val sourcecontract = _doc09_json_(sourcecarrier, "source consumer contract")
      val sourcebytes = Files.readAllBytes(sourcecarrier.toPath)
      val digest = _doc09_sha256_(sourcebytes)
      if (_doc09_required_string_(sourcecontract, "schema", "source consumer contract") != "cncf.component-knowledge-consumer.v1" ||
          _doc09_required_string_(sourcecontract, "componentId", "source consumer contract") != "org.example.fixture.ComponentKnowledgeSourceToPackage" ||
          _doc09_required_string_(sourcecontract, "logicalRelease", "source consumer contract") != "0.1.0-SNAPSHOT")
        sys.error("DOC09 source consumer contract does not match the fixture Component identity and logical release")
      if (_doc09_json_(sourcedescriptor, "source descriptor").contains("componentKnowledge"))
        sys.error("DOC09 source descriptor must not author the Component knowledge declaration")
      if (!carrier.isFile || !descriptor.isFile || !manifest.isFile)
        sys.error("DOC09 cozyPrepareRuntime did not emit the exact Component knowledge evidence paths")
      if (!java.util.Arrays.equals(sourcebytes, Files.readAllBytes(carrier.toPath)))
        sys.error("DOC09 emitted Component knowledge carrier bytes differ from the exact contract source")
      _doc09_require_carrier_declaration_(_doc09_carrier_declaration_(descriptor, "development descriptor"), digest, "development descriptor")
      _doc09_assert_runtime_evidence_(manifest, digest)
    },
    doc09BuildAndVerifyPackage := {
      val archive = cozyBuildCar.value
      val sourcecarrier = baseDirectory.value / "src" / "main" / "car" / "component-knowledge.json"
      val sourcebytes = Files.readAllBytes(sourcecarrier.toPath)
      val digest = _doc09_sha256_(sourcebytes)
      if (!archive.isFile)
        sys.error("DOC09 canonical CAR package was not created")
      val entrynames = _doc09_zip_entry_names_(archive)
      if (entrynames.exists(name => name.startsWith("/") || name.contains("src/main/car/")))
        sys.error("DOC09 packaged CAR exposes a physical source path or an absolute project path")
      if (!java.util.Arrays.equals(sourcebytes, _doc09_zip_entry_bytes_(archive, "component-knowledge.json")))
        sys.error("DOC09 packaged CAR Component knowledge bytes differ from the exact contract source")
      val packageddescriptor = _doc09_zip_entry_bytes_(archive, "component-descriptor.json")
      val descriptorfile = target.value / "doc09" / "packaged-component-descriptor.json"
      IO.createDirectory(descriptorfile.getParentFile)
      Files.write(descriptorfile.toPath, packageddescriptor)
      _doc09_require_carrier_declaration_(_doc09_carrier_declaration_(descriptorfile, "packaged descriptor"), digest, "packaged descriptor")
      Files.delete(descriptorfile.toPath)
    }
  )
