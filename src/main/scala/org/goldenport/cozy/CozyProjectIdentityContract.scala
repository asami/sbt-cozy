package org.goldenport.cozy

/*
 * @since   Aug.  7, 2026
 * @version Aug.  7, 2026
 * @author  ASAMI, Tomoharu
 */
final case class CozyProjectIdentityEvidence(
  schemaVersion: String,
  shape: String,
  effectiveVersion: String,
  canonicalNamespace: Option[String],
  canonicalLocalId: Option[String],
  canonicalQualifiedId: Option[String],
  expectedDerivedValues: Map[String, String],
  authoredCompatibilityValues: Map[String, String],
  materializedValues: Map[String, String],
  disagreements: Vector[String],
  diagnosticCodes: Vector[String],
  organization: Option[String],
  moduleName: Option[String],
  carBaseName: Option[String],
  carFilename: Option[String],
  mavenCoordinate: Option[String],
  jvmPackage: Option[String],
  generatedClass: Option[String],
  path: Option[String],
  manifestMetadata: Map[String, String]
)

object CozyProjectIdentityContract {
  val SCHEMA_VERSION = "cozy-project-identity-contract/v1"
  val DISAGREEMENT_CODE = "CAR_COMPONENT_IDENTITY_DISAGREEMENT"

  private val _canonical_paths = Vector(
    "project.namespace",
    "project.id",
    "project.component.version"
  )
  private val _compatibility_paths = Vector(
    "project.name" -> "artifact",
    "project.organization" -> "organization",
    "project.scalaPackage" -> "jvmPackage",
    "project.package" -> "jvmPackage",
    "project.component.name" -> "localId",
    "project.component.className" -> "generatedClass",
    "project.identity.qualified" -> "qualifiedId",
    "project.identity.organization" -> "organization",
    "project.identity.artifact" -> "artifact",
    "project.identity.jvmPackage" -> "jvmPackage",
    "project.identity.generatedClass" -> "generatedClass",
    "project.identity.path" -> "path",
    "packaging.car.manifest_metadata.component" -> "qualifiedId",
    "packaging.car.manifest_metadata.namespace" -> "namespace",
    "packaging.car.manifest_metadata.id" -> "localId",
    "packaging.car.manifest_metadata.qualifiedIdentity" -> "qualifiedId",
    "packaging.car.manifest_metadata.organization" -> "organization",
    "packaging.car.manifest_metadata.artifact" -> "artifact",
    "packaging.car.manifest_metadata.jvmPackage" -> "jvmPackage",
    "packaging.car.manifest_metadata.generatedClass" -> "generatedClass",
    "packaging.car.manifest_metadata.path" -> "path"
  )

  def inspect(config: CozyProjectConfig, scalaBinaryVersion: String): CozyProjectIdentityEvidence = {
    val present = _canonical_paths.map(config.isAuthored)
    val shape =
      if (!present(0) && !present(1)) "legacy"
      else if (present.forall(identity)) "canonical"
      else "partial"
    val effectiveversion = config.value("project.component.version").getOrElse("")
    val authored = _compatibility_paths.collect {
      case (path, _) if config.isAuthored(path) => path -> config.values.getOrElse(path, "").trim
    }.toMap
    if (shape == "legacy")
      CozyProjectIdentityEvidence(
        SCHEMA_VERSION, shape, effectiveversion, None, None, None, Map.empty,
        authored, authored, Vector.empty, Vector.empty, None, None, None, None,
        None, None, None, None, Map.empty
      )
    else {
      val namespace = config.value("project.namespace")
      val localid = config.value("project.id")
      val projection = CarComponentIdentityAdapter.projectRelease(
        namespace.getOrElse(""),
        localid.getOrElse(""),
        scalaBinaryVersion,
        effectiveversion
      ).left.map(_.code())
      val expected = projection.toOption.map { value =>
        Map(
          "namespace" -> value.identity.mavenGroupId(),
          "localId" -> value.identity.componentId().localId().value(),
          "qualifiedId" -> value.identity.qualifiedId(),
          "organization" -> value.identity.mavenGroupId(),
          "artifact" -> value.identity.mavenArtifactId(),
          "jvmPackage" -> value.identity.jvmPackage(),
          "generatedClass" -> value.identity.generatedClassName(),
          "path" -> value.identity.pathSegment(),
          "carFilename" -> value.carfilename,
          "mavenCoordinate" -> value.mavencoordinate
        )
      }.getOrElse(Map.empty)
      val disagreements = _compatibility_paths.collect {
        case (field, expectedkey) if config.isAuthored(field) && expected.get(expectedkey).exists(_ != config.values.getOrElse(field, "").trim) =>
          s"$field expected=${expected(expectedkey)} actual=${config.values.getOrElse(field, "").trim}"
      }.sorted.toVector
      val diagnostics = (projection.left.toOption.toVector ++
        (if (disagreements.nonEmpty) Vector(DISAGREEMENT_CODE) else Vector.empty)).distinct.sorted
      val manifest = projection.toOption.map { value =>
        config.mapUnder("packaging.car.manifest_metadata") ++ Map(
          "component" -> value.identity.qualifiedId(),
          "namespace" -> value.identity.mavenGroupId(),
          "id" -> value.identity.componentId().localId().value(),
          "qualifiedIdentity" -> value.identity.qualifiedId(),
          "organization" -> value.identity.mavenGroupId(),
          "artifact" -> value.identity.mavenArtifactId(),
          "jvmPackage" -> value.identity.jvmPackage(),
          "generatedClass" -> value.identity.generatedClassName(),
          "path" -> value.identity.pathSegment()
        )
      }.getOrElse(Map.empty)
      CozyProjectIdentityEvidence(
        SCHEMA_VERSION, shape, effectiveversion, namespace, localid,
        expected.get("qualifiedId"), expected, authored, authored, disagreements,
        diagnostics, expected.get("organization"), expected.get("artifact"),
        expected.get("artifact").map(x => s"$x-$effectiveversion"), expected.get("carFilename"),
        expected.get("mavenCoordinate"), expected.get("jvmPackage"),
        expected.get("generatedClass"), expected.get("path"), manifest
      )
    }
  }

  def admit(config: CozyProjectConfig, scalaBinaryVersion: String): Either[String, CozyProjectIdentityEvidence] = {
    val evidence = inspect(config, scalaBinaryVersion)
    if (evidence.shape == "legacy") Right(evidence)
    else if (evidence.shape != "canonical" || evidence.diagnosticCodes.nonEmpty)
      Left((evidence.diagnosticCodes ++ evidence.disagreements).mkString("; "))
    else Right(evidence)
  }

  def requireAdmitted(config: CozyProjectConfig, scalaBinaryVersion: String): CozyProjectIdentityEvidence =
    admit(config, scalaBinaryVersion).fold(reason => sys.error(s"[sbt-cozy] $reason"), identity)
}
