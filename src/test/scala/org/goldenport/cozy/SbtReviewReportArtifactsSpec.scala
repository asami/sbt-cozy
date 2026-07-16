package org.goldenport.cozy

import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import io.circe.Printer
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
      val response = SbtReviewCanonicalResponse(_report, "fail", Some(_attestation))

      When("the client renders and writes its local projections")
      val artifacts = try SbtReviewReportArtifacts.write(directory, response).fold(error => fail(error), identity) finally ()
      val json = IO.read(artifacts.canonicalJson)
      val html = IO.read(artifacts.html)
      val sarif = IO.read(artifacts.sarif)
      val attestation = IO.read(artifacts.attestation)

      Then("all projections retain the one CBD gate and SARIF remains explicitly lossy")
      parse(json).toOption.flatMap(_.hcursor.get[String]("gateResult").toOption) shouldBe Some("fail")
      html should include("Gate: fail")
      html should include("&lt;unsafe&gt;")
      sarif should include("cozy.car.documentation-rationale")
      sarif should include("project.yaml")
      sarif should include("location-bearing-findings-only")
      sarif should not include "missing-location"
      attestation should include("attestation-example")
      SbtReviewReportArtifacts.gateFromArtifact(artifacts.canonicalJson) shouldBe Right("fail")
      IO.delete(directory)
    }

    "refuse a response whose outer gate disagrees with its canonical report" in {
      Given("one canonical report with a failing gate")

      When("a transport labels it as passing")
      val result = SbtReviewReportArtifacts.render(SbtReviewCanonicalResponse(_report, "pass", Some(_attestation)))

      Then("sbt-cozy does not substitute a gate result")
      result shouldBe Left("cbd-review-response-gate-mismatch")
    }

    "refuse an attestation that is not bound to the canonical report" in {
      Given("an attestation with another report digest")

      When("sbt-cozy receives the otherwise valid canonical response")
      val result = SbtReviewReportArtifacts.render(SbtReviewCanonicalResponse(
        _report,
        "fail",
        Some(_attestation_for("sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"))
      ))

      Then("CI does not retain a drifting attestation")
      result shouldBe Left("cbd-review-attestation-binding-invalid")
    }

    "accept the same provider bindings when the report and attestation order them differently" in {
      Given("a canonical report and attestation that bind Cozy and sbt-cozy in different orders")
      val report = _with_execution_providers(_report, Vector(_cozy_provider, _sbt_provider))
      val attestation = _with_attestation_providers(Vector(_sbt_provider, _cozy_provider))

      When("CBD returns the canonical response")
      val result = SbtReviewReportArtifacts.render(SbtReviewCanonicalResponse(report, "fail", Some(attestation)))

      Then("sbt-cozy accepts the equivalent provider-binding set")
      result.isRight shouldBe true
    }

    "refuse a canonical response containing a credential-shaped value" in {
      Given("a report whose provider message contains a bearer credential")

      When("the client attempts to materialize review artifacts")
      val result = SbtReviewReportArtifacts.render(SbtReviewCanonicalResponse(
        _report.replace("<unsafe>", "Bearer secret-value-1234567890123456"),
        "fail",
        Some(_attestation)
      ))

      Then("no report, HTML, SARIF, or attestation artifact is retained")
      result shouldBe Left("cbd-review-artifact-sensitive-value")
    }
  }

  private val _report =
    """{"schemaVersion":"textus.cbd.review-report.v1","documentType":"review-report","reportId":"report-example","reportDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","reviewId":"review-example","profile":"development","target":{"digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"execution":{"providers":[{"provider":{"id":"cozy","version":"0.3.0"},"ruleSet":{"id":"cozy.car-review","version":"1.0.0"},"bundleDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]},"gate":{"result":"fail"},"observations":[{"id":"finding-location","type":"finding","rule":{"id":"cozy.car.documentation-rationale"},"message":"<unsafe>","severity":"medium","locations":[{"path":"project.yaml","line":12}]},{"id":"missing-location","type":"finding","rule":{"id":"cozy.car.missing-location"},"message":"No location","severity":"low","locations":[]}]}"""
  private val _attestation = _attestation_for("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
  private val _cozy_provider = parse(_report).fold(error => fail(error.getMessage), identity).hcursor.downField("execution").downField("providers").as[Vector[io.circe.Json]].fold(error => fail(error.getMessage), _.head)
  private val _sbt_provider = parse("""{"provider":{"id":"sbt-cozy","version":"0.1.0-SNAPSHOT"},"ruleSet":{"id":"sbt-cozy.build-evidence","version":"1.0.0"},"bundleDigest":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}""").fold(error => fail(error.getMessage), identity)

  private def _attestation_for(reportdigest: String): String = {
    val base = parse(
      s"""{"schemaVersion":"textus.cbd.review-report.v1","documentType":"review-attestation","attestationId":"attestation-example","createdAt":"2026-07-16T00:00:00Z","reviewId":"review-example","reportId":"report-example","reportDigest":"$reportdigest","targetDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","profile":"development","providers":[{"provider":{"id":"cozy","version":"0.3.0"},"ruleSet":{"id":"cozy.car-review","version":"1.0.0"},"bundleDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}],"gate":{"result":"fail"}}"""
    ).fold(error => fail(error.getMessage), identity)
    val bytes = Printer.noSpaces.copy(sortKeys = true).print(base).getBytes(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString
    Printer.noSpaces.print(base.mapObject(_.add("attestationDigest", io.circe.Json.fromString(s"sha256:$digest"))))
  }

  private def _with_execution_providers(document: String, providers: Vector[io.circe.Json]): String = {
    val parsed = parse(document).fold(error => fail(error.getMessage), identity)
    val execution = parsed.hcursor.downField("execution").focus.fold(fail("execution is missing"))(identity)
    Printer.noSpaces.print(parsed.mapObject(_.add("execution", execution.mapObject(_.add("providers", io.circe.Json.fromValues(providers))))))
  }

  private def _with_attestation_providers(providers: Vector[io.circe.Json]): String = {
    val parsed = parse(_attestation).fold(error => fail(error.getMessage), identity)
    val unsigned = parsed.mapObject(_.remove("attestationDigest").add("providers", io.circe.Json.fromValues(providers)))
    val bytes = Printer.noSpaces.copy(sortKeys = true).print(unsigned).getBytes(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString
    Printer.noSpaces.print(unsigned.mapObject(_.add("attestationDigest", io.circe.Json.fromString(s"sha256:$digest"))))
  }
}
