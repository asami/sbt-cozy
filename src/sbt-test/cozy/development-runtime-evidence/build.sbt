import org.goldenport.cozy.CozyPlugin.autoImport._

import java.nio.file.Files
import java.security.MessageDigest
import scala.util.parsing.json.JSON

def _runtime_classpath_sha256_(file: File): String =
  MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath)).map(byte => f"${byte & 0xff}%02x").mkString

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

lazy val verifyInitialRuntimeEvidence = taskKey[Unit](
  "Verify the initial stable development-runtime evidence projection."
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
    verifyInitialRuntimeEvidence := {
      val evidence = cozyRuntimeEvidenceFiles.value
      val classpath = target.value / "cncf.d" / "runtime-classpath.txt"
      val descriptor = target.value / "cncf.d" / "component-descriptor.json"
      val manifest = target.value / "cncf.d" / "car-runtime-manifest.json"
      if (evidence != Seq(classpath, descriptor, manifest) || !classpath.isFile || !descriptor.isFile || !manifest.isFile)
        sys.error(s"cozyPrepareRuntime did not produce the complete evidence projection: $evidence")
      val text = IO.read(manifest)
      if (!text.contains("cncf.car-development-runtime-manifest.v2") ||
          !text.contains("development-directory") ||
          !text.contains("target/cncf.d/component-descriptor.json") ||
          text.contains("target/scala"))
        sys.error(s"development manifest has the wrong stable-evidence shape: $manifest")
      val actualclasspathsha256 = _runtime_classpath_sha256_(classpath)
      if (_runtime_classpath_manifest_sha256_(manifest) != actualclasspathsha256)
        sys.error("development manifest does not retain the final runtime classpath digest")
      IO.write(target.value / "initial-runtime-manifest.json", text)
    },
    rewriteRuntimeContractEvidence := {
      val cardir = baseDirectory.value / "src" / "main" / "car"
      IO.write(
        cardir / "component-descriptor.json",
        """{"name":"development-runtime-evidence","version":"0.1.0-SNAPSHOT","component":"development-runtime-evidence","config":{"evidenceRevision":"2"}}"""
      )
      IO.write(
        cardir / "abi-manifest.json",
        """{"format":"cozy.car.abi-manifest.v1","car":{"name":"development-runtime-evidence","version":"0.1.0-SNAPSHOT"},"abi":{"exports":{"components":[{"name":"development-runtime-evidence"}]}}}"""
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
      val actualclasspathsha256 = _runtime_classpath_sha256_(classpath)
      if (_runtime_classpath_manifest_sha256_(target.value / "cncf.d" / "car-runtime-manifest.json") != actualclasspathsha256)
        sys.error("regenerated development manifest does not retain the final runtime classpath digest")
    }
  )
