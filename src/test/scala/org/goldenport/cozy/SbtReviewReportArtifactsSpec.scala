package org.goldenport.cozy

import java.nio.file.Files

import io.circe.parser.parse
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt.io.IO

/*
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
final class SbtReviewReportArtifactsSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy canonical Review artifacts" should {
    "write deterministic JSON, safe HTML, and location-bearing Finding SARIF" in {
      Given("one CBD-owned report with two Findings, only one of which has a location")
      val directory = Files.createTempDirectory("sbt-cozy-review-artifacts").toFile
      val response = SbtReviewCanonicalResponse(_report, "fail")

      When("the client renders and writes its local projections")
      val artifacts = try SbtReviewReportArtifacts.write(directory, response).fold(error => fail(error), identity) finally ()
      val json = IO.read(artifacts.canonicalJson)
      val html = IO.read(artifacts.html)
      val sarif = IO.read(artifacts.sarif)

      Then("all projections retain the one CBD gate and SARIF remains explicitly lossy")
      parse(json).toOption.flatMap(_.hcursor.get[String]("gateResult").toOption) shouldBe Some("fail")
      html should include("Gate: fail")
      html should include("&lt;unsafe&gt;")
      sarif should include("cozy.car.documentation-rationale")
      sarif should include("project.yaml")
      sarif should include("location-bearing-findings-only")
      sarif should not include "missing-location"
      SbtReviewReportArtifacts.gateFromArtifact(artifacts.canonicalJson) shouldBe Right("fail")
      IO.delete(directory)
    }

    "refuse a response whose outer gate disagrees with its canonical report" in {
      Given("one canonical report with a failing gate")

      When("a transport labels it as passing")
      val result = SbtReviewReportArtifacts.render(SbtReviewCanonicalResponse(_report, "pass"))

      Then("sbt-cozy does not substitute a gate result")
      result shouldBe Left("cbd-review-response-gate-mismatch")
    }
  }

  private val _report =
    """{"schemaVersion":"textus.cbd.review-report.v1","documentType":"review-report","reportId":"report-example","gate":{"result":"fail"},"observations":[{"id":"finding-location","type":"finding","rule":{"id":"cozy.car.documentation-rationale"},"message":"<unsafe>","severity":"medium","locations":[{"path":"project.yaml","line":12}]},{"id":"missing-location","type":"finding","rule":{"id":"cozy.car.missing-location"},"message":"No location","severity":"low","locations":[]}]}"""
}
