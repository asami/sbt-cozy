package org.goldenport.cozy

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import io.circe.{Json, JsonObject, Printer}
import io.circe.parser.parse
import sbt._

/*
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
/** Deterministic local projections of a CBD-owned canonical Review response. */
private[cozy] final case class SbtReviewReportArtifacts(
  canonicalJson: String,
  html: String,
  sarif: String,
  attestation: String,
  gate: String
)

private[cozy] final case class SbtReviewReportArtifactFiles(
  canonicalJson: File,
  html: File,
  sarif: File,
  attestation: File
)

private[cozy] object SbtReviewReportArtifacts {
  val CANONICAL_JSON_FILE = "canonical-response.json"
  val HTML_FILE = "canonical-report.html"
  val SARIF_FILE = "canonical-report.sarif"
  val ATTESTATION_FILE = "canonical-attestation.json"

  def render(response: SbtReviewCanonicalResponse): Either[String, SbtReviewReportArtifacts] =
    for {
      report <- parse(response.report).left.map(_ => "cbd-review-canonical-report-invalid")
      attestationText <- response.attestation.toRight("cbd-review-canonical-attestation-missing")
      attestation <- parse(attestationText).left.map(_ => "cbd-review-canonical-attestation-invalid")
      _ <- _safe_artifact(report)
      _ <- _safe_artifact(attestation)
      gate <- _gate(report, response.gate)
      _ <- _validate_attestation(report, attestation, gate)
      findings <- _findings(report)
    } yield SbtReviewReportArtifacts(
      Printer.noSpaces.print(Json.obj(
        "documentType" -> Json.fromString("canonical-review-response-artifact"),
        "attestation" -> attestation,
        "gateResult" -> Json.fromString(gate),
        "report" -> report,
        "schemaVersion" -> Json.fromString("textus.cbd.review-submission.v1")
      )),
      _html(report, gate),
      _sarif(findings, gate),
      Printer.noSpaces.print(attestation),
      gate
    )

  def write(directory: File, response: SbtReviewCanonicalResponse): Either[String, SbtReviewReportArtifactFiles] =
    render(response).map { artifacts =>
      IO.createDirectory(directory)
      val json = directory / CANONICAL_JSON_FILE
      val html = directory / HTML_FILE
      val sarif = directory / SARIF_FILE
      val attestation = directory / ATTESTATION_FILE
      IO.write(json, artifacts.canonicalJson + "\n")
      IO.write(html, artifacts.html)
      IO.write(sarif, artifacts.sarif + "\n")
      IO.write(attestation, artifacts.attestation + "\n")
      SbtReviewReportArtifactFiles(json, html, sarif, attestation)
    }

  def gateFromArtifact(file: File): Either[String, String] =
    for {
      document <- parse(IO.read(file)).left.map(_ => "cbd-review-canonical-artifact-invalid")
      gate <- _string(document, "gateResult").toRight("cbd-review-canonical-artifact-gate-missing")
      _ <- Either.cond(Set("pass", "fail", "unknown").contains(gate), (), "cbd-review-canonical-artifact-gate-invalid")
    } yield gate

  private def _gate(report: Json, responseGate: String): Either[String, String] =
    for {
      gate <- _string(report.hcursor.downField("gate").focus.getOrElse(Json.Null), "result").toRight("cbd-review-report-gate-missing")
      _ <- Either.cond(Set("pass", "fail", "unknown").contains(gate), (), "cbd-review-report-gate-invalid")
      _ <- Either.cond(gate == responseGate, (), "cbd-review-response-gate-mismatch")
    } yield gate

  private def _findings(report: Json): Either[String, Vector[Json]] =
    report.hcursor.downField("observations").as[Vector[Json]].left.map(_ => "cbd-review-report-observations-invalid").map(
      _.filter(_string(_, "type").contains("finding"))
    )

  private def _validate_attestation(report: Json, attestation: Json, gate: String): Either[String, Unit] = {
    val validstructure =
      _string(attestation, "schemaVersion").contains("textus.cbd.review-report.v1") &&
        _string(attestation, "documentType").contains("review-attestation") &&
        _string(attestation, "attestationId").exists(_.nonEmpty) &&
        _string(attestation, "createdAt").exists(_.nonEmpty)
    val identities = Vector("reviewId", "reportId", "reportDigest", "profile")
    val sameidentities = identities.forall(key => _string(report, key).exists(value => _string(attestation, key).contains(value)))
    val sametarget = _string(report.hcursor.downField("target").focus.getOrElse(Json.Null), "digest").exists(value => _string(attestation, "targetDigest").contains(value))
    val samegate = attestation.hcursor.downField("gate").focus.contains(report.hcursor.downField("gate").focus.getOrElse(Json.Null)) && _string(attestation.hcursor.downField("gate").focus.getOrElse(Json.Null), "result").contains(gate)
    val reportproviders = report.hcursor.downField("execution").downField("providers").as[Vector[Json]].toOption.getOrElse(Vector.empty).flatMap(_provider_binding).sorted
    val attestedproviders = attestation.hcursor.downField("providers").as[Vector[Json]].toOption.getOrElse(Vector.empty).flatMap(_provider_binding).sorted
    val attestationdigest = _string(attestation, "attestationDigest")
    val expecteddigest = _digest(attestation.mapObject(_.remove("attestationDigest")))
    if (!validstructure) Left("cbd-review-attestation-structure-invalid")
    else if (!sameidentities || !sametarget || !samegate) Left("cbd-review-attestation-binding-invalid")
    else if (reportproviders.isEmpty || reportproviders != attestedproviders) Left("cbd-review-attestation-providers-invalid")
    else if (!attestationdigest.contains(expecteddigest)) Left("cbd-review-attestation-digest-invalid")
    else Right(())
  }

  private def _safe_artifact(value: Json): Either[String, Unit] =
    if (_contains_sensitive_field(value)) Left("cbd-review-artifact-sensitive-field")
    else if (_contains_sensitive_value(value)) Left("cbd-review-artifact-sensitive-value")
    else Right(())

  private def _contains_sensitive_field(value: Json): Boolean =
    value.asObject.exists(_.toMap.exists { case (key, nested) =>
      _sensitive_field(key) || _contains_sensitive_field(nested)
    }) || value.asArray.exists(_.exists(_contains_sensitive_field))

  private def _contains_sensitive_value(value: Json): Boolean =
    value.asString.exists { text =>
      val normalized = text.toLowerCase(java.util.Locale.ROOT)
      normalized.matches(".*(bearer|basic)\\s+[a-z0-9._~+/-]+.*") ||
        normalized.matches(".*(?:api[_-]?key|password|secret|token)\\s*=.*") ||
        text.matches(".*AKIA[0-9A-Z]{16}.*") || text.matches(".*sk-[A-Za-z0-9_-]{16,}.*")
    } || value.asObject.exists(_.values.exists(_contains_sensitive_value)) || value.asArray.exists(_.exists(_contains_sensitive_value))

  private def _sensitive_field(value: String): Boolean = {
    val normalized = value.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "")
    Set("authorization", "cookie", "credential", "credentials", "password", "secret", "token", "apikey", "accesstoken", "refreshtoken").contains(normalized)
  }

  private def _digest(value: Json): String = {
    val bytes = Printer.noSpaces.copy(sortKeys = true).print(value).getBytes(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString
    s"sha256:$digest"
  }

  private def _provider_binding(value: Json): Option[String] =
    for {
      provider <- value.hcursor.downField("provider").focus
      providerid <- _string(provider, "id")
      providerversion <- _string(provider, "version")
      ruleset <- value.hcursor.downField("ruleSet").focus
      ruleid <- _string(ruleset, "id")
      ruleversion <- _string(ruleset, "version")
      bundle <- _string(value, "bundleDigest")
    } yield Vector(providerid, providerversion, ruleid, ruleversion, bundle).mkString("\u0000")

  private def _html(report: Json, gate: String): String = {
    val title = _string(report, "reportId").getOrElse("CBD CAR Review")
    val canonical = Printer.spaces2.print(report)
    s"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>${_html_escape(title)}</title></head>
<body><main><h1>CBD CAR Review</h1><p id="gate-result">Gate: ${_html_escape(gate)}</p>
<pre id="canonical-review-report">${_html_escape(canonical)}</pre></main></body></html>
"""
  }

  private def _sarif(findings: Vector[Json], gate: String): String = {
    val projected = findings.flatMap(_sarif_result)
    val sarif = Json.obj(
      "$$schema" -> Json.fromString("https://json.schemastore.org/sarif-2.1.0.json"),
      "runs" -> Json.arr(Json.obj(
        "invocations" -> Json.arr(Json.obj("executionSuccessful" -> Json.fromBoolean(gate == "pass"))),
        "properties" -> Json.obj(
          "gateResult" -> Json.fromString(gate),
          "omittedFindingCount" -> Json.fromInt(findings.size - projected.size),
          "projection" -> Json.fromString("location-bearing-findings-only")
        ),
        "results" -> Json.fromValues(projected),
        "tool" -> Json.obj("driver" -> Json.obj(
          "informationUri" -> Json.fromString("https://simplemodeling.org/"),
          "name" -> Json.fromString("textus-cbd-support")
        ))
      )),
      "version" -> Json.fromString("2.1.0")
    )
    Printer.noSpaces.print(sarif)
  }

  private def _sarif_result(finding: Json): Option[Json] =
    _locations(finding).headOption.map { _ =>
      val severity = _string(finding, "severity").getOrElse("info")
      Json.obj(
        "level" -> Json.fromString(_sarif_level(severity)),
        "locations" -> Json.fromValues(_locations(finding)),
        "message" -> Json.obj("text" -> Json.fromString(_string(finding, "message").getOrElse("CBD Review Finding"))),
        "properties" -> Json.obj("observationId" -> Json.fromString(_string(finding, "id").getOrElse(""))),
        "ruleId" -> Json.fromString(_string(finding.hcursor.downField("rule").focus.getOrElse(Json.Null), "id").getOrElse("cbd.review.finding"))
      )
    }

  private def _locations(finding: Json): Vector[Json] =
    finding.hcursor.downField("locations").as[Vector[Json]].toOption.getOrElse(Vector.empty).flatMap { location =>
      _string(location, "path").filter(_.nonEmpty).map { path =>
        val region = _int(location, "line").orElse(_int(location, "lineNumber")).map { line =>
          Json.obj("startLine" -> Json.fromInt(line))
        }
        val physical = Json.fromJsonObject(JsonObject.fromIterable(
          Vector("artifactLocation" -> Json.obj("uri" -> Json.fromString(path))) ++
            region.map("region" -> _)
        ))
        Json.obj("physicalLocation" -> physical)
      }
    }

  private def _sarif_level(severity: String): String = severity match {
    case "critical" | "high" => "error"
    case "medium" => "warning"
    case _ => "note"
  }

  private def _string(json: Json, key: String): Option[String] =
    json.hcursor.downField(key).as[String].toOption

  private def _int(json: Json, key: String): Option[Int] =
    json.hcursor.downField(key).as[Int].toOption

  private def _html_escape(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
