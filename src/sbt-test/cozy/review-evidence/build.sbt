import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val verifyReviewEvidence = taskKey[Unit]("Verify sbt-cozy Review evidence files")

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "review-evidence",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    cozyPackaging := "car",
    cozyBuildCar := {
      val output = target.value / "review-evidence.car"
      IO.write(output, "CAR evidence fixture\n")
      output
    },
    verifyReviewEvidence := {
      val directory = cozyReviewEvidenceDir.value
      val descriptor = directory / "provider-descriptor.json"
      val request = directory / "provider-request.json"
      val bundle = directory / "evidence-bundle.json"
      if (!descriptor.isFile || !request.isFile || !bundle.isFile)
        sys.error(s"Missing Review evidence documents under: $directory")
      val value = IO.read(bundle)
      val required = Seq("generation", "compilation", "test", "dependency-resolution", "car-build", "task-result")
      if (!required.forall(task => value.contains("\"task\":\"" + task + "\"")))
        sys.error(s"Review evidence does not contain every required task: $value")
      if (!value.contains("sbt-evidence-no-quality-assessment") || value.contains("\"gate\""))
        sys.error(s"Review evidence must remain evidence-only: $value")
    }
  )
