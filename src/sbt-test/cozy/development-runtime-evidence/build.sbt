import org.goldenport.cozy.CozyPlugin.autoImport._

import java.nio.file.Files
import java.security.MessageDigest
import scala.util.parsing.json.JSON

def _runtime_classpath_sha256_(file: File): String =
  MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath)).map(byte => f"${byte & 0xff}%02x").mkString

def _sha256_(bytes: Array[Byte]): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

def _json_object_(value: Any): Map[String, Any] =
  value match {
    case json: Map[_, _] => json.asInstanceOf[Map[String, Any]]
    case _ => sys.error("development runtime manifest contains a non-object value")
  }

def _runtime_classpath_manifest_sha256_(file: File): String =
  JSON.parseFull(IO.read(file)).flatMap {
    case root: Map[_, _] =>
      _json_object_(root).get("evidence").collect {
        case entries: List[_] => entries.collectFirst {
          case entry: Map[_, _] if _json_object_(entry).get("path").contains("target/cncf.d/runtime-classpath.txt") =>
            _json_object_(entry).get("sha256") match {
              case Some(value: String) => value
              case _ => sys.error("development runtime manifest lacks the runtime classpath digest")
            }
        }.getOrElse(sys.error("development runtime manifest lacks runtime classpath evidence"))
      }
    case _ => None
  }.getOrElse(sys.error("development runtime manifest is invalid JSON"))

def _runtime_manifest_entry_sha256_(file: File, path: String): String =
  JSON.parseFull(IO.read(file)).flatMap {
    case root: Map[_, _] =>
      _json_object_(root).get("evidence").collect {
        case entries: List[_] => entries.collectFirst {
          case entry: Map[_, _] if _json_object_(entry).get("path").contains(path) =>
            _json_object_(entry).get("sha256") match {
              case Some(value: String) => value
              case _ => sys.error(s"development runtime manifest lacks the digest for $path")
            }
        }.getOrElse(sys.error(s"development runtime manifest lacks evidence for $path"))
      }
    case _ => None
  }.getOrElse(sys.error("development runtime manifest is invalid JSON"))

def _runtime_manifest_evidence_sha256_(file: File): String =
  JSON.parseFull(IO.read(file)).flatMap {
    case root: Map[_, _] =>
      _json_object_(root).get("integrity").collect {
        case integrity: Map[_, _] => _json_object_(integrity).get("evidenceSha256") match {
          case Some(value: String) => value
          case _ => sys.error("development runtime manifest lacks its evidence digest")
        }
      }
    case _ => None
  }.getOrElse(sys.error("development runtime manifest is invalid JSON"))

def _runtime_manifest_evidence_paths_(file: File): Vector[String] =
  JSON.parseFull(IO.read(file)).flatMap {
    case root: Map[_, _] =>
      _json_object_(root).get("evidence").collect {
        case entries: List[_] => entries.toVector.map {
          case entry: Map[_, _] =>
            _json_object_(entry).get("path") match {
              case Some(value: String) => value
              case _ => sys.error("development runtime manifest evidence lacks a path")
            }
          case _ => sys.error("development runtime manifest contains a non-object evidence entry")
        }
      }
    case _ => None
  }.getOrElse(sys.error("development runtime manifest is invalid JSON"))

def _computed_runtime_manifest_evidence_sha256_(file: File): String =
  JSON.parseFull(IO.read(file)).flatMap {
    case root: Map[_, _] =>
      _json_object_(root).get("evidence").collect {
        case entries: List[_] =>
          _sha256_(entries.map {
            case entry: Map[_, _] =>
              val objectvalue = _json_object_(entry)
              val path = objectvalue.get("path").collect { case value: String => value }.getOrElse(sys.error("development runtime evidence lacks path"))
              val digest = objectvalue.get("sha256").collect { case value: String => value }.getOrElse(sys.error("development runtime evidence lacks SHA-256"))
              val logical = objectvalue.get("logicalSha256").collect { case value: String => value }.getOrElse("")
              s"$path\t$digest\t$logical"
            case _ => sys.error("development runtime manifest contains a non-object evidence entry")
          }.mkString("\n").getBytes("UTF-8"))
      }
    case _ => None
  }.getOrElse(sys.error("development runtime manifest is invalid JSON"))

def _carrier_declaration_(file: File): Map[String, Any] =
  JSON.parseFull(IO.read(file)).flatMap {
    case root: Map[_, _] => _json_object_(root).get("componentKnowledge").collect {
      case declaration: Map[_, _] => _json_object_(declaration)
    }
    case _ => None
  }.getOrElse(sys.error("development descriptor lacks the generated Component knowledge declaration"))

lazy val verifyInitialRuntimeEvidence = taskKey[Unit](
  "Verify the initial stable development-runtime evidence projection."
)
lazy val removeSourceRuntimeCarrier = taskKey[Unit](
  "Given the source Component knowledge carrier exists, remove it and its generated target carrier for the absent-source runtime-evidence scenario."
)
lazy val restoreSourceRuntimeCarrier = taskKey[Unit](
  "Restore the original source Component knowledge carrier bytes after the absent-source runtime-evidence scenario."
)
lazy val verifyAbsentRuntimeEvidence = taskKey[Unit](
  "Given the source carrier is absent, verify cozyPrepareRuntime yields exactly runtime-classpath, descriptor, and manifest evidence with no target carrier or carrier manifest entry."
)
lazy val prepareNonFileRuntimeCarrier = taskKey[Unit](
  "Given carrier-present runtime evidence is prepared, replace only the generated target carrier with a directory so cozyRuntimeEvidenceFiles rejects it with '[sbt-cozy] development Component knowledge carrier is not a file'."
)
lazy val cleanupNonFileRuntimeCarrier = taskKey[Unit](
  "Remove the controlled non-file target carrier directory after the expected '[sbt-cozy] development Component knowledge carrier is not a file' failure."
)
lazy val rewriteRuntimeContractEvidence = taskKey[Unit](
  "Change a stable descriptor and ABI input so runtime evidence must regenerate."
)
lazy val verifyRegeneratedRuntimeEvidence = taskKey[Unit](
  "Verify that the development manifest was regenerated from changed contract evidence."
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "development-runtime-evidence",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.16",
    removeSourceRuntimeCarrier := {
      val sourcecarrier = baseDirectory.value / "src" / "main" / "car" / "component-knowledge.json"
      val targetcarrier = target.value / "cncf.d" / "component-knowledge.json"
      val backup = target.value / "development-runtime-evidence" / "component-knowledge-original.json"
      if (!sourcecarrier.isFile || backup.exists)
        sys.error("cannot prepare absent-source runtime evidence from the expected source carrier bytes")
      IO.createDirectory(backup.getParentFile)
      Files.write(backup.toPath, Files.readAllBytes(sourcecarrier.toPath))
      Files.delete(sourcecarrier.toPath)
      if (targetcarrier.exists)
        IO.delete(targetcarrier)
    },
    restoreSourceRuntimeCarrier := {
      val sourcecarrier = baseDirectory.value / "src" / "main" / "car" / "component-knowledge.json"
      val backup = target.value / "development-runtime-evidence" / "component-knowledge-original.json"
      if (!backup.isFile || sourcecarrier.exists)
        sys.error("cannot restore source carrier bytes because the absent-source backup or source state is invalid")
      Files.write(sourcecarrier.toPath, Files.readAllBytes(backup.toPath))
      Files.delete(backup.toPath)
    },
    verifyAbsentRuntimeEvidence := {
      val evidence = cozyRuntimeEvidenceFiles.value
      val classpath = target.value / "cncf.d" / "runtime-classpath.txt"
      val descriptor = target.value / "cncf.d" / "component-descriptor.json"
      val carrier = target.value / "cncf.d" / "component-knowledge.json"
      val manifest = target.value / "cncf.d" / "car-runtime-manifest.json"
      val sourcecarrier = baseDirectory.value / "src" / "main" / "car" / "component-knowledge.json"
      if (sourcecarrier.exists || evidence != Seq(classpath, descriptor, manifest) ||
          !classpath.isFile || !descriptor.isFile || !manifest.isFile || carrier.exists)
        sys.error(s"cozyPrepareRuntime did not produce the exact absent-source evidence projection: $evidence")
      if (_runtime_manifest_evidence_paths_(manifest).exists(_.contains("component-knowledge.json")))
        sys.error("development runtime manifest unexpectedly contains Component knowledge carrier evidence")
    },
    prepareNonFileRuntimeCarrier := {
      val carrier = target.value / "cncf.d" / "component-knowledge.json"
      if (!carrier.isFile)
        sys.error("cannot prepare the non-file carrier scenario without the ordinary carrier-present evidence")
      Files.delete(carrier.toPath)
      IO.createDirectory(carrier)
    },
    cleanupNonFileRuntimeCarrier := {
      val carrier = target.value / "cncf.d" / "component-knowledge.json"
      if (!carrier.isDirectory)
        sys.error("cannot clean up the non-file carrier scenario because its controlled directory is absent")
      IO.delete(carrier)
    },
    verifyInitialRuntimeEvidence := {
      val evidence = cozyRuntimeEvidenceFiles.value
      val classpath = target.value / "cncf.d" / "runtime-classpath.txt"
      val descriptor = target.value / "cncf.d" / "component-descriptor.json"
      val carrier = target.value / "cncf.d" / "component-knowledge.json"
      val manifest = target.value / "cncf.d" / "car-runtime-manifest.json"
      val sourcecarrier = baseDirectory.value / "src" / "main" / "car" / "component-knowledge.json"
      if (evidence != Seq(classpath, descriptor, carrier, manifest) || !classpath.isFile || !descriptor.isFile || !carrier.isFile || !manifest.isFile)
        sys.error(s"cozyPrepareRuntime did not produce the complete evidence projection: $evidence")
      val text = IO.read(manifest)
      if (!text.contains("cncf.car-development-runtime-manifest.v2") ||
          !text.contains("development-directory") ||
          !text.contains("target/cncf.d/component-descriptor.json") ||
          !text.contains("target/cncf.d/component-knowledge.json") ||
          text.contains("target/scala"))
        sys.error(s"development manifest has the wrong stable-evidence shape: $manifest")
      val actualclasspathsha256 = _runtime_classpath_sha256_(classpath)
      if (_runtime_classpath_manifest_sha256_(manifest) != actualclasspathsha256)
        sys.error("development manifest does not retain the final runtime classpath digest")
      val declaration = _carrier_declaration_(descriptor)
      val carrierdigest = _sha256_(Files.readAllBytes(sourcecarrier.toPath))
      if (declaration.get("carrierSchema") != Some("cncf.component-knowledge-carrier.v1") ||
          declaration.get("consumerContractSchema") != Some("cncf.component-knowledge-consumer.v1") ||
          declaration.get("logicalPath") != Some("component-knowledge.json") ||
          declaration.get("sha256") != Some(carrierdigest))
        sys.error("development descriptor does not retain the generated Component knowledge carrier declaration")
      if (Files.readAllBytes(carrier.toPath).toVector != Files.readAllBytes(sourcecarrier.toPath).toVector ||
          _runtime_manifest_entry_sha256_(manifest, "target/cncf.d/component-knowledge.json") != carrierdigest)
        sys.error("development runtime evidence does not bind the Component knowledge carrier bytes and digest")
      if (_runtime_manifest_evidence_sha256_(manifest) != _computed_runtime_manifest_evidence_sha256_(manifest))
        sys.error("development runtime manifest evidence digest does not cover the Component knowledge carrier")
      IO.write(target.value / "initial-runtime-manifest.json", text)
    },
    rewriteRuntimeContractEvidence := {
      val cardir = baseDirectory.value / "src" / "main" / "car"
      IO.write(
        cardir / "component-descriptor.json",
        """{"schemaVersion":3,"component":{"namespace":"org.example.fixture","id":"DevelopmentRuntimeEvidence","version":"0.1.0-SNAPSHOT"},"config":{"evidenceRevision":"2"}}"""
      )
      IO.write(
        cardir / "abi-manifest.json",
        """{"format":"cozy.car.abi-manifest.v2","component":{"namespace":"org.example.fixture","id":"DevelopmentRuntimeEvidence","version":"0.1.0-SNAPSHOT"},"abi":{"version":1,"exports":{"components":[{"namespace":"org.example.fixture","id":"DevelopmentRuntimeEvidence"}],"services":[],"operations":[],"types":[],"entities":[]},"dependencies":[]}}"""
      )
    },
    verifyRegeneratedRuntimeEvidence := {
      val initial = IO.read(target.value / "initial-runtime-manifest.json")
      val current = IO.read(target.value / "cncf.d" / "car-runtime-manifest.json")
      if (current == initial || !current.contains("0.1.0-SNAPSHOT"))
        sys.error("development runtime evidence was not regenerated from the changed stable contract inputs")
      val descriptor = IO.read(target.value / "cncf.d" / "component-descriptor.json")
      if (!descriptor.contains("evidenceRevision") || !descriptor.contains("\"2\""))
        sys.error("development descriptor was not regenerated from the changed stable contract inputs")
      val classpath = target.value / "cncf.d" / "runtime-classpath.txt"
      val carrier = target.value / "cncf.d" / "component-knowledge.json"
      val actualclasspathsha256 = _runtime_classpath_sha256_(classpath)
      if (_runtime_classpath_manifest_sha256_(target.value / "cncf.d" / "car-runtime-manifest.json") != actualclasspathsha256)
        sys.error("regenerated development manifest does not retain the final runtime classpath digest")
      val manifest = target.value / "cncf.d" / "car-runtime-manifest.json"
      val sourcecarrier = baseDirectory.value / "src" / "main" / "car" / "component-knowledge.json"
      val carrierdigest = _sha256_(Files.readAllBytes(sourcecarrier.toPath))
      if (!carrier.isFile || _runtime_manifest_entry_sha256_(manifest, "target/cncf.d/component-knowledge.json") != carrierdigest ||
          _runtime_manifest_evidence_sha256_(manifest) != _computed_runtime_manifest_evidence_sha256_(manifest))
        sys.error("regenerated development runtime manifest does not retain complete Component knowledge carrier evidence")
    }
  )
