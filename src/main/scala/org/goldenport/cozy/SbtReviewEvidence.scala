package org.goldenport.cozy

import java.io.File

import sbt._

/*
 * @since   Jul. 16, 2026
 * @version Oct. 10, 2026
 * @author  ASAMI, Tomoharu
 */
/**
 * Deterministic, provider-owned sbt execution evidence. This module has no
 * CBD Support dependency and deliberately emits no assessment, finding, or
 * gate result: it records only attributable task outcomes for later CBD
 * admission and reconciliation.
 */
private[cozy] final case class SbtReviewEvidenceTarget(
  organization: Option[String],
  name: String,
  version: String
)

private[cozy] final case class SbtReviewTaskResult(
  task: String,
  result: String
)

private[cozy] final case class SbtReviewEvidenceArtifacts(
  descriptor: String,
  request: String,
  bundle: String
)

private[cozy] object SbtReviewEvidence {
  val SCHEMA_VERSION = "textus.cbd.review-provider.v1"
  val PROVIDER_ID = "sbt-cozy"
  val RULE_SET_ID = "sbt-cozy.build-evidence"
  val CAPABILITY_ID = "sbt-cozy.build-evidence"

  def render(reviewId: String, target: SbtReviewEvidenceTarget, providerVersion: String, results: Seq[SbtReviewTaskResult]): SbtReviewEvidenceArtifacts = {
    val normalized = results.sortBy(_.task).toVector
    val request = _request(reviewId, target)
    val descriptor = _descriptor(providerVersion)
    val bundle = _object(_bundle_fields(reviewId, target, providerVersion, normalized))
    SbtReviewEvidenceArtifacts(descriptor, request, bundle)
  }

  def write(directory: File, artifacts: SbtReviewEvidenceArtifacts): File = {
    IO.createDirectory(directory)
    IO.write(directory / "provider-descriptor.json", artifacts.descriptor + "\n")
    IO.write(directory / "provider-request.json", artifacts.request + "\n")
    val bundle = directory / "evidence-bundle.json"
    IO.write(bundle, artifacts.bundle + "\n")
    bundle
  }

  private def _descriptor(providerVersion: String): String =
    _object(Vector(
      "capabilities" -> _array(Vector(_object(Vector(
        "evidenceKinds" -> _array(Vector("build", "car-package", "task-result").map(_string)),
        "id" -> _string(CAPABILITY_ID),
        // A provider descriptor must name the Observation vocabulary it can
        // attribute. This evidence-only provider does not create one during
        // normal execution, but may report an explicit Unknown when a future
        // task capture cannot be obtained.
        "observationKinds" -> _array(Vector(_string("unknown"))),
        "version" -> _string("1.0")
      )))),
      "documentType" -> _string("provider-descriptor"),
      "limitations" -> _array(Vector(_limitation("sbt-evidence-no-quality-assessment", "capability", Some(CAPABILITY_ID), "sbt-cozy records task evidence but does not assess quality or produce a gate result."))),
      "provider" -> _object(Vector("id" -> _string(PROVIDER_ID), "version" -> _string(providerVersion))),
      "ruleSet" -> _object(Vector("id" -> _string(RULE_SET_ID), "version" -> _string("1.0.0"))),
      "schemaVersion" -> _string(SCHEMA_VERSION),
      "supportedSchemaVersions" -> _array(Vector(_string(SCHEMA_VERSION)))
    ))

  private def _request(reviewid: String, target: SbtReviewEvidenceTarget): String =
    _object(Vector(
      "documentType" -> _string("provider-request"),
      "limits" -> _object(Vector(
        "maxEvidenceItems" -> "32",
        "maxInputBytes" -> "16777216",
        "maxObservations" -> "1",
        "timeoutMillis" -> "120000"
      )),
      "requestedCapabilities" -> _array(Vector(_string(CAPABILITY_ID))),
      "requestedEvidenceKinds" -> _array(Vector("build", "car-package", "task-result").map(_string)),
      "reviewId" -> _string(reviewid),
      "rules" -> _object(Vector("exclude" -> _array(Vector.empty), "include" -> _array(Vector(_string("sbt-cozy.build.*"))))),
      "schemaVersion" -> _string(SCHEMA_VERSION),
      "target" -> _target(target)
    ))

  private def _bundle_fields(
    reviewid: String,
    target: SbtReviewEvidenceTarget,
    providerversion: String,
    results: Vector[SbtReviewTaskResult]
  ): Vector[(String, String)] = {
    val evidence = results.zipWithIndex.map { case (result, index) =>
      _object(Vector(
        "facts" -> _object(Vector("result" -> _string(result.result), "task" -> _string(result.task))),
        "id" -> _string(s"evidence-sbt-task-${index + 1}"),
        "kind" -> _string(_evidence_kind(result.task)),
        "origin" -> _object(Vector("providerId" -> _string(PROVIDER_ID), "sourceType" -> _string("sbt-task"))),
        "subject" -> _object(Vector("id" -> _string(result.task), "kind" -> _string("sbt-task")))
      ))
    }
    Vector(
      "documentType" -> _string("evidence-bundle"),
      "evidence" -> _array(evidence),
      "limitations" -> _array(Vector(_limitation("sbt-evidence-no-quality-assessment", "capability", Some(CAPABILITY_ID), "sbt-cozy records task evidence but does not assess quality or produce a gate result."))),
      "observations" -> _array(Vector.empty),
      "provider" -> _object(Vector("id" -> _string(PROVIDER_ID), "version" -> _string(providerversion))),
      "reviewId" -> _string(reviewid),
      "ruleSet" -> _object(Vector("id" -> _string(RULE_SET_ID), "version" -> _string("1.0.0"))),
      "schemaVersion" -> _string(SCHEMA_VERSION),
      "target" -> _target(target)
    )
  }

  private def _target(target: SbtReviewEvidenceTarget): String =
    _object(Vector(
      "kind" -> _string("project"),
      "name" -> _string(target.name),
      "version" -> _string(target.version)
    ) ++ target.organization.map(value => "organization" -> _string(value)))

  private def _limitation(code: String, scope: String, subjectid: Option[String], message: String): String =
    _object(Vector(
      "code" -> _string(code),
      "message" -> _string(message),
      "retryable" -> "false",
      "scope" -> _string(scope),
      "subjectId" -> subjectid.map(_string).getOrElse("null")
    ))

  private def _evidence_kind(task: String): String =
    task match {
      case "car-build" => "car-package"
      case _ if task.endsWith("result") => "task-result"
      case _ => "build"
    }

  private def _object(fields: Vector[(String, String)]): String =
    fields.sortBy(_._1).map { case (key, value) => s"${_string(key)}:$value" }.mkString("{", ",", "}")

  private def _array(values: Vector[String]): String = values.mkString("[", ",", "]")

  private def _string(value: String): String = {
    val escaped = value.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    }
    "\"" + escaped + "\""
  }
}
