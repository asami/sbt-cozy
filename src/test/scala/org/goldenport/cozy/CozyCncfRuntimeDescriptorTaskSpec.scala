package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import sbt._
import sbt.cozyfixture.CozyTaskEvaluation
import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import CozyPlugin.autoImport._

/*
 * @since   Oct. 5, 2026
 * @version Oct. 5, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyCncfRuntimeDescriptorTaskSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The CNCF runtime descriptor preparation task" should {
    "selected component sources" which {
      "finish preparation once before returning the written descriptor" in {
        Given("nonempty CML sources with the cozy backend and cncf target")
        _with_fixture(true) { fixture =>
          val body = "runtime: cncf\nversion: prepared-source\n"
          val task = _selection_task(fixture, "cozy", "cncf", () => _prepare(fixture, Some(body)))

          When("the actual production selection task is evaluated by the SBT task engine")
          val result = CozyTaskEvaluation.run(task)

          Then("preparation completes exactly once and returns its current descriptor")
          result shouldBe Value(Some(fixture.descriptor))
          fixture.calls.get shouldBe 1
          Files.readString(fixture.descriptor.toPath) shouldBe body
        }
      }

      "retain None returned by selected preparation" in {
        Given("component sources whose build-owned preparation selects the default JAR input")
        _with_fixture(true) { fixture =>
          val task = _selection_task(fixture, "cozy", "cncf", () => _prepare(fixture, None))

          When("the production selection task evaluates that preparation")
          val result = CozyTaskEvaluation.run(task)

          Then("preparation runs once and its None result is retained")
          result shouldBe Value(None)
          fixture.calls.get shouldBe 1
          Files.exists(fixture.descriptor.toPath) shouldBe false
        }
      }

      "normalize backend and target before selecting preparation" in {
        Given("component sources with surrounding whitespace and uppercase backend and target names")
        _with_fixture(true) { fixture =>
          val body = "runtime: cncf\nversion: normalized\n"
          val task = _selection_task(fixture, " COZY ", " CNCF ", () => _prepare(fixture, Some(body)))

          When("the production selection task interprets the configured backend and target")
          val result = CozyTaskEvaluation.run(task)

          Then("the normalized cozy/cncf route prepares its descriptor exactly once")
          result shouldBe Value(Some(fixture.descriptor))
          fixture.calls.get shouldBe 1
          Files.readString(fixture.descriptor.toPath) shouldBe body
        }
      }

      "propagate the selected preparation failure without a default-input fallback" in {
        Given("component sources and a preparation task that throws a specific sentinel failure")
        _with_fixture(true) { fixture =>
          val sentinel = new PreparationFailure
          val task = _selection_task(fixture, "cozy", "cncf", () => throw sentinel)

          When("the production selection task invokes the failing preparation")
          val result = CozyTaskEvaluation.run(task)

          Then("SBT reports the identical direct cause after exactly one preparation call")
          _direct_causes(result) should contain (sentinel)
          fixture.calls.get shouldBe 1
          Files.exists(fixture.descriptor.toPath) shouldBe false
        }
      }
    }

    "generation outside descriptor preparation" which {
      "skip preparation for library sources" in {
        Given("nonempty CML sources using the cozy backend and library target")
        _with_fixture(true) { fixture =>
          val task = _selection_task(fixture, "cozy", "library", () => _prepare(fixture, Some("library\n")))

          When("the production selection task evaluates library generation")
          val result = CozyTaskEvaluation.run(task)

          Then("the result is None and preparation is never invoked")
          result shouldBe Value(None)
          fixture.calls.get shouldBe 0
          Files.exists(fixture.descriptor.toPath) shouldBe false
        }
      }

      "skip preparation for legacy component sources" in {
        Given("nonempty CML sources using the legacy backend and cncf target")
        _with_fixture(true) { fixture =>
          val task = _selection_task(fixture, "legacy", "cncf", () => _prepare(fixture, Some("legacy\n")))

          When("the production selection task evaluates legacy generation")
          val result = CozyTaskEvaluation.run(task)

          Then("the result is None and preparation is never invoked")
          result shouldBe Value(None)
          fixture.calls.get shouldBe 0
          Files.exists(fixture.descriptor.toPath) shouldBe false
        }
      }

      "skip preparation when no CML sources are present" in {
        Given("an empty source directory using the cozy backend and cncf target")
        _with_fixture(false) { fixture =>
          val task = _selection_task(fixture, "cozy", "cncf", () => _prepare(fixture, Some("empty\n")))

          When("the production selection task evaluates the empty source set")
          val result = CozyTaskEvaluation.run(task)

          Then("the result is None and preparation is never invoked")
          result shouldBe Value(None)
          fixture.calls.get shouldBe 0
          Files.exists(fixture.descriptor.toPath) shouldBe false
        }
      }

      "skip preparation for an unsupported backend" in {
        Given("component sources configured with an unsupported generator backend")
        _with_fixture(true) { fixture =>
          val task = _selection_task(fixture, "unsupported", "cncf", () => _prepare(fixture, Some("unsupported\n")))

          When("the production descriptor selection task evaluates that backend")
          val result = CozyTaskEvaluation.run(task)

          Then("descriptor selection returns None without preparing an input")
          result shouldBe Value(None)
          fixture.calls.get shouldBe 0
          Files.exists(fixture.descriptor.toPath) shouldBe false
        }
      }

      "reject a CAR library target before invoking preparation" in {
        Given("CAR project metadata, component sources and a library target")
        _with_fixture(true) { fixture =>
          val metadata = CozyProjectConfig(Map("project.kind" -> "car"), Map.empty)
          val task = _selection_task(
            fixture, "cozy", "library", () => _prepare(fixture, Some("invalid\n")), metadata
          )

          When("the production selection task validates the configured target")
          val result = CozyTaskEvaluation.run(task)

          Then("SBT retains the CAR target admission failure and preparation remains untouched")
          _direct_causes(result).map(_.getMessage) should contain (
            "[sbt-cozy] CAR/SAR projects cannot use cozyGenerationTarget := \"library\"."
          )
          fixture.calls.get shouldBe 0
          Files.exists(fixture.descriptor.toPath) shouldBe false
        }
      }
    }

    "the normalized routing contract" which {
      "prepare only nonempty cozy/cncf sources across forty bounded cases" in {
        Given("backend, target and source-presence combinations with optional varied prepared descriptors")
        val cases = for {
          backend <- Gen.oneOf("cozy", " COZY ", "legacy", " LEGACY ", "unsupported")
          target <- Gen.oneOf("cncf", " CNCF ", "library", " LIBRARY ")
          hassources <- Gen.oneOf(true, false)
          body <- Gen.option(Gen.choose(0, 24).flatMap { size =>
            Gen.listOfN(size, Gen.oneOf("a", "Z", "0", "é", "運", "🚀")).map { characters =>
              "runtime: cncf\nlabel: \"" + characters.mkString + "\"\n"
            }
          })
        } yield (backend, target, hassources, body)
        val property = Prop.forAll(cases) { input =>
          val (backend, target, hassources, body) = input
          Given(s"backend '$backend', target '$target', source presence $hassources and optional prepared content")
          _with_fixture(hassources) { fixture =>
            val selected = backend.trim.toLowerCase == "cozy" &&
              target.trim.toLowerCase(java.util.Locale.ROOT) == "cncf" && hassources
            val task = _selection_task(fixture, backend, target, () => _prepare(fixture, body))

            When("the actual production task interprets this generated configuration")
            val result = CozyTaskEvaluation.run(task)

            Then("only the selected route invokes preparation and preserves its exact optional result")
            result shouldBe Value(if (selected) body.map(_ => fixture.descriptor) else None)
            fixture.calls.get shouldBe (if (selected) 1 else 0)
            Files.exists(fixture.descriptor.toPath) shouldBe (selected && body.nonEmpty)
            if (selected)
              body.foreach(content => Files.readString(fixture.descriptor.toPath) shouldBe content)
            true
          }
        }

        When("ScalaCheck evaluates forty configurations through the SBT task engine")
        val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(40).withWorkers(1), property)

        Then("all configurations preserve preparation activation and optional descriptor results")
        withClue(result.toString) { result.passed shouldBe true }
      }
    }
  }

  private final case class Fixture(dir: Path, sourcedir: File, descriptor: File, calls: AtomicInteger)
  private final class PreparationFailure extends RuntimeException("descriptor preparation sentinel")

  private def _selection_task(
    fixture: Fixture,
    backend: String,
    target: String,
    preparation: () => Option[File],
    metadata: CozyProjectConfig = CozyProjectConfig.empty
  ): Task[Option[File]] = {
    val scopedkey = CozyPlugin._cncf_runtime_descriptor_selection.scopedKey
    val production = CozyPlugin.projectSettings.find(_.key == scopedkey)
      .getOrElse(fail("production descriptor selection setting is unavailable"))
    var data = Option.empty[sbt.internal.util.Settings[Scope]]
    val settings: Seq[Def.Setting[_]] = Seq(
      production,
      sbt.std.FullInstance.settingsData := data.getOrElse(fail("fixture settings have not been initialized")),
      cozySourceDir := fixture.sourcedir,
      cozyGeneratorBackend := backend,
      cozyProjectMetadata := metadata,
      cozyGenerationTarget := target,
      cozyCncfRuntimeDescriptorInput := {
        fixture.calls.incrementAndGet()
        preparation()
      }
    )
    val initialized = Def.makeWithCompiledMap(settings)(scope => Seq(scope), _ => Nil, Def.showFullKey)
    data = Some(initialized._2)
    initialized._2.get(scopedkey.scope, scopedkey.key)
      .getOrElse(fail("initialized production descriptor selection task is unavailable"))
  }

  private def _prepare(fixture: Fixture, body: Option[String]): Option[File] =
    body.map { content =>
      Files.write(fixture.descriptor.toPath, content.getBytes(StandardCharsets.UTF_8))
      fixture.descriptor
    }

  private def _direct_causes(result: Result[Option[File]]): Vector[Throwable] = result match {
    case Inc(incomplete) => Incomplete.linearize(incomplete).flatMap(_.directCause).toVector
    case Value(_) => fail("SBT was expected to report an incomplete task")
  }

  private def _with_fixture[A](hassources: Boolean)(f: Fixture => A): A = {
    val workroot = Files.createDirectories(Paths.get("target/sbt-cozy-test/work").toAbsolutePath)
    val dir = Files.createTempDirectory(workroot, "descriptor-task-")
    try {
      val sourcedir = Files.createDirectory(dir.resolve("sources"))
      if (hassources)
        Files.write(sourcedir.resolve("component.cml"), "fixture source\n".getBytes(StandardCharsets.UTF_8))
      f(Fixture(dir, sourcedir.toFile, dir.resolve("prepared-runtime.yaml").toFile, new AtomicInteger(0)))
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
