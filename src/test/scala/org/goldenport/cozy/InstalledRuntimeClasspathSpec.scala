package org.goldenport.cozy

import java.nio.file.Files
import java.util.UUID
import io.circe.Json
import org.scalacheck.{Gen, Prop, Test => PropertyTest}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._
import sbt.Keys._

/*
 * @since   Oct. 21, 2026
 * @version Oct. 22, 2026
 */
final class InstalledRuntimeClasspathSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Installed runtime dependency inputs" should {
    "same-coordinate substitution" which {
      "preserves entry order, classifiers and every attributed metadata value" in {
        Given("an ordered runtime installation and resolved binary and classified artifacts")
        _with_manifest { (manifestfile, manifest) =>
          val marker = AttributeKey[String]("installed-runtime-spec-marker")
          val entries = Vector(
            _entry("org.goldenport", "goldenport-cncf_3", "0.5.3-SNAPSHOT", None).put(marker, "root"),
            _entry("org.example", "fixture", "1.0", Some("runtime")).put(marker, "classified"),
            _entry("org.example", "test-only", "2.0", None).put(marker, "test")
          )

          When("the installed mapping is bound to a dependency scope")
          val result = InstalledRuntimeClasspath.bind(entries, Some(manifest))

          Then("only selected data files change and all metadata retains its original order")
          result.map(_.data) shouldBe manifest.modules.map(_.file) :+ entries.last.data
          result.map(_.metadata) shouldBe entries.map(_.metadata)
          result.map(_.get(marker)) shouldBe Vector(Some("root"), Some("classified"), Some("test"))
          manifestfile.isFile shouldBe true
        }
      }

      "leaves all dependency scopes unchanged without an explicit binding" in {
        Given("arbitrary dependency entry orders without installed runtime configuration")
        val property = Prop.forAll(Gen.listOf(Gen.choose(0, 100))) { numbers =>
          val entries = numbers.map(number => Attributed.blank(file(s"ordinary/$number.jar")))

          When("ordinary dependency classpaths are evaluated")
          val result = InstalledRuntimeClasspath.bind(entries, None)

          Then("the exact original classpath value is retained")
          result.eq(entries)
        }
        PropertyTest.check(PropertyTest.Parameters.default, property).passed shouldBe true
      }
    }

    "checked child launcher export" which {
      "replaces stale mutable paths with ordered source products and selected copied modules" in {
        Given("a stale launcher export, source application products and the installed dependency closure")
        _with_manifest { (manifestfile, manifest) =>
          val directory = manifestfile.getParentFile.getParentFile
          val out = directory / "target/cncf.d/runtime-classpath.txt"
          val marker = AttributeKey[String]("child-launcher-spec-marker")
          val product = Attributed.blank(directory / "source-app-classes").put(marker, "source product")
          IO.createDirectory(product.data)
          val dependencies = _runtime_entries(manifestfile)
          val other = Attributed.blank(directory / "unselected.jar").put(marker, "unselected")
          IO.write(other.data, "unselected dependency")
          val fullclasspath = Vector(product, dependencies.head, other, dependencies.last, product)
          IO.writeLines(out, dependencies.map(_.data.getAbsolutePath))

          When("both dependency scopes are checked and the current full classpath is exported")
          val boundclasspath = InstalledRuntimeClasspath.bind(fullclasspath, Some(manifest))
          val result = InstalledRuntimeClasspath.checkAndExport(
            fullclasspath, dependencies, dependencies, Some(manifest), out)

          Then("the export keeps source products and nonselected entries in order while selecting copied dependencies")
          val expected = Vector(product.data, manifest.modules.head.file, other.data, manifest.modules.last.file)
            .map(_.getAbsolutePath)
          result shouldBe Some(out)
          IO.readLines(out) shouldBe expected
          boundclasspath.map(_.metadata) shouldBe fullclasspath.map(_.metadata)

          When("the original mutable sources are later replaced or deleted and the same graph is exported again")
          IO.write(dependencies.head.data, "replacement mutable runtime")
          Files.delete(dependencies.last.data.toPath)
          InstalledRuntimeClasspath.checkAndExport(fullclasspath, dependencies, dependencies, Some(manifest), out)

          Then("the launcher still names only the selected copied dependencies and the source application products")
          IO.readLines(out) shouldBe expected
          manifest.modules.map(item => IO.read(item.file)) shouldBe Vector.fill(2)("copied runtime fixture")
        }
      }

      "retains a valid export when a dependency scope is missing modules or changes versions or classifiers" in {
        Given("a previous valid export and scopes which disagree with the selected installation")
        _with_manifest { (manifestfile, manifest) =>
          val out = manifestfile.getParentFile.getParentFile / "target/cncf.d/runtime-classpath.txt"
          val previous = "previous valid child launcher export\n"
          IO.write(out, previous)
          val dependencies = _runtime_entries(manifestfile)
          val changedversion = Vector(
            _entry("org.goldenport", "goldenport-cncf_3", "other-SNAPSHOT", None), dependencies.last)
          val changedclassifier = Vector(dependencies.head, _entry("org.example", "fixture", "1.0", None))
          val missing = dependencies.take(1)
          val cases = Vector(
            (changedversion, dependencies, dependencies, "version/classifier differs"),
            (dependencies, changedclassifier, dependencies, "version/classifier differs"),
            (missing, dependencies, dependencies, "absent from dependency scope"),
            (dependencies, missing, dependencies, "absent from dependency scope"),
            (dependencies, dependencies, missing, "absent from dependency scope")
          )

          cases.foreach { case (compileclasspath, testclasspath, fullclasspath, message) =>
            When("a mismatched Compile, Test or full classpath is checked before export")
            val error = intercept[RuntimeException](InstalledRuntimeClasspath.checkAndExport(
              fullclasspath, compileclasspath, testclasspath, Some(manifest), out))

            Then("strict mapping rejects the graph before rewriting the previous export")
            error.getMessage should include(message)
            IO.read(out) shouldBe previous
          }
        }
      }

      "retains a valid export when required manifest configuration or installed files are missing" in {
        Given("a previous export which must survive failed installed-runtime admission")
        _with_manifest { (manifestfile, manifest) =>
          val out = manifestfile.getParentFile.getParentFile / "target/cncf.d/runtime-classpath.txt"
          val previous = "previous valid child launcher export\n"
          IO.write(out, previous)
          val dependencies = _runtime_entries(manifestfile)

          When("the mandatory installed manifest is absent")
          val missingmanifest = intercept[RuntimeException](InstalledRuntimeClasspath.checkAndExport(
            dependencies, dependencies, dependencies, InstalledRuntimeClasspath.fromProperties(
              Map(InstalledRuntimeClasspath.requiredProperty -> "true")), out))

          Then("configuration rejects before rewriting the previous export")
          missingmanifest.getMessage should include("manifest is missing")
          IO.read(out) shouldBe previous

          When("a selected copied runtime file is missing after manifest admission")
          Files.delete(manifest.modules.head.file.toPath)
          val missingfile = intercept[RuntimeException](InstalledRuntimeClasspath.checkAndExport(
            dependencies, dependencies, dependencies, Some(manifest), out))

          Then("file validation rejects before rewriting the previous export")
          missingfile.getMessage should include("regular installed file")
          IO.read(out) shouldBe previous
        }
      }

      "leaves existing and absent project-mode exports untouched without a selected manifest" in {
        Given("ordinary project dependency scopes without installed-runtime selection")
        _with_manifest { (manifestfile, _) =>
          val directory = manifestfile.getParentFile.getParentFile
          val out = directory / "target/cncf.d/runtime-classpath.txt"
          val absent = directory / "absent-target/cncf.d/runtime-classpath.txt"
          val previous = "ordinary project launcher export\n"
          IO.write(out, previous)
          val dependencies = _runtime_entries(manifestfile)

          When("the installed prerequisite checks ordinary project inputs")
          val existingresult = InstalledRuntimeClasspath.checkAndExport(
            dependencies, dependencies, dependencies, None, out)
          val absentresult = InstalledRuntimeClasspath.checkAndExport(
            dependencies, dependencies, dependencies, None, absent)

          Then("no launcher export or parent directory is created or rewritten")
          existingresult shouldBe None
          absentresult shouldBe None
          IO.read(out) shouldBe previous
          absent.getParentFile.exists shouldBe false
        }
      }
    }

    "binding rejection" which {
      "rejects missing modules and changed versions or classifiers" in {
        Given("an installation whose complete runtime closure must be present")
        _with_manifest { (_, manifest) =>
          val root = _entry("org.goldenport", "goldenport-cncf_3", "0.5.3-SNAPSHOT", None)
          val classified = _entry("org.example", "fixture", "1.0", Some("runtime"))

          When("dependency scopes omit or disagree with selected module identities")
          val missing = intercept[RuntimeException](InstalledRuntimeClasspath.bind(Vector(root), Some(manifest)))
          val version = intercept[RuntimeException](InstalledRuntimeClasspath.bind(Vector(
            _entry("org.goldenport", "goldenport-cncf_3", "other-SNAPSHOT", None), classified), Some(manifest)))
          val classifier = intercept[RuntimeException](InstalledRuntimeClasspath.bind(Vector(root,
            _entry("org.example", "fixture", "1.0", None)), Some(manifest)))

          Then("no ordinary repository file is silently substituted for a required installed module")
          missing.getMessage should include("absent from dependency scope")
          version.getMessage should include("version/classifier differs")
          classifier.getMessage should include("version/classifier differs")
        }
      }

      "rejects missing files, escaping paths and linked installation inputs" in {
        Given("a valid manifest and its copied regular runtime files")
        _with_manifest { (manifestfile, manifest) =>
          val original = IO.read(manifestfile)
          val installed = manifest.modules.head.file.toPath
          val source = installed.resolveSibling("mutable.jar")
          Files.move(installed, source)
          Files.createSymbolicLink(installed, source)

          When("a runtime file is linked, absent, or names an escaping relative path")
          val linked = intercept[RuntimeException](InstalledRuntimeClasspath.read(manifestfile))
          Files.delete(installed)
          val missing = intercept[RuntimeException](InstalledRuntimeClasspath.read(manifestfile))
          IO.write(manifestfile, original.replace("runtime/0000.jar", "../outside.jar"))
          val escaping = intercept[RuntimeException](InstalledRuntimeClasspath.read(manifestfile))

          Then("each invalid installation fails before dependency consumption")
          linked.getMessage should include("regular installed file")
          missing.getMessage should include("regular installed file")
          escaping.getMessage should include("manifest order")
        }
      }

      "requires the explicit manifest when required support is enabled" in {
        Given("required runtime support with no concrete manifest filename")

        When("plugin runtime configuration is interpreted")
        val error = intercept[RuntimeException](InstalledRuntimeClasspath.fromProperties(
          Map(InstalledRuntimeClasspath.requiredProperty -> "true")))

        Then("a missing binding is a build failure")
        error.getMessage should include("manifest is missing")
      }

      "accepts only canonical Coursier binary and five-part classifier coordinates" in {
        Given("one canonical classified coordinate and an unsupported four-part spelling")

        When("the module identities are interpreted")
        val coordinate = InstalledRuntimeClasspath.parseCoordinate("org.example:fixture:jar:runtime:1.0")
        val error = intercept[RuntimeException](InstalledRuntimeClasspath.parseCoordinate("org.example:fixture:runtime:1.0"))

        Then("the classifier is explicit and no compatibility spelling is invented")
        coordinate.classifier shouldBe Some("runtime")
        coordinate.revision shouldBe "1.0"
        error.getMessage should include("unsupported Coursier coordinate")
      }
    }
  }

  private def _entry(organization: String, name: String, revision: String, classifier: Option[String]): Attributed[File] =
    Attributed.blank(file(s"ordinary/$name-${classifier.getOrElse("binary")}.jar"))
      .put(moduleID.key, ModuleID(organization, name, revision))
      .put(artifact.key, Artifact(name, "jar", "jar").withClassifier(classifier))

  private def _runtime_entries(manifestfile: File): Vector[Attributed[File]] = {
    val directory = manifestfile.getParentFile.getParentFile
    Vector(
      Attributed(directory / "source-0.jar")(
        _entry("org.goldenport", "goldenport-cncf_3", "0.5.3-SNAPSHOT", None).metadata),
      Attributed(directory / "source-1.jar")(
        _entry("org.example", "fixture", "1.0", Some("runtime")).metadata)
    )
  }

  private def _with_manifest(body: (File, InstalledRuntimeClasspath.Manifest) => Unit): Unit = {
    val parent = file("target/sbt-cozy-test/work/installed-runtime")
    IO.createDirectory(parent)
    val directory = Files.createTempDirectory(parent.toPath, "manifest-").toFile.getCanonicalFile
    try {
      val installationid = UUID.randomUUID.toString
      val root = directory / installationid
      val modules = Vector(InstalledRuntimeClasspath.rootCoordinate, "org.example:fixture:jar:runtime:1.0")
      val entries = modules.zipWithIndex.map { case (coordinate, index) =>
        val relative = f"runtime/$index%04d.jar"
        IO.write(root / relative, "copied runtime fixture")
        IO.write(directory / s"source-$index.jar", "mutable runtime fixture")
        Json.obj("coordinate" -> Json.fromString(coordinate), "path" -> Json.fromString(relative),
          "source" -> Json.fromString((directory / s"source-$index.jar").getAbsolutePath))
      }
      IO.write(root / "components/textus-sm-workflow.car", "copied CAR fixture")
      val json = Json.obj("kind" -> Json.fromString("cncf.installed-runtime.v1"),
        "installation_id" -> Json.fromString(installationid), "update_id" -> Json.fromString(UUID.randomUUID.toString),
        "application" -> Json.fromString("sm-workflow"), "root_coordinate" -> Json.fromString(InstalledRuntimeClasspath.rootCoordinate),
        "repositories" -> Json.arr(Json.fromString("ivy2Local"), Json.fromString("central")),
        "modules" -> Json.arr(entries: _*), "component" -> Json.obj(
          "identity" -> Json.fromString(LocalApplicationRuntimeUpdate.componentIdentity),
          "coordinate" -> Json.fromString(LocalApplicationRuntimeUpdate.componentCoordinate),
          "path" -> Json.fromString("components/textus-sm-workflow.car"),
          "source" -> Json.fromString((directory / "actual.car").getAbsolutePath)))
      val manifestfile = root / "runtime-installation.json"
      IO.write(manifestfile, json.noSpaces)
      body(manifestfile, InstalledRuntimeClasspath.read(manifestfile))
    } finally IO.delete(directory)
  }
}
