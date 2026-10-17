package org.goldenport.cozy

import java.nio.file.{Files, StandardCopyOption}
import sbt._

/**
 * Transfers official generator sidecars without interpreting or rewriting them.
 *
 * @since   Oct.  1, 2026
 * @version Oct. 10, 2026
 * @author  ASAMI, Tomoharu
 */
private[cozy] object CozyRuntimeAbiResources {
  val filenames: Vector[String] = Vector(
    "statemachine-workflow-abi.json",
    "candidate-admission-producer-abi.json"
  )
  private val _epoch = "cozy-runtime-abi-resources-v1"

  private def _manifest(target: File): File = target / "sbt-cozy" / "runtime-abi-resources.txt"
  private def _staged(target: File): Vector[File] = filenames.map(target / "cozy" / _)
  private def _declaration(names: Vector[String]): String =
    (Vector(_epoch) ++ names).mkString("", "\n", "\n")

  private def _declared(target: File): Option[Vector[String]] = {
    val manifest = _manifest(target)
    if (!manifest.isFile) None
    else {
      val lines = IO.readLines(manifest).toVector
      val names = lines.drop(1)
      if (lines.headOption.contains(_epoch) && names == filenames.filter(names.contains)) Some(names)
      else None
    }
  }

  def isAvailable(target: File): Boolean =
    _declared(target).exists(_.forall(name => (target / "cozy" / name).isFile))

  def install(workdir: File, target: File): Unit = {
    // Resolve official source ambiguity before installing the selected sidecars.
    val selected = filenames.map { name =>
      val suffix = s"/target/cozy/$name"
      val sources = (workdir ** name).get.filter(f => f.isFile &&
        f.getAbsolutePath.replace('\\', '/').endsWith(suffix)).sortBy(_.getAbsolutePath)
      if (sources.size > 1)
        sys.error(s"[sbt-cozy] multiple official runtime ABI sidecars for $name: ${sources.mkString(", ")}")
      name -> sources.headOption
    }
    selected.foreach { case (name, source) =>
      val destination = target / "cozy" / name
      source match {
        case Some(file) =>
          IO.createDirectory(destination.getParentFile)
          Files.copy(file.toPath, destination.toPath, StandardCopyOption.REPLACE_EXISTING)
        case None => IO.delete(destination)
      }
    }
    IO.write(_manifest(target), _declaration(selected.collect { case (name, Some(_)) => name }))
  }

  def clear(target: File): Unit = {
    _staged(target).foreach(IO.delete(_))
    IO.write(_manifest(target), _declaration(Vector.empty))
  }

  def project(target: File, resources: File): Seq[File] = {
    val declared = _declared(target).getOrElse(
      sys.error("[sbt-cozy] official runtime ABI declaration is missing or invalid; regenerate before packaging")
    )
    if (!declared.forall(name => (target / "cozy" / name).isFile))
      sys.error("[sbt-cozy] declared runtime ABI sidecars are missing; regenerate before packaging")
    filenames.flatMap { name =>
      val source = target / "cozy" / name
      val destination = resources / "META-INF" / "cozy" / name
      if (declared.contains(name)) {
        IO.createDirectory(destination.getParentFile)
        Files.copy(source.toPath, destination.toPath, StandardCopyOption.REPLACE_EXISTING)
        Some(destination)
      } else {
        IO.delete(destination)
        None
      }
    }
  }
}
