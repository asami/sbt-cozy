import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val verifyReviewSubmission = taskKey[Unit]("Verify the materialized CBD Review artifacts")

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "review-submit",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.16",
    cozyPackaging := "car",
    cozyBuildCar := {
      val output = target.value / "review-submit.car"
      IO.write(output, "CAR Review submission fixture\n")
      output
    },
    cozyDelegateCommand := sys.props.get("cozy.review.classpath").map { classpath =>
      Seq("java", "-cp", classpath, "cozy.Cozy")
    }.getOrElse(Seq("cozy")),
    cozyReviewCbdEndpoint := sys.props.get("cbd.review.endpoint"),
    cozyReviewCozyProviderVersion := Some("0.3.0-SNAPSHOT"),
    verifyReviewSubmission := {
      val directory = cozyReviewEvidenceDir.value
      val canonical = cozyReviewCanonicalJson.value
      val attestation = cozyReviewAttestation.value
      val html = cozyReviewReportHtml.value
      val sarif = cozyReviewReportSarif.value
      val required = Seq(
        canonical,
        attestation,
        html,
        sarif
      )
      if (!required.forall(_.isFile))
        sys.error(s"CBD Review artifacts are incomplete under: $directory")
    }
  )
