package org.goldenport.cozy

import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import io.circe.Printer
import io.circe.parser.parse
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt.io.IO

/*
 * @since   Jul. 16, 2026
 * @version Jul. 23, 2026
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

    "materialize one immutable CI artifact directory from CBD-owned projections" in {
      Given("one canonical CBD response with its Markdown and PDF bundle")
      val root = Files.createTempDirectory("sbt-cozy-review-ci-artifacts").toFile
      val evidence = new File(root, "sbt-cozy")
      val canonical = _canonical_response
      val bundle = _artifact_bundle()
      val response = SbtReviewCanonicalResponse(_report, "fail", Some(_attestation), Some(canonical), Some(bundle))

      When("sbt-cozy materializes the CI artifact attempt")
      val artifacts = try SbtReviewCiArtifactMaterializer.write(evidence, response).fold(error => fail(error), identity) finally ()
      val manifest = parse(IO.read(artifacts.manifest)).fold(error => fail(error.getMessage), identity)

      Then("canonical, report, attestation, Markdown, PDF, projections, and manifest share one digest-keyed directory")
      Vector(artifacts.canonicalJson, artifacts.report, artifacts.attestation, artifacts.markdown, artifacts.pdf, artifacts.html, artifacts.sarif, artifacts.manifest).forall(_.isFile) shouldBe true
      artifacts.canonicalJson.getParentFile shouldBe artifacts.manifest.getParentFile
      manifest.hcursor.get[String]("documentType").toOption shouldBe Some("review-ci-artifact-manifest")
      manifest.hcursor.get[Int]("exitCode").toOption shouldBe Some(2)
      manifest.hcursor.get[Vector[String]]("limitations").toOption shouldBe Some(Vector("pdf.unsupported-character"))
      manifest.hcursor.downField("artifacts").keys.map(_.toSet) shouldBe Some(Set("canonicalResponse", "report", "attestation", "markdown", "pdf", "html", "sarif"))
      IO.read(artifacts.markdown) should include("CBD CAR Review")
      Files.readAllBytes(artifacts.pdf.toPath).take(4).map(_.toChar).mkString shouldBe "%PDF"
      IO.delete(root)
    }

    "refuse an oversized CBD-owned artifact before creating a CI attempt directory" in {
      Given("one canonical CBD response with Markdown beyond the artifact bound")
      val root = Files.createTempDirectory("sbt-cozy-review-ci-artifact-limit").toFile
      val evidence = new File(root, "sbt-cozy")
      val response = SbtReviewCanonicalResponse(_report, "fail", Some(_attestation), Some(_canonical_response), Some(_artifact_bundle("#" + ("x" * SbtReviewCiArtifactMaterializer.MAX_ARTIFACT_BYTES))))

      When("sbt-cozy admits the CBD artifact bundle")
      val result = SbtReviewCiArtifactMaterializer.write(evidence, response)

      Then("it refuses the bounded output without retaining an attempt directory")
      result shouldBe Left("cbd-review-artifact-output-too-large")
      root.listFiles.toVector shouldBe empty
      IO.delete(root)
    }

    "refuse an oversized canonical response before creating a CI attempt directory" in {
      Given("one canonical response whose retained wire document exceeds the artifact bound")
      val root = Files.createTempDirectory("sbt-cozy-review-ci-canonical-limit").toFile
      val evidence = new File(root, "sbt-cozy")
      val oversizedcanonical = _canonical_response + ("x" * SbtReviewCiArtifactMaterializer.MAX_ARTIFACT_BYTES)
      val response = SbtReviewCanonicalResponse(_report, "fail", Some(_attestation), Some(oversizedcanonical), Some(_artifact_bundle()))

      When("sbt-cozy validates every artifact before opening a temporary attempt directory")
      val result = SbtReviewCiArtifactMaterializer.write(evidence, response)

      Then("the oversized canonical output is refused without retaining any CI attempt")
      result shouldBe Left("cbd-review-artifact-output-too-large")
      root.listFiles.toVector shouldBe empty
      IO.delete(root)
    }

    "refuse an oversized PDF Base64 value before decoding or creating a CI attempt directory" in {
      Given("one artifact bundle whose encoded PDF length proves it exceeds the artifact bound")
      val root = Files.createTempDirectory("sbt-cozy-review-ci-pdf-limit").toFile
      val evidence = new File(root, "sbt-cozy")
      val oversizedpdfbase64 = "A" * (SbtReviewCiArtifactMaterializer.MAX_PDF_BASE64_CHARACTERS + 1)
      val response = SbtReviewCanonicalResponse(_report, "fail", Some(_attestation), Some(_canonical_response), Some(_artifact_bundle(pdfbase64 = oversizedpdfbase64)))

      When("sbt-cozy checks the encoded PDF bound before calling the decoder")
      val result = SbtReviewCiArtifactMaterializer.write(evidence, response)

      Then("the bundle is rejected without a decoded oversized payload or retained CI attempt")
      result shouldBe Left("cbd-review-artifact-output-too-large")
      root.listFiles.toVector shouldBe empty
      IO.delete(root)
    }

    "refuse multiline credential-shaped text from both CBD renderings and manifest limitations" in {
      Given("two CBD artifact bundles whose Markdown or limitation contains a credential after a line break")
      val root = Files.createTempDirectory("sbt-cozy-review-ci-artifact-redaction").toFile
      val evidence = new File(root, "sbt-cozy")
      val markdownresponse = SbtReviewCanonicalResponse(_report, "fail", Some(_attestation), Some(_canonical_response), Some(_artifact_bundle("# CBD CAR Review\nBearer secret-value-1234567890123456")))
      val limitationresponse = SbtReviewCanonicalResponse(_report, "fail", Some(_attestation), Some(_canonical_response), Some(_artifact_bundle(limitations = Vector("provider limitation\nBearer secret-value-1234567890123456"))))

      When("sbt-cozy checks every CBD-owned text field before materialization")
      val markdownresult = SbtReviewCiArtifactMaterializer.write(evidence, markdownresponse)
      val limitationresult = SbtReviewCiArtifactMaterializer.write(evidence, limitationresponse)

      Then("neither rendering nor manifest limitations can carry a multiline credential")
      markdownresult shouldBe Left("cbd-review-artifact-sensitive-value")
      limitationresult shouldBe Left("cbd-review-artifact-sensitive-value")
      root.listFiles.toVector shouldBe empty
      IO.delete(root)
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

    "refuse multiline credential-shaped values from a canonical Report or attestation" in {
      Given("a canonical Report and attestation whose text contains a bearer credential after a line break")
      val reportresponse = SbtReviewCanonicalResponse(
        _report.replace("<unsafe>", "first line\\nBearer secret-value-1234567890123456"),
        "fail",
        Some(_attestation)
      )
      val attestationresponse = SbtReviewCanonicalResponse(
        _report,
        "fail",
        Some(_attestation.replace("attestation-example", "attestation-example\\nBearer secret-value-1234567890123456"))
      )

      When("the client attempts to render local projections")
      val reportresult = SbtReviewReportArtifacts.render(reportresponse)
      val attestationresult = SbtReviewReportArtifacts.render(attestationresponse)

      Then("neither canonical document can reach the Report, HTML, SARIF, or attestation artifacts")
      reportresult shouldBe Left("cbd-review-artifact-sensitive-value")
      attestationresult shouldBe Left("cbd-review-artifact-sensitive-value")
    }
  }

  private val _report =
    """{"schemaVersion":"textus.cbd.review-report.v1","documentType":"review-report","reportId":"report-example","reportDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","reviewId":"review-example","profile":"development","target":{"digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"execution":{"providers":[{"provider":{"id":"cozy","version":"0.3.0"},"ruleSet":{"id":"cozy.car-review","version":"1.0.0"},"bundleDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]},"gate":{"policyId":"cbd.default","policyVersion":"1.0.0","result":"fail"},"observations":[{"id":"finding-location","type":"finding","rule":{"id":"cozy.car.documentation-rationale"},"message":"<unsafe>","severity":"medium","locations":[{"path":"project.yaml","line":12}]},{"id":"missing-location","type":"finding","rule":{"id":"cozy.car.missing-location"},"message":"No location","severity":"low","locations":[]}]}"""
  private val _attestation = _attestation_for("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
  private val _canonical_response = Printer.noSpaces.print(io.circe.Json.obj(
    "schemaVersion" -> io.circe.Json.fromString("textus.cbd.review-submission.v1"),
    "documentType" -> io.circe.Json.fromString("canonical-review-response"),
    "report" -> parse(_report).fold(error => fail(error.getMessage), identity),
    "attestation" -> parse(_attestation).fold(error => fail(error.getMessage), identity),
    "gateResult" -> io.circe.Json.fromString("fail")
  ))
  private def _artifact_bundle(markdown: String = "# CBD CAR Review\n\nGate: fail\n", limitations: Vector[String] = Vector("pdf.unsupported-character"), pdfbase64: String = Base64.getEncoder.encodeToString("%PDF-1.7\nfixture\n".getBytes(StandardCharsets.UTF_8))): String = Printer.noSpaces.print(io.circe.Json.obj(
    "schemaVersion" -> io.circe.Json.fromString("textus.cbd.review-artifact-bundle.v1"),
    "documentType" -> io.circe.Json.fromString("review-artifact-bundle"),
    "reportDigest" -> io.circe.Json.fromString("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
    "limitations" -> io.circe.Json.fromValues(limitations.map(io.circe.Json.fromString)),
    "markdown" -> io.circe.Json.fromString(markdown),
    "pdfBase64" -> io.circe.Json.fromString(pdfbase64)
  ))
  private val _cozy_provider = parse(_report).fold(error => fail(error.getMessage), identity).hcursor.downField("execution").downField("providers").as[Vector[io.circe.Json]].fold(error => fail(error.getMessage), _.head)
  private val _sbt_provider = parse("""{"provider":{"id":"sbt-cozy","version":"0.1.0-SNAPSHOT"},"ruleSet":{"id":"sbt-cozy.build-evidence","version":"1.0.0"},"bundleDigest":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}""").fold(error => fail(error.getMessage), identity)

  private def _attestation_for(reportdigest: String): String = {
    val base = parse(
      s"""{"schemaVersion":"textus.cbd.review-report.v1","documentType":"review-attestation","attestationId":"attestation-example","createdAt":"2026-07-16T00:00:00Z","reviewId":"review-example","reportId":"report-example","reportDigest":"$reportdigest","targetDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","profile":"development","providers":[{"provider":{"id":"cozy","version":"0.3.0"},"ruleSet":{"id":"cozy.car-review","version":"1.0.0"},"bundleDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}],"gate":{"policyId":"cbd.default","policyVersion":"1.0.0","result":"fail"}}"""
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
