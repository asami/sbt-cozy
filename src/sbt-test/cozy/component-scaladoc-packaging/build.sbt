import org.goldenport.cozy.{CozyProjectIdentityContract, CozyProjectIdentityEvidence}
import org.goldenport.cozy.CozyPlugin.autoImport._

import java.nio.charset.StandardCharsets

def _scaladoc_manifest_entries_(file: File): Vector[String] = {
  val text = IO.read(file)
  val entry = """\{"path":"([^"]+)"\}""".r
  val entries = entry.findAllMatchIn(text).map(_.group(1)).toVector
  if (text != s"""{"schema":"cozy.component-scaladoc.v1","entries":${entries.map(path => s"""{"path":"$path"}""").mkString("[", ",", "]")}}""" ||
      text.endsWith("\n") ||
      entries.isEmpty ||
      entries != entries.sorted)
    sys.error(s"Scaladoc manifest is not canonical or lacks sorted entries: ${file.getAbsolutePath}")
  entries
}

def _staged_scaladoc_text_(directory: File): String =
  (directory ** "*").get.filter(_.isFile).sortBy(_.getAbsolutePath).map(file => IO.read(file, StandardCharsets.UTF_8)).mkString("\n")

lazy val verifyScaladocStaging = taskKey[Unit](
  "DOC03-SDOC-AC-01: verify Scaladoc generation and canonical staging"
)
lazy val verifyScaladocDisclosure = taskKey[Unit](
  "DOC03-SDOC-AC-02: verify public-only Scaladoc disclosure without source links"
)
lazy val projectIdentityEvidence = settingKey[CozyProjectIdentityEvidence](
  "Admitted canonical project identity evidence"
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    projectIdentityEvidence := CozyProjectIdentityContract.requireAdmitted(
      cozyProjectMetadata.value,
      scalaBinaryVersion.value
    ),
    organization := projectIdentityEvidence.value.organization.get,
    moduleName := projectIdentityEvidence.value.moduleName.get,
    name := moduleName.value,
    version := projectIdentityEvidence.value.effectiveVersion,
    cozyCarName := projectIdentityEvidence.value.carBaseName.get,
    cozyManifestMetadata := projectIdentityEvidence.value.manifestMetadata,
    scalaVersion := "3.3.8",
    resolvers += "CNCF workspace Maven repository" at "file:///Users/asami/src/maven-repository/repository/maven",
    libraryDependencies += "org.goldenport" %% "goldenport-cncf" % "0.5.2",
    Compile / sourceGenerators := Seq.empty,
    verifyScaladocStaging := {
      val staging = target.value / "cozy" / "scaladoc"
      val manifest = staging / "scaladoc-manifest.json"
      if (!staging.isDirectory || !manifest.isFile)
        sys.error(s"Scaladoc staging was not created: ${staging.getAbsolutePath}")
      val entries = _scaladoc_manifest_entries_(manifest)
      if (!(staging / "index.html").isFile)
        sys.error("Scaladoc staging is missing index.html")
      if (!entries.exists(path => path.endsWith(".js") && (path.contains("index") || path.contains("search"))))
        sys.error("Scaladoc staging is missing generated search or symbol evidence")
    },
    verifyScaladocDisclosure := {
      val staging = target.value / "cozy" / "scaladoc"
      val staged = _staged_scaladoc_text_(staging)
      if (!staged.contains("PublicApi") || !staged.contains("greet"))
        sys.error("Scaladoc staging lacks public symbol and search evidence")
      if (staged.contains("FixtureInternal") || staged.contains("normalize") || staged.toLowerCase.contains("src-html"))
        sys.error("Scaladoc staging discloses a private fixture symbol or source-page material")
      val sourcelink = "(?is)<a\\b[^>]*\\bhref\\s*=\\s*[\"'][^\"']+[\"'][^>]*>\\s*source\\s*</a>".r
      if (sourcelink.findFirstIn(staged).nonEmpty)
        sys.error("Scaladoc staging exposes a source link")
      if ((staging / "src-html").exists())
        sys.error("Scaladoc staging contains src-html source pages")
    }
  )
