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
 * @version Oct.  9, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyCncfRuntimeDescriptorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy CNCF runtime descriptor transport" should {
    "extract the descriptor from the resolved runtime dependency" in {
      _with_temp_dir { dir =>
        Given("one resolved dependency represented as a JAR or a class directory with the fixed descriptor resource")
        val descriptor =
          """schemaVersion: 1
            |runtime: cncf
            |version: 0.5.1-SNAPSHOT
            |predefinedResults: {}
            |""".stripMargin
        val jar = dir.resolve("goldenport-cncf_3-0.5.1-SNAPSHOT.jar")
        _write_jar_entry(jar, "META-INF/cncf/runtime.yaml", descriptor)
        val classes = dir.resolve("classes")
        val resource = classes.resolve("META-INF/cncf/runtime.yaml")
        Files.createDirectories(resource.getParent)
        Files.write(resource, descriptor.getBytes(StandardCharsets.UTF_8))
        val dependencies = Vector(jar, classes)

        dependencies.zipWithIndex.foreach { case (dependency, index) =>
          val output = dir.resolve(s"target/cncf-runtime-$index.yaml")
          When("sbt-cozy prepares delegated generation settings from the selected dependency representation")
          val extracted = CozyCncfRuntimeDescriptor.extractRequired(Seq(dependency.toFile), output.toFile)

          Then("the selected output path contains the exact descriptor bytes")
          extracted.toPath shouldBe output
          Files.readString(extracted.toPath, StandardCharsets.UTF_8) shouldBe descriptor
        }
      }
    }

    "reject multiple runtime descriptors" in {
      _with_temp_dir { dir =>
        Given("distinct resolved JAR or class-directory owners that claim the CNCF runtime, even with equal bytes")
        val first = dir.resolve("cncf-first.jar")
        val second = dir.resolve("cncf-second.jar")
        _write_jar_entry(first, "META-INF/cncf/runtime.yaml", "version: first\n")
        _write_jar_entry(second, "META-INF/cncf/runtime.yaml", "version: first\n")
        val classes = dir.resolve("classes")
        val resource = classes.resolve("META-INF/cncf/runtime.yaml")
        Files.createDirectories(resource.getParent)
        Files.write(resource, "version: first\n".getBytes(StandardCharsets.UTF_8))
        val owners = Vector(Seq(first.toFile, second.toFile), Seq(first.toFile, classes.toFile))

        owners.foreach { dependencies =>
          When("sbt-cozy resolves the selected runtime descriptor")
          val exception = intercept[RuntimeException] {
            CozyCncfRuntimeDescriptor.extract(dependencies, dir.resolve("out.yaml").toFile)
          }

          Then("generation fails instead of selecting an arbitrary runtime")
          exception.getMessage should include ("multiple CNCF runtime descriptors resolved")
        }
      }
    }

    "require the descriptor for delegated component generation" in {
      _with_temp_dir { dir =>
        Given("resolved dependencies without a CNCF runtime descriptor")
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
    val workroot = Path.of("target/cozy-cncf-runtime-descriptor-spec/work").toAbsolutePath.normalize()
    Files.createDirectories(workroot)
    val dir = Files.createTempDirectory(workroot, "sbt-cozy-cncf-runtime-descriptor-")
    try f(dir)
    finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
