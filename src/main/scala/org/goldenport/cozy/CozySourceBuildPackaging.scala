package org.goldenport.cozy

import java.io.File

/*
 * @since   Oct. 17, 2026
 * @version Oct. 17, 2026
 */
private[cozy] object CozySourceBuildPackaging {
  final case class Selection(
    runtimeartifact: Option[File],
    sourceversions: Option[(String, String)]
  ) {
    def libraryJars(resolvedJars: Seq[File]): Seq[File] =
      (resolvedJars ++ runtimeartifact.toSeq).distinct
  }

  def select(
    runtimeArtifactInput: Option[File],
    projectMetadata: CozyProjectConfig
  ): Selection = runtimeArtifactInput match {
    case None => Selection(None, None)
    case Some(artifact) =>
      if (!artifact.isFile || !artifact.getName.endsWith(".jar"))
        sys.error(s"[sbt-cozy] source CNCF runtime artifact must be an existing regular JAR: $artifact")
      _require_snapshot(projectMetadata.value("project.component.version"), "component")
      val versions = CozyGenerationVersionContract.projectVersions(projectMetadata)
      val cncfversion = _require_snapshot(versions.get(CozyGenerationVersionContract.CNCF_VERSION_KEY), "CNCF")
      val cozyversion = _require_snapshot(versions.get(CozyGenerationVersionContract.COZY_VERSION_KEY), "Cozy")
      Selection(Some(artifact), Some(cncfversion -> cozyversion))
  }

  def arguments(sourceBuildVersions: Option[(String, String)]): Vector[String] =
    sourceBuildVersions.toVector.flatMap { case (cncfversion, cozyversion) =>
      Vector("--source-cncf-version", cncfversion, "--source-cozy-version", cozyversion)
    }

  private def _require_snapshot(value: Option[String], label: String): String =
    value.map(_.trim).filter(version => version.nonEmpty && version.endsWith("-SNAPSHOT")).getOrElse(
      sys.error(s"[sbt-cozy] source CAR packaging requires a Snapshot $label version")
    )
}
