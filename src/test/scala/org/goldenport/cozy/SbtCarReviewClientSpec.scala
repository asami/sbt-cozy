package org.goldenport.cozy

import java.io.File

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
final class SbtCarReviewClientSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy CAR Review client" should {
    "use fixed Cozy descriptor and stdin-evidence command templates" in {
      Given("one configured Cozy command prefix")
      val transport = new SbtCozyCommandReviewTransport(Seq("cozy"))

      When("the local client is asked to collect its provider documents")
      val outcome = transport.collect(new File("missing-project-root"), "{\"reviewId\":\"review-cozy-001\"}", "0.1.15-SNAPSHOT")

      Then("an invalid root is rejected before any caller-provided command or path reaches CBD")
      outcome shouldBe Left("cozy-review-project-root-invalid")
    }

    "collect local Cozy evidence and submit only paired provider documents to CBD" in {
      Given("local Cozy evidence, sbt evidence, and a recording CBD submission transport")
      val artifacts = SbtReviewEvidence.render(
        SbtReviewEvidenceTarget(Some("org.example"), "fixture", "1.0.0", "sha256:" + ("a" * 64)),
        "0.1.15-SNAPSHOT",
        Vector(SbtReviewTaskResult("task-result", "succeeded"))
      )
      val cozy = new RecordingCozyTransport()
      val cbd = new RecordingSubmissionTransport()

      When("the client drives the local/CI Review route")
      val response = SbtCarReviewClient.submit(new File("."), "{\"reviewId\":\"review-cozy-001\"}", "0.1.15-SNAPSHOT", artifacts, cozy, cbd).fold(error => fail(error), identity)

      Then("CBD receives exactly Cozy and sbt provider documents, never the workspace path")
      cozy.called shouldBe true
      cbd.submission.map(_.providerId) shouldBe Vector("cozy", "sbt-cozy")
      cbd.submission.map(_.bundle).forall(_.contains("workspace")) shouldBe false
      response.gate shouldBe "pass"
    }

    "refuse a CBD response without both canonical report and gate" in {
      Given("a paired submission transport with an incomplete response")
      val artifacts = SbtReviewEvidence.render(SbtReviewEvidenceTarget(None, "fixture", "1.0.0", "sha256:" + ("a" * 64)), "0.1.15-SNAPSHOT", Vector(SbtReviewTaskResult("task-result", "succeeded")))

      When("the transport omits the CBD gate")
      val response = SbtCarReviewClient.submit(new File("."), "{\"reviewId\":\"review-cozy-001\"}", "0.1.15-SNAPSHOT", artifacts, new RecordingCozyTransport(), new RecordingSubmissionTransport(SbtReviewCanonicalResponse("{\"report\":true}", "")))

      Then("sbt-cozy does not invent a local gate result")
      response shouldBe Left("cbd-review-response-incomplete")
    }
  }

  private final class RecordingCozyTransport extends SbtLocalCozyReviewTransport {
    var called = false

    def collect(projectRoot: File, providerRequest: String, providerVersion: String): Either[String, SbtReviewProviderDocuments] = {
      called = true
      Right(SbtReviewProviderDocuments("cozy", "{\"descriptor\":true}", providerRequest, "{\"bundle\":true}"))
    }
  }

  private final class RecordingSubmissionTransport(response: SbtReviewCanonicalResponse = SbtReviewCanonicalResponse("{\"report\":true}", "pass")) extends SbtReviewSubmissionTransport {
    var submission = Vector.empty[SbtReviewProviderDocuments]

    def submit(value: SbtReviewPairedSubmission): Either[String, SbtReviewCanonicalResponse] = {
      submission = value.providers
      Right(response)
    }
  }
}
