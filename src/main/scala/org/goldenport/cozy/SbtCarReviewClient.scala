package org.goldenport.cozy

import java.io.File
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

import scala.sys.process._
import scala.util.control.NonFatal

/*
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
/**
 * Transport-neutral client boundary for the P5-31 local/CI route. The client
 * supplies only provider-owned JSON documents to CBD; it never supplies a
 * workspace path to the submission transport.
 */
private[cozy] final case class SbtReviewProviderDocuments(
  providerId: String,
  descriptor: String,
  request: String,
  bundle: String
)

private[cozy] final case class SbtReviewPairedSubmission(
  reviewId: String,
  providers: Vector[SbtReviewProviderDocuments]
)

private[cozy] final case class SbtReviewCanonicalResponse(
  report: String,
  gate: String
)

private[cozy] trait SbtLocalCozyReviewTransport {
  def collect(projectRoot: File, providerRequest: String, providerVersion: String): Either[String, SbtReviewProviderDocuments]
}

private[cozy] trait SbtReviewSubmissionTransport {
  def submit(value: SbtReviewPairedSubmission): Either[String, SbtReviewCanonicalResponse]
}

private[cozy] trait SbtCbdReviewWireEndpoint {
  def submit(document: String): Either[String, String]
}

/** Concrete sbt-side adapter for CBD's transport-neutral submission JSON. */
private[cozy] final class SbtCbdReviewWireTransport(endpoint: SbtCbdReviewWireEndpoint) extends SbtReviewSubmissionTransport {
  def submit(value: SbtReviewPairedSubmission): Either[String, SbtReviewCanonicalResponse] =
    for {
      target <- _object(value.providers.headOption.map(_.request).getOrElse(""), "target")
      request = _submission(value, target)
      response <- endpoint.submit(request)
      report <- _object(response, "report")
      gate <- _string(response, "gateResult")
      _ <- Either.cond(Set("pass", "fail", "unknown").contains(gate), (), "cbd-review-response-gate-invalid")
    } yield SbtReviewCanonicalResponse(report, gate)

  private def _submission(value: SbtReviewPairedSubmission, target: String): String =
    s"""{"schemaVersion":"textus.cbd.review-submission.v1","documentType":"provider-document-submission","reviewId":${_quote(value.reviewId)},"target":$target,"providers":[${value.providers.map(_provider).mkString(",")}]}"""

  private def _provider(value: SbtReviewProviderDocuments): String =
    s"""{"availability":"enabled","descriptor":${_quote(value.descriptor)},"providerRequest":${_quote(value.request)},"bundle":${_quote(value.bundle)}}"""

  private def _quote(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"; case '\"' => "\\\""; case '\n' => "\\n"; case '\r' => "\\r"; case '\t' => "\\t"; case char => char.toString
    } + "\""

  private def _string(value: String, key: String): Either[String, String] =
    ("\\\"" + java.util.regex.Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").r.findFirstMatchIn(value).map(_.group(1)).toRight("cbd-review-response-field-missing")

  private def _object(value: String, key: String): Either[String, String] = {
    val start = ("\\\"" + java.util.regex.Pattern.quote(key) + "\\\"\\s*:\\s*").r.findFirstMatchIn(value).map(_.end).getOrElse(-1)
    if (start < 0 || start >= value.length || value(start) != '{') Left("cbd-review-response-field-missing")
    else {
      val end = value.indices.drop(start).foldLeft((0, false, false, -1)) { case ((depth, quote, escape, found), index) =>
        if (found >= 0) (depth, quote, escape, found)
        else {
          val char = value(index)
          if (quote) (depth, char == '"' && !escape, char == '\\' && !escape, -1)
          else if (char == '"') (depth, true, false, -1)
          else if (char == '{') (depth + 1, false, false, -1)
          else if (char == '}' && depth == 1) (0, false, false, index)
          else if (char == '}') (depth - 1, false, false, -1)
          else (depth, false, false, -1)
        }
      }._4
      if (end >= 0) Right(value.substring(start, end + 1)) else Left("cbd-review-response-field-invalid")
    }
  }
}

private[cozy] final class SbtCozyCommandReviewTransport(commandPrefix: Seq[String]) extends SbtLocalCozyReviewTransport {
  import SbtCozyCommandReviewTransport.MAX_RESPONSE_BYTES

  def collect(projectRoot: File, providerRequest: String, providerVersion: String): Either[String, SbtReviewProviderDocuments] =
    for {
      root <- Either.cond(projectRoot.isDirectory, projectRoot.getCanonicalFile, "cozy-review-project-root-invalid")
      descriptor <- _execute(_descriptor_command(providerVersion), root, None)
      bundle <- _execute(_evidence_command(root, providerVersion), root, Some(providerRequest))
      _ <- Either.cond(descriptor.getBytes(StandardCharsets.UTF_8).length <= MAX_RESPONSE_BYTES && bundle.getBytes(StandardCharsets.UTF_8).length <= MAX_RESPONSE_BYTES, (), "cozy-review-response-too-large")
    } yield SbtReviewProviderDocuments("cozy", descriptor, providerRequest, bundle)

  private def _descriptor_command(providerVersion: String): Seq[String] =
    commandPrefix ++ Seq("review", "car-descriptor", "--provider-version", providerVersion, "--descriptor")

  private def _evidence_command(projectRoot: File, providerVersion: String): Seq[String] =
    commandPrefix ++ Seq("review", "car-evidence", "--project-root", projectRoot.getAbsolutePath, "--provider-version", providerVersion, "--request-stdin")

  private def _execute(command: Seq[String], cwd: File, stdin: Option[String]): Either[String, String] =
    try {
      val process = Process(command, cwd)
      val output = stdin match {
        case Some(value) => (process #< new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))).!!
        case None => process.!!
      }
      Right(output.trim)
    } catch {
      case NonFatal(_) => Left("cozy-review-command-failed")
    }
}

private[cozy] object SbtCozyCommandReviewTransport {
  val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
}

private[cozy] object SbtCarReviewClient {
  def submit(
    projectRoot: File,
    cozyRequest: String,
    cozyProviderVersion: String,
    sbtArtifacts: SbtReviewEvidenceArtifacts,
    cozyTransport: SbtLocalCozyReviewTransport,
    submissionTransport: SbtReviewSubmissionTransport
  ): Either[String, SbtReviewCanonicalResponse] =
    for {
      cozy <- cozyTransport.collect(projectRoot.getCanonicalFile, cozyRequest, cozyProviderVersion)
      sbt = SbtReviewProviderDocuments("sbt-cozy", sbtArtifacts.descriptor, sbtArtifacts.request, sbtArtifacts.bundle)
      paired <- _paired(cozy, sbt)
      response <- submissionTransport.submit(paired)
      _ <- Either.cond(response.report.trim.nonEmpty && response.gate.trim.nonEmpty, (), "cbd-review-response-incomplete")
    } yield response

  private def _paired(
    cozy: SbtReviewProviderDocuments,
    sbt: SbtReviewProviderDocuments
  ): Either[String, SbtReviewPairedSubmission] =
    for {
      _ <- Either.cond(cozy.providerId == "cozy", (), "cozy-provider-identity-invalid")
      _ <- Either.cond(sbt.providerId == "sbt-cozy", (), "sbt-provider-identity-invalid")
      _ <- Either.cond(Vector(cozy, sbt).forall(_documents_bounded), (), "review-provider-document-too-large")
      reviewid <- _review_id(sbt.request)
    } yield SbtReviewPairedSubmission(reviewid, Vector(cozy, sbt))

  private def _documents_bounded(value: SbtReviewProviderDocuments): Boolean =
    Vector(value.descriptor, value.request, value.bundle).forall(_.getBytes("UTF-8").length <= 16 * 1024 * 1024)

  private def _review_id(request: String): Either[String, String] = {
    val pattern = "\"reviewId\":\"([^\"]+)\"".r
    pattern.findFirstMatchIn(request).map(_.group(1)).toRight("sbt-review-request-id-missing")
  }
}
