package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
final class SbtReviewEvidenceSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy Review evidence provider" should {
    "render deterministic attributable task evidence without a quality assessment" in {
      Given("one project target and all required build task outcomes")
      val target = SbtReviewEvidenceTarget(
        organization = Some("org.example"),
        name = "review-fixture",
        version = "1.2.3",
        digest = "sha256:" + ("a" * 64)
      )
      val outcomes = Vector(
        SbtReviewTaskResult("test", "succeeded"),
        SbtReviewTaskResult("car-build", "succeeded"),
        SbtReviewTaskResult("generation", "succeeded"),
        SbtReviewTaskResult("dependency-resolution", "succeeded"),
        SbtReviewTaskResult("compilation", "succeeded"),
        SbtReviewTaskResult("task-result", "succeeded")
      )

      When("the same outcomes arrive in distinct task order")
      val first = SbtReviewEvidence.render(target, "0.1.15-SNAPSHOT", outcomes)
      val second = SbtReviewEvidence.render(target, "0.1.15-SNAPSHOT", outcomes.reverse)

      Then("the provider documents remain deterministic and carry no quality conclusion")
      first shouldBe second
      first.descriptor should include ("\"provider\":{\"id\":\"sbt-cozy\"")
      first.descriptor should include ("\"observationKinds\":[\"unknown\"]")
      first.bundle should include ("\"task\":\"generation\"")
      first.bundle should include ("\"task\":\"compilation\"")
      first.bundle should include ("\"task\":\"test\"")
      first.bundle should include ("\"task\":\"dependency-resolution\"")
      first.bundle should include ("\"task\":\"car-build\"")
      first.bundle should include ("\"task\":\"task-result\"")
      first.bundle should include ("\"limitations\":[{\"code\":\"sbt-evidence-no-quality-assessment\"")
      first.bundle should include ("\"observations\":[]")
      first.bundle should not include "\"gate\""
      first.bundle should not include "\"assessment\""
    }

    "derive a source digest from source content rather than generated output" in {
      Given("equivalent project roots with distinct generated target files")
      _with_temp_dir { root =>
        val first = root.resolve("first")
        val second = root.resolve("second")
        _write(first.resolve("src/main/cozy/model.cml"), "entity Customer")
        _write(second.resolve("src/main/cozy/model.cml"), "entity Customer")
        _write(first.resolve("target/generated.scala"), "first")
        _write(second.resolve("target/generated.scala"), "second")

        When("sbt-cozy identifies the two review targets")
        val firstdigest = SbtReviewEvidence.sourceDigest(first.toFile)
        val seconddigest = SbtReviewEvidence.sourceDigest(second.toFile)

        Then("generated output is excluded while source content remains attributable")
        firstdigest shouldBe seconddigest
        firstdigest should startWith ("sha256:")
      }
    }

    "reject an unbounded source target before reading it into the digest" in {
      Given("a project source file above the provider input limit")
      _with_temp_dir { root =>
        val oversized = root.resolve("src/main/cozy/oversized.cml")
        _write(oversized, "x" * ((16 * 1024 * 1024) + 1))

        When("sbt-cozy derives the Review target digest")
        val exception = intercept[IllegalArgumentException] {
          SbtReviewEvidence.sourceDigest(root.toFile)
        }

        Then("the provider refuses the unbounded input deterministically")
        exception.getMessage should include ("Review target exceeds")
      }
    }

    "write the descriptor, request, and evidence bundle under the selected target directory" in {
      Given("rendered provider documents")
      _with_temp_dir { root =>
        val target = SbtReviewEvidenceTarget(None, "fixture", "0.1.0", "sha256:" + ("b" * 64))
        val artifacts = SbtReviewEvidence.render(target, "0.1.15-SNAPSHOT", Vector(SbtReviewTaskResult("task-result", "succeeded")))

        When("the provider materializes its bounded evidence output")
        val bundle = SbtReviewEvidence.write(root.toFile, artifacts)

        Then("all provider documents are present with exact rendered content")
        artifacts.request should not include "\"organization\""
        Files.readString(root.resolve("provider-descriptor.json"), StandardCharsets.UTF_8) shouldBe artifacts.descriptor + "\n"
        Files.readString(root.resolve("provider-request.json"), StandardCharsets.UTF_8) shouldBe artifacts.request + "\n"
        Files.readString(bundle.toPath, StandardCharsets.UTF_8) shouldBe artifacts.bundle + "\n"
      }
    }
  }

  private def _write(path: Path, value: String): Unit = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))
  }

  private def _with_temp_dir[A](f: Path => A): A = {
    val directory = Files.createTempDirectory("sbt-cozy-review-evidence-")
    try f(directory)
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
