package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.collection.JavaConverters._
import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 15, 2026
 * @version Oct. 5, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyCncfRuntimeDescriptorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy CNCF runtime descriptor transport" should {
    "resolved JAR input" which {
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

    "the default mandatory input" which {
      "retain exact JAR staging when no source file is selected" in {
        Given("one resolved JAR descriptor and no explicit source input")
        _with_temp_dir { dir =>
          val descriptor = "schemaVersion: 1\nruntime: cncf\nversion: default\n"
          val jar = dir.resolve("cncf.jar")
          _write_jar_entry(jar, "META-INF/cncf/runtime.yaml", descriptor)
          val output = dir.resolve("target/cncf-runtime.yaml")

          When("generation requests its mandatory descriptor through the default input")
          val extracted = CozyCncfRuntimeDescriptor.extractRequired(Seq(jar.toFile), output.toFile, None)

          Then("the managed descriptor retains its exact bytes and provenance digest")
          extracted.path shouldBe output.toFile
          Files.readAllBytes(output).toVector shouldBe descriptor.getBytes(StandardCharsets.UTF_8).toVector
          extracted.sha256 shouldBe _sha256(descriptor.getBytes(StandardCharsets.UTF_8))
        }
      }

      "retain the missing descriptor error when no source file is selected" in {
        Given("resolved dependencies without a descriptor and no explicit source input")
        _with_temp_dir { dir =>
          val jar = dir.resolve("plain.jar")
          _write_jar_entry(jar, "META-INF/plain.txt", "plain")

          When("generation requests its mandatory descriptor through the default input")
          val exception = intercept[RuntimeException] {
            CozyCncfRuntimeDescriptor.extractRequired(Seq(jar.toFile), dir.resolve("out.yaml").toFile, None)
          }

          Then("the established missing runtime error remains available")
          exception.getMessage should include ("CNCF runtime descriptor is unavailable")
        }
      }

      "retain the multiple descriptor error when no source file is selected" in {
        Given("two resolved descriptor-bearing JARs and no explicit source input")
        _with_temp_dir { dir =>
          val first = dir.resolve("first.jar")
          val second = dir.resolve("second.jar")
          _write_jar_entry(first, "META-INF/cncf/runtime.yaml", "version: first\n")
          _write_jar_entry(second, "META-INF/cncf/runtime.yaml", "version: second\n")

          When("generation requests its mandatory descriptor through the default input")
          val exception = intercept[RuntimeException] {
            CozyCncfRuntimeDescriptor.extractRequired(
              Seq(first.toFile, second.toFile), dir.resolve("out.yaml").toFile, None
            )
          }

          Then("generation still rejects an ambiguous runtime selection")
          exception.getMessage should include ("multiple CNCF runtime descriptors resolved")
        }
      }
    }

    "explicit source input" which {
      "stage the selected file's exact bytes and provenance digest" in {
        Given("a prepared UTF-8 source descriptor with non-ASCII text and CRLF line endings")
        _with_temp_dir { dir =>
          val bytes = "schemaVersion: 1\r\nruntime: cncf\r\nlabel: 運用 café 🚀\r\n".getBytes(StandardCharsets.UTF_8)
          val source = dir.resolve("source-runtime.yaml")
          Files.write(source, bytes)
          val output = dir.resolve("target/sbt-cozy/cncf-runtime.yaml")

          When("generation selects that source file without a descriptor-bearing dependency")
          val extracted = CozyCncfRuntimeDescriptor.extractRequired(Seq.empty, output.toFile, Some(source.toFile))

          Then("the managed target and fingerprint preserve the source content exactly")
          extracted.path shouldBe output.toFile
          Files.readAllBytes(output).toVector shouldBe bytes.toVector
          extracted.sha256 shouldBe _sha256(bytes)
          Files.readAllBytes(source).toVector shouldBe bytes.toVector
        }
      }

      "take precedence over a stale resolved JAR descriptor" in {
        Given("a current source descriptor and a stale descriptor-bearing dependency JAR")
        _with_temp_dir { dir =>
          val current = "schemaVersion: 1\nruntime: cncf\nversion: current-source\n"
          val source = dir.resolve("source.yaml")
          Files.write(source, current.getBytes(StandardCharsets.UTF_8))
          val jar = dir.resolve("stale.jar")
          _write_jar_entry(jar, "META-INF/cncf/runtime.yaml", "version: stale-jar\n")
          val output = dir.resolve("target/cncf-runtime.yaml")

          When("generation explicitly selects the source descriptor")
          val extracted = CozyCncfRuntimeDescriptor.extractRequired(Seq(jar.toFile), output.toFile, Some(source.toFile))

          Then("the selected source alone determines the staged content and digest")
          Files.readString(extracted.path.toPath) shouldBe current
          extracted.sha256 shouldBe _sha256(current.getBytes(StandardCharsets.UTF_8))
        }
      }

      "reject a missing selected file without falling back to a resolved JAR" in {
        Given("a missing source descriptor and an otherwise usable dependency descriptor")
        _with_temp_dir { dir =>
          val source = dir.resolve("missing.yaml")
          val jar = dir.resolve("fallback.jar")
          _write_jar_entry(jar, "META-INF/cncf/runtime.yaml", "version: fallback\n")
          val output = dir.resolve("target/cncf-runtime.yaml")

          When("generation explicitly selects the missing source")
          val exception = intercept[RuntimeException] {
            CozyCncfRuntimeDescriptor.extractRequired(Seq(jar.toFile), output.toFile, Some(source.toFile))
          }

          Then("the error identifies the invalid selection and no fallback is staged")
          exception.getMessage should include ("[sbt-cozy] explicit CNCF runtime descriptor input is not a regular file")
          exception.getMessage should include (source.toFile.getAbsolutePath)
          Files.exists(output) shouldBe false
        }
      }

      "reject a directory selected as a descriptor" in {
        Given("a selected directory and an otherwise usable dependency descriptor")
        _with_temp_dir { dir =>
          val source = Files.createDirectory(dir.resolve("directory.yaml"))
          val jar = dir.resolve("fallback.jar")
          _write_jar_entry(jar, "META-INF/cncf/runtime.yaml", "version: fallback\n")
          val output = dir.resolve("target/cncf-runtime.yaml")

          When("generation explicitly selects the directory")
          val exception = intercept[RuntimeException] {
            CozyCncfRuntimeDescriptor.extractRequired(Seq(jar.toFile), output.toFile, Some(source.toFile))
          }

          Then("the error names the directory and no fallback is staged")
          exception.getMessage should include ("[sbt-cozy] explicit CNCF runtime descriptor input is not a regular file")
          exception.getMessage should include (source.toFile.getAbsolutePath)
          Files.exists(output) shouldBe false
        }
      }

      "reject a symbolic link even when it points to a regular descriptor" in {
        Given("a source symlink to a regular descriptor and an otherwise usable dependency descriptor")
        _with_temp_dir { dir =>
          val regular = dir.resolve("regular.yaml")
          Files.write(regular, "version: linked-source\n".getBytes(StandardCharsets.UTF_8))
          val source = Files.createSymbolicLink(dir.resolve("linked.yaml"), regular)
          val jar = dir.resolve("fallback.jar")
          _write_jar_entry(jar, "META-INF/cncf/runtime.yaml", "version: fallback\n")
          val output = dir.resolve("target/cncf-runtime.yaml")

          When("generation explicitly selects the symbolic link")
          val exception = intercept[RuntimeException] {
            CozyCncfRuntimeDescriptor.extractRequired(Seq(jar.toFile), output.toFile, Some(source.toFile))
          }

          Then("the error names the disallowed symlink and no fallback is staged")
          exception.getMessage should include ("symbolic links are not allowed")
          exception.getMessage should include (source.toFile.getAbsolutePath)
          Files.exists(output) shouldBe false
        }
      }

      "preserve equivalent UTF-8 content and provenance across source and JAR inputs" in {
        Given("bounded descriptor bodies containing varied ASCII and Unicode text")
        val bodies = Gen.choose(0, 64).flatMap { size =>
          Gen.listOfN(size, Gen.oneOf("a", "Z", "0", " ", "é", "運", "用", "🚀")).map { characters =>
            "schemaVersion: 1\nruntime: cncf\nversion: property\nlabel: \"" + characters.mkString + "\"\n"
          }
        }
        val property = Prop.forAll(bodies) { body =>
          Given("the same generated UTF-8 descriptor in a source file and dependency JAR")
          _with_temp_dir { dir =>
            val bytes = body.getBytes(StandardCharsets.UTF_8)
            val source = dir.resolve("source.yaml")
            Files.write(source, bytes)
            val jar = dir.resolve("cncf.jar")
            _write_jar_entry(jar, "META-INF/cncf/runtime.yaml", body)
            val sourceoutput = dir.resolve("target/source/cncf-runtime.yaml")
            val jaroutput = dir.resolve("target/jar/cncf-runtime.yaml")

            When("generation stages each equivalent input through its mandatory transport")
            val explicit = CozyCncfRuntimeDescriptor.extractRequired(Seq.empty, sourceoutput.toFile, Some(source.toFile))
            val resolved = CozyCncfRuntimeDescriptor.extractRequired(Seq(jar.toFile), jaroutput.toFile)

            Then("both transports preserve identical bytes and the same artifact fingerprint")
            Files.readAllBytes(explicit.path.toPath).toVector shouldBe bytes.toVector
            Files.readAllBytes(resolved.path.toPath).toVector shouldBe bytes.toVector
            explicit.sha256 shouldBe resolved.sha256
            explicit.sha256 shouldBe _sha256(bytes)
            true
          }
        }

        When("ScalaCheck evaluates forty generated descriptor bodies")
        val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(40).withWorkers(1), property)

        Then("source-file staging and equivalent JAR staging satisfy the same byte and digest contract")
        withClue(result.toString) { result.passed shouldBe true }
      }
    }
  }

  private def _sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

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
    val workroot = Files.createDirectories(Paths.get("target/sbt-cozy-test/work").toAbsolutePath)
    val dir = Files.createTempDirectory(workroot, "sbt-cozy-cncf-runtime-descriptor-")
    try f(dir)
    finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
