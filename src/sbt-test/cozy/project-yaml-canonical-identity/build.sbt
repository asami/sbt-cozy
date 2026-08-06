import org.goldenport.cozy.{CozyProjectIdentityContract, CozyProjectIdentityEvidence}
import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val projectIdentityEvidence = settingKey[CozyProjectIdentityEvidence]("Admitted canonical project identity evidence")
lazy val verifyCanonicalIdentity = taskKey[Unit]("Verify canonical project.yaml identity projections")

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    projectIdentityEvidence := CozyProjectIdentityContract.requireAdmitted(cozyProjectMetadata.value, scalaBinaryVersion.value),
    organization := projectIdentityEvidence.value.organization.get,
    moduleName := projectIdentityEvidence.value.moduleName.get,
    name := moduleName.value,
    version := projectIdentityEvidence.value.effectiveVersion,
    scalaVersion := "3.3.8",
    cozyCarName := projectIdentityEvidence.value.carBaseName.get,
    cozyManifestMetadata := projectIdentityEvidence.value.manifestMetadata,
    verifyCanonicalIdentity := {
      val evidence = projectIdentityEvidence.value
      val expectedManifest = Map(
        "component" -> "org.simplemodeling.textus.UserAccount",
        "namespace" -> "org.simplemodeling.textus",
        "id" -> "UserAccount",
        "qualifiedIdentity" -> "org.simplemodeling.textus.UserAccount",
        "organization" -> "org.simplemodeling.textus",
        "artifact" -> "textus-user-account",
        "jvmPackage" -> "org.simplemodeling.textus.useraccount",
        "generatedClass" -> "UserAccountComponent",
        "path" -> "user-account"
      )
      if (organization.value != "org.simplemodeling.textus" || moduleName.value != "textus-user-account" || version.value != "0.6.0-SNAPSHOT")
        sys.error("canonical build coordinates disagree")
      if (cozyCarName.value != "textus-user-account-0.6.0-SNAPSHOT" || evidence.carFilename != Some("textus-user-account-0.6.0-SNAPSHOT.car"))
        sys.error("canonical CAR name or filename disagrees")
      if (evidence.canonicalQualifiedId != Some("org.simplemodeling.textus.UserAccount") || evidence.jvmPackage != Some("org.simplemodeling.textus.useraccount") || evidence.generatedClass != Some("UserAccountComponent") || evidence.path != Some("user-account"))
        sys.error("canonical identity projections disagree")
      if (cozyManifestMetadata.value != expectedManifest || evidence.diagnosticCodes.nonEmpty)
        sys.error("canonical manifest metadata or diagnostics disagree")
    }
  )
