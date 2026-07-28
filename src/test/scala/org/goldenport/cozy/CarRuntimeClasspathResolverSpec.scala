package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
/*
 * @since   Jul. 28, 2026
 * @version Jul. 28, 2026
 * @author  ASAMI, Tomoharu
 */
final class CarRuntimeClasspathResolverSpec
    extends AnyWordSpec
    with Matchers
    with GivenWhenThen {
  "CAR runtime classpath resolution" should {
    "read component-local Maven dependencies from the packaged manifest" in {
      Given("a CAR with local, shared, and provided dependency declarations")
      val archive = Files.createTempFile("sbt-cozy-runtime-manifest-", ".car")
      _write_car(
        archive.toFile,
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
        CarRuntimeClasspathResolver.readManifest(archive.toFile)

      Then("only component-local dependencies are selected for flattening")
      manifest.local shouldBe Vector("org.jsoup:jsoup:1.18.1")
      manifest.repositories shouldBe Vector(
        "https://example.invalid/maven"
      )
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
  }

  private def _write_car(
    archive: java.io.File,
    manifest: String
  ): Unit = {
    val output = new ZipOutputStream(Files.newOutputStream(archive.toPath))
    try {
      output.putNextEntry(new ZipEntry("component-dependencies.yaml"))
      output.write(manifest.getBytes(StandardCharsets.UTF_8))
      output.closeEntry()
    } finally {
      output.close()
    }
  }
}
