package org.goldenport.cozy

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest
import java.util.Base64

import io.circe.{Json, Printer}
import io.circe.parser.parse
import sbt.io.IO

/*
 * @since   Jul. 23, 2026
 * @version Jul. 23, 2026
 * @author  ASAMI, Tomoharu
 */
private[cozy] final case class SbtReviewCiArtifactFiles(canonicalJson: File, report: File, attestation: File, markdown: File, pdf: File, html: File, sarif: File, manifest: File)

private[cozy] object SbtReviewCiArtifactMaterializer {
  val MAX_ARTIFACT_BYTES = 16 * 1024 * 1024
  val MAX_ATTEMPT_BYTES = 64 * 1024 * 1024
  val MAX_PDF_BASE64_CHARACTERS = (((MAX_ARTIFACT_BYTES.toLong + 2) / 3) * 4).toInt
  val ATTESTATION_FILE = "attestation.json"
  val HTML_FILE = "report.html"
  val MARKDOWN_FILE = "report.md"
  val MANIFEST_FILE = "review-artifacts.json"
  val PDF_FILE = "report.pdf"
  val SARIF_FILE = "report.sarif"
  private val _printer = Printer.noSpaces.copy(sortKeys = true)
  private val _names = Map("canonical" -> "canonical-response.json", "report" -> "report.json", "attestation" -> ATTESTATION_FILE, "markdown" -> MARKDOWN_FILE, "pdf" -> PDF_FILE, "html" -> HTML_FILE, "sarif" -> SARIF_FILE, "manifest" -> MANIFEST_FILE)

  def write(evidencedir: File, response: SbtReviewCanonicalResponse): Either[String, SbtReviewCiArtifactFiles] =
    for {
      canonical <- response.canonicalResponse.toRight("cbd-review-canonical-response-missing")
      attestation <- response.attestation.toRight("cbd-review-canonical-attestation-missing")
      bundle <- response.artifactBundle.toRight("cbd-review-artifact-bundle-missing")
      _ <- _safe_bounded_line_text(canonical)
      _ <- _safe_bounded_line_text(response.report)
      _ <- _safe_bounded_line_text(attestation)
      projections <- SbtReviewReportArtifacts.render(response)
      _ <- _safe_bounded_text(projections.html)
      _ <- _safe_bounded_line_text(projections.sarif)
      data <- _data(bundle, response.report, attestation, response.gate)
      files <- _write(new File(evidencedir.getParentFile, data.attestationDigest.replace(':', '-')), canonical, response.report, attestation, data, projections.html, projections.sarif)
    } yield files

  private final case class Data(markdown: String, pdf: Array[Byte], limitations: Vector[String], reportDigest: String, targetDigest: String, profile: String, gate: Json, gateResult: String, reviewId: String, reportId: String, attestationDigest: String)

  private def _data(bundle: String, report: String, attestation: String, responsegate: String): Either[String, Data] =
    for {
      bundlejson <- parse(bundle).left.map(_ => "cbd-review-artifact-bundle-invalid")
      schema <- bundlejson.hcursor.get[String]("schemaVersion").toOption.filter(_ == "textus.cbd.review-artifact-bundle.v1").toRight("cbd-review-artifact-bundle-schema-invalid")
      documenttype <- bundlejson.hcursor.get[String]("documentType").toOption.filter(_ == "review-artifact-bundle").toRight("cbd-review-artifact-bundle-document-type-invalid")
      markdown <- bundlejson.hcursor.get[String]("markdown").toOption.filter(_.nonEmpty).toRight("cbd-review-artifact-markdown-missing")
      _ <- _safe_bounded_text(markdown)
      pdfbase64 <- bundlejson.hcursor.get[String]("pdfBase64").toOption.toRight("cbd-review-artifact-pdf-invalid")
      _ <- _bounded_pdf_base64(pdfbase64)
      pdf <- _decode_pdf(pdfbase64).toRight("cbd-review-artifact-pdf-invalid")
      _ <- _bounded(pdf)
      _ <- _safe_text(new String(pdf, StandardCharsets.ISO_8859_1))
      limitations <- bundlejson.hcursor.get[Vector[String]]("limitations").toOption.toRight("cbd-review-artifact-limitations-invalid").flatMap(_limitations)
      reportjson <- parse(report).left.map(_ => "cbd-review-canonical-report-invalid")
      reportdigest <- reportjson.hcursor.get[String]("reportDigest").toOption.filter(_digest).toRight("cbd-review-report-digest-invalid")
      bundledigest <- bundlejson.hcursor.get[String]("reportDigest").toOption.filter(_digest).toRight("cbd-review-artifact-report-digest-invalid")
      _ <- Either.cond(bundledigest == reportdigest, (), "cbd-review-artifact-report-digest-mismatch")
      targetdigest <- reportjson.hcursor.downField("target").get[String]("digest").toOption.filter(_digest).toRight("cbd-review-target-digest-invalid")
      profile <- reportjson.hcursor.get[String]("profile").toOption.filter(_.nonEmpty).toRight("cbd-review-profile-missing")
      reviewid <- reportjson.hcursor.get[String]("reviewId").toOption.filter(_.nonEmpty).toRight("cbd-review-id-missing")
      reportid <- reportjson.hcursor.get[String]("reportId").toOption.filter(_.nonEmpty).toRight("cbd-review-id-missing")
      gate <- reportjson.hcursor.downField("gate").focus.toRight("cbd-review-gate-missing")
      policyid <- gate.hcursor.get[String]("policyId").toOption.filter(_.nonEmpty).toRight("cbd-review-gate-policy-invalid")
      policyversion <- gate.hcursor.get[String]("policyVersion").toOption.filter(_.nonEmpty).toRight("cbd-review-gate-policy-invalid")
      gatevalue <- gate.hcursor.get[String]("result").toOption.filter(Set("pass", "fail", "unknown")).toRight("cbd-review-gate-invalid")
      _ <- Either.cond(gatevalue == responsegate, (), "cbd-review-response-gate-mismatch")
      attestationdigest <- parse(attestation).toOption.flatMap(_.hcursor.get[String]("attestationDigest").toOption).filter(_digest).toRight("cbd-review-attestation-digest-invalid")
    } yield Data(markdown, pdf, limitations, reportdigest, targetdigest, profile, gate, gatevalue, reviewid, reportid, attestationdigest)

  private def _write(directory: File, canonical: String, report: String, attestation: String, data: Data, html: String, sarif: String): Either[String, SbtReviewCiArtifactFiles] =
    if (directory.exists) Left("cbd-review-artifact-directory-exists")
    else {
      val parent = directory.getParentFile
      IO.createDirectory(parent)
      val temporary = Files.createTempDirectory(parent.toPath, ".cbd-review-").toFile
      try {
        val files = _files(temporary)
        IO.write(files.canonicalJson, canonical + "\n"); IO.write(files.report, report + "\n"); IO.write(files.attestation, attestation + "\n"); IO.write(files.markdown, data.markdown); Files.write(files.pdf.toPath, data.pdf); IO.write(files.html, html); IO.write(files.sarif, sarif + "\n")
        _bounded_files(_artifact_files(files)).flatMap { _ =>
          IO.write(files.manifest, _printer.print(_manifest(files, data)) + "\n")
          _bounded_files(_artifact_files(files) :+ files.manifest).map { _ =>
            Files.move(temporary.toPath, directory.toPath, StandardCopyOption.ATOMIC_MOVE)
            _files(directory)
          }
        }
      } catch { case _: Exception => Left("cbd-review-artifact-write-failed") }
      finally if (temporary.exists) IO.delete(temporary)
    }

  private def _files(directory: File): SbtReviewCiArtifactFiles = SbtReviewCiArtifactFiles(new File(directory, _names("canonical")), new File(directory, _names("report")), new File(directory, _names("attestation")), new File(directory, _names("markdown")), new File(directory, _names("pdf")), new File(directory, _names("html")), new File(directory, _names("sarif")), new File(directory, _names("manifest")))

  private def _manifest(files: SbtReviewCiArtifactFiles, data: Data): Json = Json.obj(
    "schemaVersion" -> Json.fromString("textus.cbd.review-ci-artifact.v1"), "documentType" -> Json.fromString("review-ci-artifact-manifest"), "reviewId" -> Json.fromString(data.reviewId), "reportId" -> Json.fromString(data.reportId), "reportDigest" -> Json.fromString(data.reportDigest), "targetDigest" -> Json.fromString(data.targetDigest), "attestationDigest" -> Json.fromString(data.attestationDigest), "profile" -> Json.fromString(data.profile), "limitations" -> Json.fromValues(data.limitations.map(Json.fromString)), "gate" -> data.gate, "exitCode" -> Json.fromInt(Map("pass" -> 0, "fail" -> 2, "unknown" -> 3)(data.gateResult)), "artifactDirectory" -> Json.fromString(s"target/cbd-review/${data.attestationDigest.replace(':', '-')}"), "retention" -> Json.obj("mode" -> Json.fromString("ci-workspace"), "preserveOn" -> Json.arr(Json.fromString("pass"), Json.fromString("fail"), Json.fromString("unknown")), "publication" -> Json.fromString("not-triggered"), "distribution" -> Json.fromString("not-triggered"), "deployment" -> Json.fromString("not-triggered")), "artifacts" -> Json.obj("canonicalResponse" -> _artifact(files.canonicalJson), "report" -> _artifact(files.report), "attestation" -> _artifact(files.attestation), "markdown" -> _artifact(files.markdown), "pdf" -> _artifact(files.pdf), "html" -> _artifact(files.html), "sarif" -> _artifact(files.sarif))
  )

  private def _artifact_files(files: SbtReviewCiArtifactFiles): Vector[File] = Vector(files.canonicalJson, files.report, files.attestation, files.markdown, files.pdf, files.html, files.sarif)
  private def _bounded_files(files: Vector[File]): Either[String, Unit] =
    if (files.exists(_.length > MAX_ARTIFACT_BYTES)) Left("cbd-review-artifact-output-too-large")
    else if (files.map(_.length).sum > MAX_ATTEMPT_BYTES) Left("cbd-review-artifact-attempt-too-large")
    else Right(())
  private def _bounded(value: Array[Byte]): Either[String, Unit] =
    Either.cond(value.length <= MAX_ARTIFACT_BYTES, (), "cbd-review-artifact-output-too-large")
  private def _bounded_pdf_base64(value: String): Either[String, Unit] =
    Either.cond(value.length <= MAX_PDF_BASE64_CHARACTERS, (), "cbd-review-artifact-output-too-large")
  private def _safe_bounded_text(value: String): Either[String, Unit] =
    for { _ <- _bounded(value.getBytes(StandardCharsets.UTF_8)); _ <- _safe_text(value) } yield ()
  private def _safe_bounded_line_text(value: String): Either[String, Unit] =
    _safe_bounded_text(value + "\n")
  private def _safe_text(value: String): Either[String, Unit] = {
    val normalized = value.toLowerCase(java.util.Locale.ROOT)
    val sensitive = normalized.matches("(?s).*(bearer|basic)\\s+[a-z0-9._~+/-]+.*") || normalized.matches("(?s).*(?:api[_-]?key|password|secret|token)\\s*=.*") || value.matches("(?s).*AKIA[0-9A-Z]{16}.*") || value.matches("(?s).*sk-[A-Za-z0-9_-]{16,}.*")
    Either.cond(!sensitive, (), "cbd-review-artifact-sensitive-value")
  }
  private def _limitations(value: Vector[String]): Either[String, Vector[String]] =
    if (value.size > 64 || value.exists(x => x.isEmpty || x.length > 1024)) Left("cbd-review-artifact-limitations-invalid")
    else value.foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) { (z, limitation) =>
      for { xs <- z; _ <- _safe_text(limitation) } yield xs :+ limitation
    }
  private def _artifact(file: File): Json = Json.obj("path" -> Json.fromString(file.getName), "sha256" -> Json.fromString(_sha256(Files.readAllBytes(file.toPath))))
  private def _decode_pdf(value: String): Option[Array[Byte]] = scala.util.Try(Base64.getDecoder.decode(value)).toOption.filter(bytes => bytes.length >= 4 && new String(bytes.take(4), StandardCharsets.ISO_8859_1) == "%PDF")
  private def _digest(value: String): Boolean = value.matches("sha256:[0-9a-f]{64}")
  private def _sha256(value: Array[Byte]): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(value).map(byte => f"${byte & 0xff}%02x").mkString
}
