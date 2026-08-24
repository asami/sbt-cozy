import org.goldenport.cozy.CozyPlugin.autoImport._

import java.nio.file.Files
import java.security.MessageDigest
import scala.util.parsing.json.JSON

def _source_archive_json_object_(value: Any): Map[String, Any] =
  value match {
    case json: Map[_, _] => json.asInstanceOf[Map[String, Any]]
    case _ => sys.error("component source archive contains a non-object value")
  }

def _source_archive_entries_(file: File): Vector[(String, String)] =
  JSON.parseFull(IO.read(file)).flatMap {
    case root: Map[_, _] =>
      val objectroot = _source_archive_json_object_(root)
      objectroot.get("schema") match {
        case Some("cozy.component-source-archive.v1") =>
        case _ => sys.error("component source archive has an unsupported schema")
      }
      objectroot.get("entries").collect {
        case entries: List[_] => entries.toVector.map {
          case entry: Map[_, _] =>
            val objectentry = _source_archive_json_object_(entry)
            objectentry.get("path").collect { case path: String => path }.getOrElse(
              sys.error("component source archive entry lacks path")
            ) -> objectentry.get("sha256").collect { case digest: String => digest }.getOrElse(
              sys.error("component source archive entry lacks sha256")
            )
          case _ => sys.error("component source archive contains a non-object entry")
        }
      }.map(entries => objectroot -> entries)
    case _ => None
  }.map { case (root, entries) =>
    val expectedDigest = _source_archive_sha256_(entries.map {
      case (path, digest) => s"$path\t$digest\n"
    }.mkString.getBytes("UTF-8"))
    if (root.get("archiveDigest") != Some(expectedDigest))
      sys.error("component source archive digest does not match its structural entry inventory")
    entries
  }.getOrElse(sys.error("component source archive is invalid JSON"))

def _source_archive_sha256_(bytes: Array[Byte]): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

def _source_archive_file_sha256_(file: File): String =
  _source_archive_sha256_(Files.readAllBytes(file.toPath))

lazy val prepareSourceArchiveFixture = taskKey[Unit](
  "Prepare a build-only document that must not enter the source archive"
)
lazy val verifySourceArchiveProjection = taskKey[Unit](
  "Verify the normalized source archive projection"
)
lazy val rememberSourceArchive = taskKey[Unit](
  "Remember the first source archive for repeated-build comparison"
)
lazy val verifyRepeatedSourceArchiveProjection = taskKey[Unit](
  "Verify that repeated source archive projection is byte-for-byte stable"
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "component-source-archive-projection",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    prepareSourceArchiveFixture := {
      val generated = target.value / "generated.txt"
      IO.write(generated, "generated-build-only-documentation-bytes\n")
    },
    verifySourceArchiveProjection := {
      val archive = target.value / "cozy" / "component-source-archive.json"
      if (!archive.isFile)
        sys.error(s"Source archive projection was not created: $archive")
      if (!archive.getCanonicalFile.toPath.startsWith(target.value.getCanonicalFile.toPath))
        sys.error(s"Source archive projection escaped target: $archive")

      val content = IO.read(archive)
      val entries = _source_archive_entries_(archive)
      val expectedpaths = Vector("README.md", "build.sbt", "src/main/cozy/model.cml")
      val expectedentries = expectedpaths.map { path =>
        path -> _source_archive_file_sha256_(baseDirectory.value / path)
      }
      if (entries != expectedentries)
        sys.error(s"Source archive projection entries differ: expected=$expectedentries actual=$entries")
      if (!content.startsWith("{\"schema\":\"cozy.component-source-archive.v1\",\"archiveDigest\":"))
        sys.error("Source archive projection does not use the canonical root field order")
      if (content.contains("target/generated.txt") ||
          content.contains("generated-build-only-documentation-bytes") ||
          content.contains(baseDirectory.value.getCanonicalPath) ||
          content.contains("provenance"))
        sys.error(s"Source archive projection contains generated, host, or non-contract state: $archive")
    },
    rememberSourceArchive := {
      val archive = target.value / "cozy" / "component-source-archive.json"
      val snapshot = baseDirectory.value / "source-archive-first.json"
      if (!archive.isFile)
        sys.error(s"Cannot remember missing source archive projection: $archive")
      IO.copyFile(archive, snapshot)
    },
    verifyRepeatedSourceArchiveProjection := {
      val archive = target.value / "cozy" / "component-source-archive.json"
      val snapshot = baseDirectory.value / "source-archive-first.json"
      if (!snapshot.isFile)
        sys.error(s"First source archive snapshot was not created: $snapshot")
      if (!archive.isFile)
        sys.error(s"Repeated source archive projection was not created: $archive")
      val same = java.util.Arrays.equals(IO.readBytes(snapshot), IO.readBytes(archive))
      val firstentries = _source_archive_entries_(snapshot)
      val repeatedentries = _source_archive_entries_(archive)
      IO.delete(snapshot)
      if (!same || firstentries != repeatedentries)
        sys.error("Repeated source archive projection is not byte-identical and structurally equivalent")
    }
  )
