package org.goldenport.cozy

import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.jar.{JarEntry, JarOutputStream}
import scala.collection.JavaConverters._
import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Oct. 17, 2026
 * @version Oct. 17, 2026
 */
final class CozySourceBuildPackagingSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Explicit source CAR packaging inputs" should {
    "preserve published inputs" which {
      "keeps ordinary JARs and omits both source arguments by default" in {
        _with_fixture { root =>
          Given("ordinary resolved JARs and canonical release build inputs without a source artifact")
          val ordinary = Seq(_jar(root.resolve("ordinary.jar")))
          val metadata = _metadata("0.1.0", "0.5.2", "0.3.2.2")
          When("packaging selects its default inputs")
          val selected = CozySourceBuildPackaging.select(None, metadata)
          Then("the library inputs remain identical and no source pair crosses the bridge")
          selected.libraryJars(ordinary) shouldBe ordinary
          selected.sourceversions shouldBe None
          CozySourceBuildPackaging.arguments(selected.sourceversions) shouldBe Vector.empty
        }
      }
    }

    "select native source inputs" which {
      "carries the effective exact pair with the real JAR for arbitrary Snapshot versions" in {
        _with_fixture { root =>
          Given("a packaged native JAR, ordinary dependencies and varying effective Snapshot coordinates")
          val nativejar = _jar(root.resolve("native.jar"))
          val ordinary = Seq(_jar(root.resolve("ordinary.jar")))
          val versions = Gen.zip(Gen.choose(1, 1000), Gen.choose(1, 1000))
          When("the source artifact is explicitly selected for every generated version pair")
          val result = Test.check(Test.Parameters.default, Prop.forAll(versions) { case (cncfpatch, cozypatch) =>
            val cncfversion = s"0.5.$cncfpatch-SNAPSHOT"
            val cozyversion = s"0.3.$cozypatch-SNAPSHOT"
            val metadata = _metadata("0.1.1-SNAPSHOT", cncfversion, cozyversion)
            val selected = CozySourceBuildPackaging.select(Some(nativejar), metadata)
            selected.libraryJars(ordinary) == (ordinary :+ nativejar) &&
              selected.libraryJars(ordinary :+ nativejar) == (ordinary :+ nativejar) &&
              selected.sourceversions.contains(cncfversion -> cozyversion) &&
              CozySourceBuildPackaging.arguments(selected.sourceversions) == Vector(
                "--source-cncf-version", cncfversion, "--source-cozy-version", cozyversion
              ) && metadata.value("project.component.version").contains("0.1.1-SNAPSHOT")
          })
          Then("selection adds exactly the native JAR and transports both exact build versions")
          result.passed shouldBe true
        }
      }

      "rejects missing files, directories and non-JAR inputs without fallback" in {
        _with_fixture { root =>
          Given("an explicit artifact input that is absent, a directory or a non-JAR file")
          val directory = Files.createDirectory(root.resolve("directory.jar")).toFile
          val text = Files.write(root.resolve("runtime.txt"), Array[Byte](1)).toFile
          val inputs = Seq(root.resolve("missing.jar").toFile, directory, text)
          val metadata = _metadata("0.1.1-SNAPSHOT", "0.5.3-SNAPSHOT", "0.3.3-SNAPSHOT")
          When("each invalid artifact is selected")
          val failures = inputs.map { artifact =>
            intercept[RuntimeException] { CozySourceBuildPackaging.select(Some(artifact), metadata) }
          }
          Then("each input fails admission instead of choosing published artifacts")
          failures.foreach(_.getMessage should include("existing regular JAR"))
        }
      }

      "rejects release or missing component, CNCF and Cozy versions without fallback" in {
        _with_fixture { root =>
          Given("a real JAR and source metadata with one release or missing build coordinate")
          val artifact = _jar(root.resolve("native.jar"))
          val valid = _metadata("0.1.1-SNAPSHOT", "0.5.3-SNAPSHOT", "0.3.3-SNAPSHOT")
          val invalid = Seq(
            _metadata("0.1.1", "0.5.3-SNAPSHOT", "0.3.3-SNAPSHOT"),
            _metadata("0.1.1-SNAPSHOT", "0.5.3", "0.3.3-SNAPSHOT"),
            _metadata("0.1.1-SNAPSHOT", "0.5.3-SNAPSHOT", "0.3.3"),
            valid.copy(values = valid.values - "project.component.version"),
            valid.copy(values = valid.values - "build.cozyVersion"),
            valid.copy(lists = valid.lists - "build.dependencies.compile")
          )
          When("source selection interprets each incomplete or release contract")
          val failures = invalid.map { metadata =>
            intercept[RuntimeException] { CozySourceBuildPackaging.select(Some(artifact), metadata) }
          }
          Then("every invalid contract fails before any published fallback is available")
          failures.size shouldBe invalid.size
          failures.foreach(_.getMessage should not be empty)
        }
      }
    }
  }

  private def _metadata(component: String, cncf: String, cozy: String): CozyProjectConfig =
    CozyProjectConfig(
      Map("project.kind" -> "car", "project.component.version" -> component, "build.cozyVersion" -> cozy),
      Map("build.dependencies.compile" -> Seq(s"org.goldenport::goldenport-cncf:$cncf"))
    )

  private def _jar(path: Path): java.io.File = {
    val output = new JarOutputStream(Files.newOutputStream(path))
    try {
      output.putNextEntry(new JarEntry("native.txt"))
      output.write(Array[Byte](1))
      output.closeEntry()
    } finally output.close()
    path.toFile
  }

  private def _with_fixture[A](body: Path => A): A = {
    val work = Path.of("target/sbt-cozy-test/work/source-build-packaging").toAbsolutePath.normalize()
    Files.createDirectories(work)
    val root = Files.createTempDirectory(work, "selection-")
    try body(root)
    finally {
      val stream = Files.walk(root)
      try stream.sorted(Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists(_))
      finally stream.close()
    }
  }
}
