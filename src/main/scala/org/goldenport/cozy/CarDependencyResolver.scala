package org.goldenport.cozy

import java.net.URI
import java.nio.file.{Files, Path, StandardCopyOption}

import scala.util.control.NonFatal

import sbt._

/*
 * @since   Jul. 12, 2026
 * @version Jul. 12, 2026
 * @author  ASAMI, Tomoharu
 */
final case class CarDependency(name: String, version: String)

private[cozy] object CarDependencyResolver {
  private val _connect_timeout_ms = 3000
  private val _read_timeout_ms = 10000

  def resolve(
    dependency: CarDependency,
    repositories: Seq[String],
    cachedir: File
  ): File = {
    val filename = s"${dependency.name}-${dependency.version}.car"
    repositories.iterator.flatMap { repository =>
      _resolve_repository(repository, dependency, filename, cachedir).iterator
    }.toSeq.headOption.getOrElse {
      sys.error(
        s"[sbt-cozy] CAR dependency not found: ${dependency.name}:${dependency.version}; searched ${repositories.mkString(", ")}"
      )
    }
  }

  private def _resolve_repository(
    repository: String,
    dependency: CarDependency,
    filename: String,
    cachedir: File
  ): Option[File] = {
    val normalized = repository.trim.stripSuffix("/")
    if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
      if (_is_snapshot(dependency.version)) None
      else _download(normalized, dependency, filename, cachedir)
    } else {
      val root = if (normalized.startsWith("file:")) new java.io.File(new URI(normalized)) else file(normalized)
      val candidate = root / dependency.name / dependency.version / filename
      Option(candidate).filter(_.isFile)
    }
  }

  private def _download(
    repository: String,
    dependency: CarDependency,
    filename: String,
    cachedir: File
  ): Option[File] = {
    val destination = cachedir / dependency.name / dependency.version / filename
    if (destination.isFile) {
      Some(destination)
    } else {
      val temporary = destination.getParentFile / s"${filename}.tmp"
      try {
        IO.createDirectory(destination.getParentFile)
        val url = new URI(s"${repository}/${dependency.name}/${dependency.version}/${filename}").toURL
        val connection = url.openConnection()
        connection.setConnectTimeout(_connect_timeout_ms)
        connection.setReadTimeout(_read_timeout_ms)
        val input = connection.getInputStream
        try Files.copy(input, temporary.toPath, StandardCopyOption.REPLACE_EXISTING)
        finally input.close()
        Files.move(temporary.toPath, destination.toPath, StandardCopyOption.REPLACE_EXISTING)
        Some(destination)
      } catch {
        case NonFatal(_) =>
          IO.delete(temporary)
          None
      }
    }
  }

  private def _is_snapshot(version: String): Boolean =
    version.toUpperCase(java.util.Locale.ROOT).contains("SNAPSHOT")
}
