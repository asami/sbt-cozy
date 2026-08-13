package org.goldenport.cozy

import java.net.URI
import java.nio.file.{Files, Path, StandardCopyOption}

import scala.util.control.NonFatal

import sbt._

/*
 * @since   Jul. 12, 2026
 * @version Aug. 13, 2026
 * @author  ASAMI, Tomoharu
 */
final class CarDependency private (
  val namespace: String,
  val localId: String,
  val version: String
) {
  override def equals(other: Any): Boolean = other match {
    case that: CarDependency =>
      namespace == that.namespace && localId == that.localId && version == that.version
    case _ => false
  }

  override def hashCode: Int =
    java.util.Objects.hash(namespace, localId, version)

  override def toString: String =
    s"CarDependency(namespace=$namespace, localId=$localId, version=$version)"
}

object CarDependency {
  def apply(namespace: String, localId: String, version: String): CarDependency =
    new CarDependency(namespace, localId, version)
}

private[cozy] object CarDependencyResolver {
  private val _connect_timeout_ms = 3000
  private val _read_timeout_ms = 10000

  def resolve(
    dependency: CarDependency,
    repositories: Seq[String],
    cachedir: File
  ): File = {
    val coordinate = CarComponentIdentityAdapter._require_release(dependency)
    val cached = cachedir / coordinate._car_cache_relative_path
    if (cached.isFile)
      cached
    else
      repositories.iterator.map { repository =>
        _resolve_repository(repository, coordinate, cachedir)
      }.collectFirst { case Some(resolved) => resolved }.getOrElse {
        sys.error(
          s"[sbt-cozy] CAR dependency not found: ${coordinate._dependency_key}; searched ${repositories.mkString(", ")}"
        )
      }
  }

  private def _resolve_repository(
    repository: String,
    coordinate: CarComponentReleaseProjection,
    cachedir: File
  ): Option[File] = {
    val normalized = repository.trim.stripSuffix("/")
    if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
      if (_is_snapshot(coordinate._release)) None
      else _download(normalized, coordinate, cachedir)
    } else {
      val root = if (normalized.startsWith("file:")) new java.io.File(new URI(normalized)) else file(normalized)
      val candidate = root / coordinate._car_repository_relative_path
      Option(candidate).filter(_.isFile)
    }
  }

  private def _download(
    repository: String,
    coordinate: CarComponentReleaseProjection,
    cachedir: File
  ): Option[File] = {
    val destination = cachedir / coordinate._car_cache_relative_path
    if (destination.isFile) {
      Some(destination)
    } else {
      var temporary: Option[Path] = None
      try {
        IO.createDirectory(destination.getParentFile)
        val path = Files.createTempFile(destination.getParentFile.toPath, s"${coordinate._car_filename}.", ".tmp")
        temporary = Some(path)
        val url = new URI(s"${repository}/${coordinate._car_repository_relative_path}").toURL
        val connection = url.openConnection()
        connection.setConnectTimeout(_connect_timeout_ms)
        connection.setReadTimeout(_read_timeout_ms)
        val input = connection.getInputStream
        try Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
        finally input.close()
        Files.move(path, destination.toPath, StandardCopyOption.REPLACE_EXISTING)
        temporary = None
        Some(destination)
      } catch {
        case NonFatal(_) => None
      } finally {
        temporary.foreach(path => Files.deleteIfExists(path))
      }
    }
  }

  private def _is_snapshot(version: String): Boolean =
    version.toUpperCase(java.util.Locale.ROOT).contains("SNAPSHOT")
}
