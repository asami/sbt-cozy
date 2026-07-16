package org.goldenport.cozy

import java.io.File
import java.io.ByteArrayInputStream
import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.StandardCharsets

import io.circe.{Json, Printer}
import io.circe.parser.parse

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
  gate: String,
  attestation: Option[String] = None
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

/** Fixed JSON-over-HTTP implementation for an explicitly configured CBD gateway. */
private[cozy] final class SbtCbdReviewHttpEndpoint(
  endpoint: String,
  timeoutMillis: Int = 30000
) extends SbtCbdReviewWireEndpoint {
  import SbtCbdReviewHttpEndpoint.MAX_DOCUMENT_BYTES

  def submit(document: String): Either[String, String] =
    for {
      uri <- _uri(endpoint)
      _ <- Either.cond(document.getBytes(StandardCharsets.UTF_8).length <= MAX_DOCUMENT_BYTES, (), "cbd-review-request-too-large")
      response <- _post(uri, _envelope(document))
      canonical <- _canonical_response(response)
    } yield canonical

  private def _uri(value: String): Either[String, URI] =
    scala.util.Try(new URI(value)).toOption.filter { uri =>
      Set("http", "https").contains(Option(uri.getScheme).getOrElse("").toLowerCase(java.util.Locale.ROOT)) &&
        uri.getHost != null && uri.getRawUserInfo == null && uri.getRawQuery == null && uri.getRawFragment == null
    }.toRight("cbd-review-endpoint-invalid")

  private def _post(uri: URI, document: String): Either[String, String] =
    try {
      val connection = uri.toURL.openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod("POST")
      connection.setInstanceFollowRedirects(false)
      connection.setConnectTimeout(timeoutMillis)
      connection.setReadTimeout(timeoutMillis)
      connection.setDoOutput(true)
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("Accept", "application/json")
      val output = connection.getOutputStream
      try output.write(document.getBytes(StandardCharsets.UTF_8)) finally output.close()
      val status = connection.getResponseCode
      val contenttype = Option(connection.getContentType).getOrElse("").toLowerCase(java.util.Locale.ROOT)
      val input = if (status >= 200 && status < 300) connection.getInputStream else connection.getErrorStream
      val response = _read(input)
      connection.disconnect()
      Either.cond(status >= 200 && status < 300 && contenttype.startsWith("application/json") && response.nonEmpty, response, "cbd-review-http-response-invalid")
    } catch {
      case NonFatal(_) => Left("cbd-review-http-request-failed")
    }

  private def _read(input: InputStream): String =
    if (input == null) ""
    else {
      val output = new ByteArrayOutputStream()
      val buffer = new Array[Byte](8192)
      try {
        Iterator.continually(input.read(buffer)).takeWhile(_ >= 0).foreach { size =>
          if (output.size + size > MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("cbd-review-response-too-large")
          output.write(buffer, 0, size)
        }
        new String(output.toByteArray, StandardCharsets.UTF_8)
      } finally input.close()
    }

  private def _envelope(document: String): String =
    s"""{"submissionDocument":${_quote(document)}}"""

  private def _canonical_response(value: String): Either[String, String] =
    parse(value).toOption
      .flatMap(_.asObject)
      .filter(_.keys.toSet == Set("canonical_response"))
      .flatMap(_("canonical_response").flatMap(_.asString).filter(_.nonEmpty))
      .toRight("cbd-review-response-envelope-invalid")

  private def _quote(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"; case '"' => "\\\""; case '\n' => "\\n"; case '\r' => "\\r"; case '\t' => "\\t"; case char => char.toString
    } + "\""
}

private[cozy] object SbtCbdReviewHttpEndpoint {
  val MAX_DOCUMENT_BYTES = 128 * 1024 * 1024
}

/** Concrete sbt-side adapter for CBD's transport-neutral submission JSON. */
private[cozy] final class SbtCbdReviewWireTransport(endpoint: SbtCbdReviewWireEndpoint) extends SbtReviewSubmissionTransport {
  private val _printer = Printer.noSpaces.copy(sortKeys = true)

  def submit(value: SbtReviewPairedSubmission): Either[String, SbtReviewCanonicalResponse] =
    for {
      target <- _object(value.providers.headOption.map(_.request).getOrElse(""), "target")
      request = _submission(value, target)
      response <- endpoint.submit(request)
      canonical <- _canonical_response(response)
    } yield canonical

  private def _submission(value: SbtReviewPairedSubmission, target: String): String =
    s"""{"schemaVersion":"textus.cbd.review-submission.v1","documentType":"provider-document-submission","reviewId":${_quote(value.reviewId)},"target":$target,"providers":[${value.providers.map(_provider).mkString(",")}]}"""

  private def _provider(value: SbtReviewProviderDocuments): String =
    s"""{"availability":"enabled","descriptor":${_quote(value.descriptor)},"providerRequest":${_quote(value.request)},"bundle":${_quote(value.bundle)}}"""

  private def _quote(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"; case '\"' => "\\\""; case '\n' => "\\n"; case '\r' => "\\r"; case '\t' => "\\t"; case char => char.toString
    } + "\""

  private def _canonical_response(value: String): Either[String, SbtReviewCanonicalResponse] =
    for {
      json <- parse(value).left.map(_ => "cbd-review-response-json-invalid")
      fields <- json.asObject.filter(_.keys.toSet == Set("schemaVersion", "documentType", "report", "attestation", "gateResult")).toRight("cbd-review-response-shape-invalid")
      _ <- Either.cond(fields("schemaVersion").flatMap(_.asString).contains("textus.cbd.review-submission.v1"), (), "cbd-review-response-schema-invalid")
      _ <- Either.cond(fields("documentType").flatMap(_.asString).contains("canonical-review-response"), (), "cbd-review-response-document-type-invalid")
      report <- fields("report").flatMap(_.asObject).map(value => _printer.print(Json.fromJsonObject(value))).toRight("cbd-review-response-report-invalid")
      attestation <- fields("attestation").flatMap(_.asObject).map(value => _printer.print(Json.fromJsonObject(value))).toRight("cbd-review-response-attestation-invalid")
      gate <- fields("gateResult").flatMap(_.asString).filter(Set("pass", "fail", "unknown")).toRight("cbd-review-response-gate-invalid")
    } yield SbtReviewCanonicalResponse(report, gate, Some(attestation))

  private def _object(value: String, key: String): Either[String, String] =
    parse(value).toOption
      .flatMap(_.hcursor.get[Json](key).toOption)
      .flatMap(_.asObject)
      .map(value => _printer.print(Json.fromJsonObject(value)))
      .toRight("review-provider-request-target-missing")
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
  private val _printer = Printer.noSpaces.copy(sortKeys = true)

  def cozyProviderRequest(sbtRequest: String): Either[String, String] =
    for {
      reviewid <- _review_id(sbtRequest)
      target <- _object(sbtRequest, "target")
    } yield s"""{"schemaVersion":"textus.cbd.review-provider.v1","documentType":"provider-request","reviewId":${_quote(reviewid)},"target":$target,"limits":{"maxEvidenceItems":256,"maxObservations":256,"maxInputBytes":16777216,"timeoutMillis":120000},"requestedCapabilities":["cozy.car-analysis"],"requestedEvidenceKinds":["car-project","cml-model","build","car-package","abi","documentation"],"rules":{"include":[],"exclude":[]}}"""

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
    parse(request).toOption
      .flatMap(_.hcursor.get[String]("reviewId").toOption)
      .filter(_.nonEmpty)
      .toRight("sbt-review-request-id-missing")
  }

  private def _object(value: String, key: String): Either[String, String] =
    parse(value).toOption
      .flatMap(_.hcursor.get[Json](key).toOption)
      .flatMap(_.asObject)
      .map(value => _printer.print(Json.fromJsonObject(value)))
      .toRight("review-provider-request-target-missing")

  private def _quote(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"; case '"' => "\\\""; case '\n' => "\\n"; case '\r' => "\\r"; case '\t' => "\\t"; case char => char.toString
    } + "\""
}
