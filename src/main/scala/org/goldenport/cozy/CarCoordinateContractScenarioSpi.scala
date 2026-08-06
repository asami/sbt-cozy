package org.goldenport.cozy

/*
 * Scenario-only SPI for the Phase 56 CID-01 contract.  The DTOs deliberately
 * carry strings and metadata only; identity validation and projections belong
 * to the later CID implementation slices.
 */
private[cozy] sealed trait CarCoordinateContractScenarioRequest {
  def scenarioId: String
}

private[cozy] object CarCoordinateContractScenarioRequest {
  final case class CanonicalAgreement(
    scenarioId: String,
    namespace: String,
    localid: String,
    version: String
  ) extends CarCoordinateContractScenarioRequest

  final case class NamespaceRetention(
    scenarioId: String,
    firstnamespace: String,
    secondnamespace: String,
    localid: String,
    version: String
  ) extends CarCoordinateContractScenarioRequest

  final case class MetadataAdmission(
    scenarioId: String,
    namespace: String,
    localid: String,
    version: String,
    compatibilitymetadata: Map[String, String]
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
    modulename: String,
    version: String,
    mavenartifact: String,
    carfilename: String,
    manifestcomponent: String,
    descriptorcomponent: String,
    jvmpackage: String,
    generatedclass: String
  ) extends CarCoordinateContractScenarioReport

  final case class NamespaceIsolated(
    scenarioId: String,
    dependencykeys: Vector[String],
    repositorykeys: Vector[String],
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
  ): CarCoordinateContractScenarioReport =
    CarCoordinateContractScenarioReport.NotImplemented(request.scenarioId)
}
