package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.collection.JavaConverters._
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 15, 2026
 * @version Jul. 15, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyCncfRuntimeDescriptorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy CNCF runtime descriptor transport" should {
    "extract the descriptor from the resolved runtime jar" in {
      Given("one resolved dependency jar containing the CNCF runtime descriptor")
      _with_temp_dir { dir =>
        val descriptor =
          """schemaVersion: 1
            |runtime: cncf
            |version: 0.5.1-SNAPSHOT
            |predefinedResults: {}
            |""".stripMargin
        val jar = dir.resolve("goldenport-cncf_3-0.5.1-SNAPSHOT.jar")
        _write_jar_entry(jar, "META-INF/cncf/runtime.yaml", descriptor)
        val output = dir.resolve("target/cncf-runtime.yaml")

        When("sbt-cozy prepares delegated generation settings")
        val extracted = CozyCncfRuntimeDescriptor.extract(Seq(jar.toFile), output.toFile).getOrElse(fail("descriptor was not extracted"))

        Then("the exact descriptor and its content fingerprint are available")
        Files.readString(extracted.path.toPath) shouldBe descriptor
        extracted.sha256 should have length 64
      }
    }

    "reject multiple runtime descriptors" in {
      Given("two resolved jars that both claim to be the CNCF runtime")
      _with_temp_dir { dir =>
        val first = dir.resolve("cncf-first.jar")
        val second = dir.resolve("cncf-second.jar")
        _write_jar_entry(first, "META-INF/cncf/runtime.yaml", "version: first\n")
        _write_jar_entry(second, "META-INF/cncf/runtime.yaml", "version: second\n")

        When("sbt-cozy resolves the selected runtime descriptor")
        val exception = intercept[RuntimeException] {
          CozyCncfRuntimeDescriptor.extract(Seq(first.toFile, second.toFile), dir.resolve("out.yaml").toFile)
        }

        Then("generation fails instead of selecting an arbitrary runtime")
        exception.getMessage should include ("multiple CNCF runtime descriptors resolved")
      }
    }

    "require the descriptor for delegated component generation" in {
      Given("resolved dependencies without a CNCF runtime descriptor")
      _with_temp_dir { dir =>
        val plainjar = dir.resolve("plain-runtime.jar")
        _write_jar_entry(plainjar, "META-INF/plain.txt", "plain")

        When("sbt-cozy prepares the required runtime contract")
        val exception = intercept[RuntimeException] {
          CozyCncfRuntimeDescriptor.extractRequired(Seq(plainjar.toFile), dir.resolve("out.yaml").toFile)
        }

        Then("generation fails instead of continuing with an empty catalog")
        exception.getMessage should include ("CNCF runtime descriptor is unavailable")
      }
    }
  }

  private def _write_jar_entry(path: Path, name: String, content: String): Unit = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    val out = new ZipOutputStream(Files.newOutputStream(path))
    try {
      out.putNextEntry(new ZipEntry(name))
      out.write(content.getBytes(StandardCharsets.UTF_8))
      out.closeEntry()
    } finally {
      out.close()
    }
  }

  private def _with_temp_dir[A](f: Path => A): A = {
    val dir = Files.createTempDirectory("sbt-cozy-cncf-runtime-descriptor-")
    try f(dir)
    finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
