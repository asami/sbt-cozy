package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._
/*
 * @since   Jul. 28, 2026
 * @version Aug.  7, 2026
 * @author  ASAMI, Tomoharu
 */
final class CarRuntimeClasspathResolverSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen {
  "CAR runtime classpath resolution" should {
    "read component-local Maven dependencies from the packaged manifest" in {
      Given("a CAR with local, shared, and provided dependency declarations")
      _with_temp_dir("runtime-manifest") { directory =>
        val archive = directory / "manifest.car"
        _write_car(
          archive,
          """dependencies:
          |  provided:
            |    - "org.goldenport:goldenport-cncf_3:0.5.1"
          |  shared:
          |    - "org.example:shared-api:1.0.0"
          |  local:
          |    - "org.jsoup:jsoup:1.18.1"
          |  repositories:
          |    - "https://example.invalid/maven"
          |""".stripMargin
        )

        When("sbt-cozy reads the CAR-owned test runtime contract")
        val manifest =
          CarRuntimeClasspathResolver.readManifest(archive)

        Then("only component-local dependencies are selected for flattening")
        manifest.local shouldBe Vector("org.jsoup:jsoup:1.18.1")
        manifest.repositories shouldBe Vector(
          "https://example.invalid/maven"
        )
      }
    }

    "build a deterministic Coursier fetch command" in {
      Given("ordered local coordinates and Maven repositories")
      val coordinates = Vector(
        "org.jsoup:jsoup:1.18.1",
        "org.example:fixture:1.0.0"
      )
      val repositories = Vector(
        "central",
        "https://www.simplemodeling.org/repository/maven"
      )

      When("the runtime dependency command is constructed")
      val command =
        CarRuntimeClasspathResolver.fetchCommand(
          coordinates,
          repositories
        )

      Then("repositories precede the exact packaged coordinates")
      command.take(3) shouldBe Seq(
        sys.env.getOrElse("SBT_COZY_COURSIER_COMMAND", "cs"),
        "fetch",
        "--classpath"
      )
      command.takeRight(2) shouldBe coordinates
      command.sliding(2).toVector should contain(
        Seq("--repository", "central")
      )
      command.sliding(2).toVector should contain(
        Seq(
          "--repository",
          "https://www.simplemodeling.org/repository/maven"
        )
      )
    }

    "extract same-filename CAR runtime jars below distinct shared cache roots" in {
      Given("two namespace-qualified CAR archives with equal human filenames")
      _with_temp_dir("runtime-namespace") { directory =>
        val first = directory / "textus-shared-0.6.0.car"
        val second = directory / "textus-shared-0.6.0-copy.car"
        _write_car(first, "", Map("component/main.jar" -> "alpha"))
        _write_car(second, "", Map("component/main.jar" -> "beta"))

        When("the production runtime extractor resolves both exact dependencies")
        val jars = CarRuntimeClasspathResolver.resolve(
          Seq(
            CarDependency("org.alpha.textus", "Shared", "0.6.0") -> first,
            CarDependency("org.beta.textus", "Shared", "0.6.0") -> second
          ),
          directory / "output",
          sbt.util.Logger.Null
        )

        Then("the archive bytes remain isolated below each shared cache-relative parent")
        val paths = jars.map(_.getCanonicalPath)
        paths should contain((directory / "output" / "org" / "alpha" / "textus" / "textus-shared" / "0.6.0" / "component" / "main.jar").getCanonicalPath)
        paths should contain((directory / "output" / "org" / "beta" / "textus" / "textus-shared" / "0.6.0" / "component" / "main.jar").getCanonicalPath)
        jars.map(file => IO.read(file)).toSet shouldBe Set("alpha", "beta")
      }
    }
  }

  private def _write_car(
    archive: java.io.File,
    manifest: String,
    entries: Map[String, String] = Map.empty
  ): Unit = {
    val output = new ZipOutputStream(Files.newOutputStream(archive.toPath))
    try {
      output.putNextEntry(new ZipEntry("component-dependencies.yaml"))
      output.write(manifest.getBytes(StandardCharsets.UTF_8))
      output.closeEntry()
      entries.foreach { case (path, content) =>
        output.putNextEntry(new ZipEntry(path))
        output.write(content.getBytes(StandardCharsets.UTF_8))
        output.closeEntry()
      }
    } finally {
      output.close()
    }
  }

  private def _with_temp_dir[A](prefix: String)(f: File => A): A = {
    val parent = file("target/sbt-cozy-test/work/car-runtime-classpath")
    IO.createDirectory(parent)
    val directory = Files.createTempDirectory(parent.toPath, s"${prefix}-").toFile
    try f(directory)
    finally IO.delete(directory)
  }
}
