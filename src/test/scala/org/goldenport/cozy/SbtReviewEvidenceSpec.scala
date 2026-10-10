package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Oct. 10, 2026
 * @author  ASAMI, Tomoharu
 */
final class SbtReviewEvidenceSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy Review evidence provider" should {
    "render deterministic attributable task evidence without a quality assessment" in {
      Given("one project target and all required build task outcomes")
      val target = SbtReviewEvidenceTarget(
        organization = Some("org.example"),
        name = "review-fixture",
        version = "1.2.3"
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
      val first = SbtReviewEvidence.render("review-sbt-cozy-fixture", target, "0.1.15-SNAPSHOT", outcomes)
      val second = SbtReviewEvidence.render("review-sbt-cozy-fixture", target, "0.1.15-SNAPSHOT", outcomes.reverse)

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

    "write the descriptor, request, and evidence bundle under the selected target directory" in {
      Given("rendered provider documents")
      _with_temp_dir { root =>
        val target = SbtReviewEvidenceTarget(None, "fixture", "0.1.0")
        val artifacts = SbtReviewEvidence.render("review-sbt-cozy-fixture", target, "0.1.15-SNAPSHOT", Vector(SbtReviewTaskResult("task-result", "succeeded")))

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
