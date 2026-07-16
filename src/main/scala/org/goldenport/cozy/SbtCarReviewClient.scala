package org.goldenport.cozy

import java.io.File
import java.io.ByteArrayInputStream
import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{HttpURLConnection, URI}
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

/** Fixed JSON-over-HTTP implementation for an explicitly configured CBD gateway. */
private[cozy] final class SbtCbdReviewHttpEndpoint(
  endpoint: String,
  timeoutMillis: Int = 30000,
  reviewRole: Option[String] = None
) extends SbtCbdReviewWireEndpoint {
  import SbtCbdReviewHttpEndpoint.MAX_DOCUMENT_BYTES

  def submit(document: String): Either[String, String] =
    for {
      uri <- _uri(endpoint)
      role <- _role(uri, reviewRole)
      _ <- Either.cond(document.getBytes(StandardCharsets.UTF_8).length <= MAX_DOCUMENT_BYTES, (), "cbd-review-request-too-large")
      response <- _post(uri, _envelope(document), role)
      canonical <- _field(response, "canonicalResponse")
    } yield canonical

  private def _uri(value: String): Either[String, URI] =
    scala.util.Try(new URI(value)).toOption.filter { uri =>
      Set("http", "https").contains(Option(uri.getScheme).getOrElse("").toLowerCase(java.util.Locale.ROOT)) &&
        uri.getHost != null && uri.getRawUserInfo == null && uri.getRawQuery == null && uri.getRawFragment == null
    }.toRight("cbd-review-endpoint-invalid")

  private def _role(uri: URI, value: Option[String]): Either[String, Option[String]] =
    value.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty) match {
      case Some(role) if !Set("reviewer", "operator", "admin").contains(role) => Left("cbd-review-role-invalid")
      case Some(_) if !_is_loopback(uri) => Left("cbd-review-role-loopback-required")
      case Some(role) => Right(Some(role))
      case None => Right(None)
    }

  private def _is_loopback(uri: URI): Boolean =
    Set("127.0.0.1", "::1", "localhost").contains(Option(uri.getHost).getOrElse("").toLowerCase(java.util.Locale.ROOT))

  private def _post(uri: URI, document: String, role: Option[String]): Either[String, String] =
    try {
      val connection = uri.toURL.openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod("POST")
      connection.setInstanceFollowRedirects(false)
      connection.setConnectTimeout(timeoutMillis)
      connection.setReadTimeout(timeoutMillis)
      connection.setDoOutput(true)
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("Accept", "application/json")
      role.foreach(connection.setRequestProperty("role", _))
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

  private def _field(value: String, key: String): Either[String, String] = {
    val marker = ("\\\"" + java.util.regex.Pattern.quote(key) + "\\\"\\s*:\\s*").r
    marker.findFirstMatchIn(value).map(_.end).map(_json_string(value, _)).getOrElse(Left("cbd-review-response-envelope-invalid"))
  }

  private def _json_string(value: String, start: Int): Either[String, String] =
    if (start >= value.length || value.charAt(start) != '"') Left("cbd-review-response-envelope-invalid")
    else {
      val out = new StringBuilder
      var index = start + 1
      var closed = false
      var valid = true
      while (index < value.length && !closed && valid) {
        value.charAt(index) match {
          case '"' => closed = true
          case '\\' if index + 1 >= value.length => valid = false
          case '\\' =>
            index += 1
            value.charAt(index) match {
              case 'n' => out.append('\n')
              case 'r' => out.append('\r')
              case 't' => out.append('\t')
              case 'b' => out.append('\b')
              case 'f' => out.append('\f')
              case 'u' if index + 4 < value.length =>
                val code = value.substring(index + 1, index + 5)
                scala.util.Try(Integer.parseInt(code, 16)).toOption match {
                  case Some(number) => out.append(number.toChar); index += 4
                  case None => valid = false
                }
              case 'u' => valid = false
              case char @ ('"' | '\\' | '/') => out.append(char)
              case _ => valid = false
            }
          case char => out.append(char)
        }
        index += 1
      }
      if (closed && valid) Right(out.toString) else Left("cbd-review-response-envelope-invalid")
    }

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
    val pattern = "\"reviewId\":\"([^\"]+)\"".r
    pattern.findFirstMatchIn(request).map(_.group(1)).toRight("sbt-review-request-id-missing")
  }

  private def _object(value: String, key: String): Either[String, String] = {
    val start = ("\\\"" + java.util.regex.Pattern.quote(key) + "\\\"\\s*:\\s*").r.findFirstMatchIn(value).map(_.end).getOrElse(-1)
    if (start < 0 || start >= value.length || value(start) != '{') Left("review-provider-request-target-missing")
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
      if (end >= 0) Right(value.substring(start, end + 1)) else Left("review-provider-request-target-invalid")
    }
  }

  private def _quote(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"; case '"' => "\\\""; case '\n' => "\\n"; case '\r' => "\\r"; case '\t' => "\\t"; case char => char.toString
    } + "\""
}
