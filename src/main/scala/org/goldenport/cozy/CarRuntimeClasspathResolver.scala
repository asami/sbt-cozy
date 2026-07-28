package org.goldenport.cozy

import java.io.File
import java.util.zip.ZipFile
import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}
import sbt.{file, IO}

/*
 * Resolves the implementation classpath of an exact CAR for in-process tests.
 *
 * Production CNCF runtime keeps component-local dependencies in its component
 * classloader. This resolver projects the same CAR-owned local dependency
 * contract into one flat SBT test classpath without consulting a source
 * project.
 *
 * @since   Jul. 28, 2026
 * @version Jul. 28, 2026
 * @author  ASAMI, Tomoharu
 */
private[cozy] object CarRuntimeClasspathResolver {
  final case class Manifest(
    local: Vector[String],
    repositories: Vector[String]
  )

  private val _default_repositories = Vector(
    "central",
    "https://www.simplemodeling.org/repository/maven"
  )

  def readManifest(car: File): Manifest = {
    val archive = new ZipFile(car)
    try {
      Option(archive.getEntry("component-dependencies.yaml")) match {
        case Some(entry) =>
          val input = scala.io.Source.fromInputStream(
            archive.getInputStream(entry),
            "UTF-8"
          )
          val text =
            try input.mkString
            finally input.close()
          val config = CozyProjectConfig.parse(text.linesIterator.toVector)
          Manifest(
            config.list("dependencies.local").distinct.toVector,
            config.list("dependencies.repositories").distinct.toVector
          )
        case None =>
          Manifest(Vector.empty, Vector.empty)
      }
    } finally {
      archive.close()
    }
  }

  def resolve(
    cars: Seq[(CarDependency, File)],
    outputDirectory: File,
    log: sbt.util.Logger
  ): Seq[File] = {
    val extracted = cars.flatMap { case (dependency, car) =>
      _extract_runtime_jars(dependency, car, outputDirectory)
    }
    val manifests = cars.map { case (_, car) => readManifest(car) }
    val coordinates = manifests.flatMap(_.local).distinct.sorted
    val repositories =
      (_default_repositories ++ manifests.flatMap(_.repositories)).
        distinct
    (extracted ++ _fetch(coordinates, repositories, log)).
      filter(_.isFile).
      distinct.
      sortBy(_.getAbsolutePath)
  }

  private def _extract_runtime_jars(
    dependency: CarDependency,
    car: File,
    outputdirectory: File
  ): Seq[File] = {
    val output =
      new File(new File(outputdirectory, dependency.name), dependency.version)
    IO.delete(output)
    IO.createDirectory(output)
    IO.unzip(car, output).toSeq.
      filter(_.isFile).
      filter { runtimejar =>
        val relative = IO.relativize(output, runtimejar).getOrElse("")
        relative == "component/main.jar" ||
          (relative.startsWith("lib/") && relative.endsWith(".jar"))
      }.
      sortBy(_.getAbsolutePath)
  }

  private def _fetch(
    coordinates: Seq[String],
    repositories: Seq[String],
    log: sbt.util.Logger
  ): Seq[File] =
    if (coordinates.isEmpty)
      Vector.empty
    else {
      val command = fetchCommand(coordinates, repositories)
      val output = new StringBuilder
      val errors = new StringBuilder
      val exit = Process(command).!(ProcessLogger(
        line => {
          if (output.nonEmpty) output.append('\n')
          output.append(line)
        },
        line => {
          if (errors.nonEmpty) errors.append('\n')
          errors.append(line)
          log.debug(s"[sbt-cozy/coursier] $line")
        }
      ))
      if (exit != 0)
        sys.error(
          s"[sbt-cozy] CAR local dependency resolution failed ($exit): " +
            s"${coordinates.mkString(", ")}\n${errors.result()}"
        )
      output.result().trim.
        split(java.util.regex.Pattern.quote(File.pathSeparator)).
        toVector.
        map(_.trim).
        filter(_.nonEmpty).
        map(file)
    }

  private[cozy] def fetchCommand(
    coordinates: Seq[String],
    repositories: Seq[String]
  ): Seq[String] = {
    val launcher =
      sys.env.getOrElse("SBT_COZY_COURSIER_COMMAND", "cs")
    Seq(launcher, "fetch", "--classpath") ++
      repositories.flatMap(repository => Seq("--repository", repository)) ++
      coordinates
  }
}
