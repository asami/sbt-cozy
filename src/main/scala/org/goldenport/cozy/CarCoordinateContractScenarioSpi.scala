package org.goldenport.cozy

/*
 * Scenario SPI for the Phase 56 CID-01 contract.
 *
 * @since   Aug.  7, 2026
 * @version Aug.  7, 2026
 * @author  ASAMI, Tomoharu
 */
private[cozy] sealed trait CarCoordinateContractScenarioRequest {
  def scenarioId: String
}

private[cozy] object CarCoordinateContractScenarioRequest {
  final case class CanonicalAgreement(
    scenarioId: String,
    namespace: String,
    localId: String,
    version: String
  ) extends CarCoordinateContractScenarioRequest

  final case class NamespaceRetention(
    scenarioId: String,
    firstNamespace: String,
    secondNamespace: String,
    localId: String,
    version: String
  ) extends CarCoordinateContractScenarioRequest

  final case class MetadataAdmission(
    scenarioId: String,
    namespace: String,
    localId: String,
    version: String,
    compatibilityMetadata: Map[String, String]
  ) extends CarCoordinateContractScenarioRequest
}

private[cozy] sealed trait CarCoordinateContractScenarioReport {
  def scenarioId: String
}

private[cozy] object CarCoordinateContractScenarioReport {
  final case class NotImplemented(
    scenarioId: String
  ) extends CarCoordinateContractScenarioReport

  final case class Agreement(
    scenarioId: String,
    organization: String,
    moduleName: String,
    version: String,
    mavenArtifact: String,
    carFilename: String,
    manifestComponent: String,
    descriptorComponent: String,
    jvmPackage: String,
    generatedClass: String
  ) extends CarCoordinateContractScenarioReport

  final case class NamespaceIsolated(
    scenarioId: String,
    dependencyKeys: Vector[String],
    repositoryKeys: Vector[String],
    cachekeys: Vector[String],
    filenames: Vector[String]
  ) extends CarCoordinateContractScenarioReport

  final case class Rejected(
    scenarioId: String,
    reason: String
  ) extends CarCoordinateContractScenarioReport
}

private[cozy] object CarCoordinateContractScenarioSpi {
  def evaluate(
    request: CarCoordinateContractScenarioRequest
  ): CarCoordinateContractScenarioReport = request match {
    case request: CarCoordinateContractScenarioRequest.CanonicalAgreement =>
      _admit(request.scenarioId, request.namespace, request.localId, request.version, Map.empty)
    case request: CarCoordinateContractScenarioRequest.MetadataAdmission =>
      _admit(request.scenarioId, request.namespace, request.localId, request.version, request.compatibilityMetadata)
    case request: CarCoordinateContractScenarioRequest.NamespaceRetention =>
      _namespace_retention(request)
  }

  private def _admit(
    scenarioid: String,
    namespace: String,
    localid: String,
    version: String,
    compatibilitymetadata: Map[String, String]
  ): CarCoordinateContractScenarioReport = {
    val paths = compatibilitymetadata.map { case (key, value) => _compatibility_path(key) -> value }
    val config = CozyProjectConfig(
      Map(
        "project.namespace" -> namespace,
        "project.id" -> localid,
        "project.component.version" -> version
      ) ++ paths,
      Map.empty
    )
    CozyProjectIdentityContract.admit(config, "3") match {
      case Right(evidence) =>
        val metadata = CozyManifestMetadata.from(
          evidence.manifestMetadata,
          evidence.moduleName.getOrElse(""),
          evidence.effectiveVersion
        )
        val descriptor = metadata.extensions.getOrElse("componentDescriptorJson", "")
        val descriptorcomponent = "\"component\":\"" + metadata.component + "\""
        CarCoordinateContractScenarioReport.Agreement(
          scenarioid,
          evidence.organization.getOrElse(""),
          evidence.moduleName.getOrElse(""),
          evidence.effectiveVersion,
          evidence.moduleName.map(_ + "_3").getOrElse(""),
          evidence.carFilename.getOrElse(""),
          metadata.component,
          if (descriptor.contains(descriptorcomponent)) metadata.component else "",
          evidence.jvmPackage.getOrElse(""),
          evidence.generatedClass.getOrElse("")
        )
      case Left(reason) =>
        CarCoordinateContractScenarioReport.Rejected(scenarioid, reason)
    }
  }

  private def _compatibility_path(key: String): String = key match {
    case "organization" => "project.organization"
    case "artifact" => "project.name"
    case "scalaPackage" => "project.scalaPackage"
    case "package" => "project.package"
    case "component" => "project.component.name"
    case "className" => "project.component.className"
    case other => other
  }

  private def _namespace_retention(
    request: CarCoordinateContractScenarioRequest.NamespaceRetention
  ): CarCoordinateContractScenarioReport = {
    val coordinates = Vector(request.firstNamespace, request.secondNamespace).map { namespace =>
      CarComponentIdentityAdapter.projectRelease(
        namespace,
        request.localId,
        "3",
        request.version
      )
    }
    coordinates.collectFirst { case Left(error) => error } match {
      case Some(error) => CarCoordinateContractScenarioReport.Rejected(request.scenarioId, error.code())
      case None =>
        val admitted = coordinates.collect { case Right(value) => value }
        CarCoordinateContractScenarioReport.NamespaceIsolated(
          request.scenarioId,
          admitted.map(_._dependency_key),
          admitted.map(_._car_repository_relative_path),
          admitted.map(_._car_cache_relative_path),
          admitted.map(_._car_filename).distinct
        )
    }
  }
}
