package org.goldenport.cozy

import java.io.File

import io.circe.Json
import io.circe.parser.parse
import sbt.io.IO

/*
 * @since   Jul. 23, 2026
 * @version Jul. 23, 2026
 * @author  ASAMI, Tomoharu
 */
/** The bounded CI consumer of the CBD-owned Review artifact manifest. */
private[cozy] final case class SbtReviewCiGate(result: String, exitCode: Int)

private[cozy] object SbtReviewCiGate {
  private val _artifact_paths = Map(
    "canonicalResponse" -> "canonical-response.json",
    "report" -> "report.json",
    "attestation" -> "attestation.json",
    "markdown" -> "report.md",
    "pdf" -> "report.pdf",
    "html" -> "report.html",
    "sarif" -> "report.sarif"
  )
  private val _manifest_fields = Set("schemaVersion", "documentType", "reviewId", "reportId", "reportDigest", "targetDigest", "attestationDigest", "profile", "limitations", "gate", "exitCode", "artifactDirectory", "retention", "artifacts")
  private val _results = Set("pass", "fail", "unknown")

  def fromManifest(file: File): Either[String, SbtReviewCiGate] =
    fromText(IO.read(file))

  def fromText(value: String): Either[String, SbtReviewCiGate] =
    for {
      manifest <- parse(value).left.map(_ => "cbd-review-ci-manifest-invalid")
      fields <- _object(manifest, "cbd-review-ci-manifest-shape-invalid")
      _ <- Either.cond(fields.keySet == _manifest_fields, (), "cbd-review-ci-manifest-shape-invalid")
      _ <- _string(fields("schemaVersion")).filter(_ == "textus.cbd.review-ci-artifact.v1").toRight("cbd-review-ci-manifest-schema-invalid")
      _ <- _string(fields("documentType")).filter(_ == "review-ci-artifact-manifest").toRight("cbd-review-ci-manifest-document-type-invalid")
      _ <- Vector("reviewId", "reportId", "profile").foldLeft[Either[String, Unit]](Right(())) { case (z, key) => z.flatMap(_ => _identifier(fields(key))) }
      _ <- Vector("reportDigest", "targetDigest").foldLeft[Either[String, Unit]](Right(())) { case (z, key) => z.flatMap(_ => _digest(fields(key)).map(_ => ())) }
      attestationdigest <- _digest(fields("attestationDigest"))
      _ <- _limitations(fields("limitations"))
      result <- _gate(fields("gate"))
      code <- fields("exitCode").asNumber.flatMap(_.toInt).toRight("cbd-review-ci-manifest-exit-code-invalid")
      expected <- exitCodeFor(result)
      _ <- Either.cond(code == expected, (), "cbd-review-ci-manifest-exit-code-mismatch")
      _ <- _artifact_directory(fields("artifactDirectory"), attestationdigest)
      _ <- _retention(fields("retention"))
      _ <- _artifacts(fields("artifacts"))
    } yield SbtReviewCiGate(result, code)

  def exitCodeFor(result: String): Either[String, Int] =
    result match {
      case "pass" => Right(0)
      case "fail" => Right(2)
      case "unknown" => Right(3)
      case _ => Left("cbd-review-ci-manifest-gate-invalid")
    }

  private def _artifact_directory(value: Json, attestationdigest: String): Either[String, Unit] =
    Either.cond(_string(value).contains(s"target/cbd-review/${attestationdigest.replace(':', '-')}"), (), "cbd-review-ci-manifest-artifact-directory-invalid")

  private def _artifacts(value: Json): Either[String, Unit] =
    for {
      fields <- _object(value, "cbd-review-ci-manifest-artifacts-invalid")
      _ <- Either.cond(fields.keySet == _artifact_paths.keySet, (), "cbd-review-ci-manifest-artifacts-invalid")
      _ <- _artifact_paths.foldLeft[Either[String, Unit]](Right(())) { case (z, (key, path)) => z.flatMap(_ => _artifact(fields(key), path)) }
    } yield ()

  private def _artifact(value: Json, path: String): Either[String, Unit] =
    for {
      fields <- _object(value, "cbd-review-ci-manifest-artifact-invalid")
      _ <- Either.cond(fields.keySet == Set("path", "sha256"), (), "cbd-review-ci-manifest-artifact-invalid")
      _ <- Either.cond(_string(fields("path")).contains(path), (), "cbd-review-ci-manifest-artifact-path-invalid")
      _ <- _digest(fields("sha256"))
    } yield ()

  private def _digest(value: Json): Either[String, String] =
    _string(value).filter(_.matches("sha256:[0-9a-f]{64}")).toRight("cbd-review-ci-manifest-digest-invalid")

  private def _gate(value: Json): Either[String, String] =
    for {
      fields <- _object(value, "cbd-review-ci-manifest-gate-invalid")
      _ <- Either.cond(fields.keySet == Set("policyId", "policyVersion", "result"), (), "cbd-review-ci-manifest-gate-invalid")
      _ <- _identifier(fields("policyId"))
      _ <- _identifier(fields("policyVersion"))
      result <- _string(fields("result")).filter(_results).toRight("cbd-review-ci-manifest-gate-invalid")
    } yield result

  private def _identifier(value: Json): Either[String, Unit] =
    Either.cond(_string(value).exists(_.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")), (), "cbd-review-ci-manifest-identifier-invalid")

  private def _limitations(value: Json): Either[String, Unit] =
    value.asArray.map(_.toVector).filter(values => values.size <= 64 && values.forall(_.asString.exists(text => text.nonEmpty && text.length <= 1024)))
      .map(_ => ()).toRight("cbd-review-ci-manifest-limitations-invalid")

  private def _object(value: Json, error: String): Either[String, Map[String, Json]] =
    value.asObject.map(_.toMap).toRight(error)

  private def _retention(value: Json): Either[String, Unit] =
    for {
      fields <- _object(value, "cbd-review-ci-manifest-retention-invalid")
      _ <- Either.cond(fields.keySet == Set("mode", "preserveOn", "publication", "distribution", "deployment"), (), "cbd-review-ci-manifest-retention-invalid")
      _ <- Either.cond(_string(fields("mode")).contains("ci-workspace") && _string(fields("publication")).contains("not-triggered") && _string(fields("distribution")).contains("not-triggered") && _string(fields("deployment")).contains("not-triggered"), (), "cbd-review-ci-manifest-retention-invalid")
      preserve <- fields("preserveOn").asArray.map(_.toVector.map(_.asString)).toRight("cbd-review-ci-manifest-retention-invalid")
      _ <- Either.cond(preserve.size == _results.size && preserve.forall(_.isDefined) && preserve.flatten.toSet == _results, (), "cbd-review-ci-manifest-retention-invalid")
    } yield ()

  private def _string(value: Json): Option[String] =
    value.asString
}
