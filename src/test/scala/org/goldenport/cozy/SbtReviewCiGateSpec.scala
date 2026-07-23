package org.goldenport.cozy

import io.circe.{Json, Printer}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 23, 2026
 * @version Jul. 23, 2026
 * @author  ASAMI, Tomoharu
 */
final class SbtReviewCiGateSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy CI Review gate" should {
    "preserve each CBD-owned pass, fail, and unknown result with its contract exit code" in {
      Given("three accepted artifact manifests")
      val cases = Vector("pass" -> 0, "fail" -> 2, "unknown" -> 3)

      When("the CI gate reads each manifest")
      val results = cases.map { case (result, exitcode) =>
        SbtReviewCiGate.fromText(_manifest(result, exitcode))
      }

      Then("it uses the manifest result and never derives a local conclusion")
      results shouldBe cases.map { case (result, exitcode) => Right(SbtReviewCiGate(result, exitcode)) }
    }

    "refuse a manifest whose exit code disagrees with the CBD-owned gate" in {
      Given("one failing gate with a substituted passing exit code")

      When("the CI consumer validates the accepted manifest")
      val result = SbtReviewCiGate.fromText(_manifest("fail", 0))

      Then("the task cannot reinterpret the Review result")
      result shouldBe Left("cbd-review-ci-manifest-exit-code-mismatch")
    }

    "refuse incomplete or path-substituted manifests even when their gate and exit code agree" in {
      Given("one incomplete manifest and one complete manifest with a substituted artifact path")
      val incomplete = Printer.noSpaces.print(Json.obj(
        "schemaVersion" -> Json.fromString("textus.cbd.review-ci-artifact.v1"),
        "documentType" -> Json.fromString("review-ci-artifact-manifest"),
        "gate" -> Json.obj("result" -> Json.fromString("pass")),
        "exitCode" -> Json.fromInt(0)
      ))
      val substituted = _manifest("pass", 0).replace("report.pdf", "substituted.pdf")

      When("the CI consumer validates its artifact-manifest boundary")
      val incompleteresult = SbtReviewCiGate.fromText(incomplete)
      val substitutedresult = SbtReviewCiGate.fromText(substituted)

      Then("neither document is accepted as a CI gate input")
      incompleteresult shouldBe Left("cbd-review-ci-manifest-shape-invalid")
      substitutedresult shouldBe Left("cbd-review-ci-manifest-artifact-path-invalid")
    }
  }

  private val _digest = "sha256:" + ("a" * 64)

  private def _manifest(result: String, exitcode: Int): String =
    Printer.noSpaces.print(Json.obj(
      "schemaVersion" -> Json.fromString("textus.cbd.review-ci-artifact.v1"),
      "documentType" -> Json.fromString("review-ci-artifact-manifest"),
      "reviewId" -> Json.fromString("review-example"),
      "reportId" -> Json.fromString("report-example"),
      "reportDigest" -> Json.fromString(_digest),
      "targetDigest" -> Json.fromString(_digest),
      "attestationDigest" -> Json.fromString(_digest),
      "profile" -> Json.fromString("development"),
      "limitations" -> Json.arr(),
      "gate" -> Json.obj("policyId" -> Json.fromString("cbd.default"), "policyVersion" -> Json.fromString("1.0.0"), "result" -> Json.fromString(result)),
      "exitCode" -> Json.fromInt(exitcode),
      "artifactDirectory" -> Json.fromString("target/cbd-review/" + _digest.replace(':', '-')),
      "retention" -> Json.obj("mode" -> Json.fromString("ci-workspace"), "preserveOn" -> Json.arr(Json.fromString("pass"), Json.fromString("fail"), Json.fromString("unknown")), "publication" -> Json.fromString("not-triggered"), "distribution" -> Json.fromString("not-triggered"), "deployment" -> Json.fromString("not-triggered")),
      "artifacts" -> Json.obj(
        "canonicalResponse" -> _artifact("canonical-response.json"),
        "report" -> _artifact("report.json"),
        "attestation" -> _artifact("attestation.json"),
        "markdown" -> _artifact("report.md"),
        "pdf" -> _artifact("report.pdf"),
        "html" -> _artifact("report.html"),
        "sarif" -> _artifact("report.sarif")
      )
    ))

  private def _artifact(path: String): Json =
    Json.obj("path" -> Json.fromString(path), "sha256" -> Json.fromString(_digest))
}
