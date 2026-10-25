package org.goldenport.cozy

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import java.util.UUID
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import sbt._
import sbt.Keys._

/*
 * @since   Oct. 21, 2026
 * @version Oct. 22, 2026
 */
private[cozy] object InstalledRuntimeClasspath {
  val manifestProperty = "cncf.installed-runtime.manifest"
  val requiredProperty = "cncf.installed-runtime.required"
  val rootCoordinate = "org.goldenport:goldenport-cncf_3:0.5.3-SNAPSHOT"

  final case class Coordinate(organization: String, name: String, revision: String, classifier: Option[String])
  final case class Module(coordinate: Coordinate, file: File)
  final case class Manifest(installationid: String, updateid: String, modules: Vector[Module])

  def fromProperties(properties: collection.Map[String, String]): Option[Manifest] = {
    val filename = properties.get(manifestProperty)
    if (filename.isEmpty && properties.get(requiredProperty).contains("true"))
      _reject("required installed-runtime manifest is missing")
    filename.map { value =>
      if (value.trim.isEmpty || !new File(value).isAbsolute)
        _reject("installed-runtime manifest must be an absolute filename")
      read(new File(value))
    }
  }

  def read(manifestFile: File): Manifest = {
    val path = manifestFile.toPath.toAbsolutePath.normalize
    _regular(path)
    val json = parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).fold(
      error => _reject(error.message), identity
    )
    val cursor = json.hcursor
    if (_string(cursor, "kind") != "cncf.installed-runtime.v1" ||
      _string(cursor, "application") != "sm-workflow" ||
      _string(cursor, "root_coordinate") != rootCoordinate)
      _reject("unsupported installed-runtime contract")
    val installationid = _uuid(_string(cursor, "installation_id"))
    val updateid = _uuid(_string(cursor, "update_id"))
    val root = path.getParent
    if (root.getFileName.toString != installationid)
      _reject("installation directory differs from installation_id")
    val repositories = cursor.get[Vector[String]]("repositories").fold(error => _reject(error.message), identity)
    if (repositories.isEmpty || repositories.exists(_.trim.isEmpty)) _reject("repositories must be nonempty")
    val entries = cursor.get[Vector[Json]]("modules").fold(error => _reject(error.message), identity)
    val modules = entries.zipWithIndex.map { case (entry, index) =>
      val item = entry.hcursor
      val coordinate = parseCoordinate(_string(item, "coordinate"))
      val relative = _string(item, "path")
      if (relative != f"runtime/$index%04d.jar") _reject("runtime module paths must retain manifest order")
      _absolute_source(item)
      Module(coordinate, _installed_file(root, relative))
    }
    if (modules.isEmpty || modules.map(_.coordinate).distinct.size != modules.size ||
      !modules.exists(_.coordinate == parseCoordinate(rootCoordinate)))
      _reject("missing root or duplicate runtime module identity")
    val component = cursor.downField("component").focus.getOrElse(_reject("missing component")).hcursor
    if (_string(component, "identity") != "org.simplemodeling.textus.SmWorkflow" ||
      _string(component, "coordinate") != "org.simplemodeling.textus:textus-sm-workflow:0.1.0-SNAPSHOT" ||
      _string(component, "path") != "components/textus-sm-workflow.car")
      _reject("unsupported installed component")
    _absolute_source(component)
    _installed_file(root, _string(component, "path"))
    Manifest(installationid, updateid, modules)
  }

  def parseCoordinate(value: String): Coordinate = value.split(":", -1).toVector match {
    case Vector(organization, name, revision) if Vector(organization, name, revision).forall(_.nonEmpty) =>
      Coordinate(organization, name, revision, None)
    case Vector(organization, name, "jar", classifier, revision)
        if Vector(organization, name, classifier, revision).forall(_.nonEmpty) =>
      Coordinate(organization, name, revision, Some(classifier))
    case _ => _reject("unsupported Coursier coordinate: " + value)
  }

  def bind(classpath: Seq[Attributed[File]], manifest: Option[Manifest]): Seq[Attributed[File]] = manifest match {
    case None => classpath
    case Some(selected) =>
      val matched = scala.collection.mutable.Set.empty[Coordinate]
      val result = classpath.map { entry =>
        entry.get(moduleID.key) match {
          case Some(module) if selected.modules.exists(item =>
            item.coordinate.organization == module.organization && item.coordinate.name == module.name) =>
            val classifier = entry.get(artifact.key).flatMap(_.classifier)
            val coordinate = Coordinate(module.organization, module.name, module.revision, classifier)
            val installed = selected.modules.find(_.coordinate == coordinate).getOrElse(
              _reject("resolved runtime version/classifier differs from installation: " + coordinate)
            )
            _regular(installed.file.toPath)
            matched += coordinate
            Attributed(installed.file)(entry.metadata)
          case _ => entry
        }
      }
      val missing = selected.modules.map(_.coordinate).filterNot(matched)
      if (missing.nonEmpty) _reject("installed modules absent from dependency scope: " + missing.mkString(", "))
      result
  }

  def checkAndExport(
    fullClasspath: Seq[Attributed[File]],
    compileDependencyClasspath: Seq[Attributed[File]],
    testDependencyClasspath: Seq[Attributed[File]],
    manifest: Option[Manifest],
    outputFile: File
  ): Option[File] = {
    bind(compileDependencyClasspath, manifest)
    bind(testDependencyClasspath, manifest)
    manifest.map { selected =>
      val classpath = bind(fullClasspath, Some(selected))
        .map(_.data.getAbsoluteFile)
        .distinct
        .map(_.getAbsolutePath)
      IO.createDirectory(outputFile.getParentFile)
      IO.writeLines(outputFile, classpath)
      outputFile
    }
  }

  private def _string(cursor: HCursor, field: String): String =
    cursor.get[String](field).fold(error => _reject(error.message), identity)

  private def _uuid(value: String): String = {
    val parsed = try UUID.fromString(value).toString catch {
      case _: IllegalArgumentException => _reject("invalid UUID: " + value)
    }
    if (parsed != value) _reject("noncanonical UUID: " + value)
    parsed
  }

  private def _absolute_source(cursor: HCursor): Unit =
    if (!new File(_string(cursor, "source")).isAbsolute) _reject("source must be an absolute filename")

  private def _installed_file(root: Path, relative: String): File = {
    val path = root.resolve(relative).normalize
    if (new File(relative).isAbsolute || !path.startsWith(root) || path == root)
      _reject("installed path escapes installation")
    _regular(path)
    path.toFile
  }

  private def _regular(path: Path): Unit = {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) _reject("missing regular installed file: " + path)
    var ancestor = path.getParent
    while (ancestor != null) {
      if (Files.isSymbolicLink(ancestor)) _reject("symlink in installed path: " + path)
      ancestor = ancestor.getParent
    }
    try {
      if (Files.getAttribute(path, "unix:nlink").asInstanceOf[Number].longValue != 1L)
        _reject("hardlinked installed file: " + path)
    } catch {
      case _: UnsupportedOperationException => ()
    }
  }

  private def _reject(reason: String): Nothing =
    sys.error("[sbt-cozy] installed-runtime: " + reason)
}
