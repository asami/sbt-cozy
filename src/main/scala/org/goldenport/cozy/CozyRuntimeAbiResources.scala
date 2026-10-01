package org.goldenport.cozy

import java.security.MessageDigest
import sbt._

/** Transfers official generator sidecars without interpreting or rewriting them. */
private[cozy] object CozyRuntimeAbiResources {
  val filenames: Vector[String] = Vector(
    "statemachine-workflow-abi.json",
    "candidate-admission-producer-abi.json"
  )
  private val epoch = "cozy-runtime-abi-resources-v1"

  private def manifest(target: File): File = target / "sbt-cozy" / "runtime-abi-resources.txt"
  private def staged(target: File): Vector[File] = filenames.map(target / "cozy" / _)
  private def fingerprint(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(IO.readBytes(file)).map(b => f"${b & 0xff}%02x").mkString
  private def snapshot(target: File): String =
    (Vector(epoch) ++ staged(target).filter(_.isFile).map(f => s"${f.getName}=${fingerprint(f)}")).mkString("", "\n", "\n")

  def isAvailable(target: File): Boolean =
    manifest(target).isFile && IO.read(manifest(target)) == snapshot(target)

  def install(workdir: File, target: File): Unit = {
    // Validate every source before changing the prior installed generation.
    val selected = filenames.map { name =>
      val suffix = s"/target/cozy/$name"
      val sources = (workdir ** name).get.filter(f => f.isFile &&
        f.getAbsolutePath.replace('\\', '/').endsWith(suffix)).sortBy(_.getAbsolutePath)
      if (sources.size > 1)
        sys.error(s"[sbt-cozy] multiple official runtime ABI sidecars for $name: ${sources.mkString(", ")}")
      name -> sources.headOption
    }
    IO.delete(manifest(target))
    selected.foreach { case (name, source) =>
      val destination = target / "cozy" / name
      source match {
        case Some(file) =>
          IO.createDirectory(destination.getParentFile)
          IO.copyFile(file, destination, preserveLastModified = true)
        case None => IO.delete(destination)
      }
    }
    IO.write(manifest(target), snapshot(target))
  }

  def clear(target: File): Unit = {
    IO.delete(manifest(target))
    staged(target).foreach(IO.delete(_))
    IO.write(manifest(target), snapshot(target))
  }

  def project(target: File, resources: File): Seq[File] = {
    if (!isAvailable(target))
      sys.error("[sbt-cozy] official runtime ABI sidecars are missing or changed; regenerate before packaging")
    filenames.flatMap { name =>
      val source = target / "cozy" / name
      val destination = resources / "META-INF" / "cozy" / name
      if (source.isFile) {
        IO.createDirectory(destination.getParentFile)
        IO.copyFile(source, destination, preserveLastModified = true)
        Some(destination)
      } else {
        IO.delete(destination)
        None
      }
    }
  }
}
