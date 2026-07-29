import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val verifyInitialRuntimeEvidence = taskKey[Unit](
  "Verify the initial stable development-runtime evidence pair."
)
lazy val rewriteRuntimeContractEvidence = taskKey[Unit](
  "Change a stable descriptor and ABI input so runtime evidence must regenerate."
)
lazy val verifyRegeneratedRuntimeEvidence = taskKey[Unit](
  "Verify that the development manifest was regenerated from changed contract evidence."
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "development-runtime-evidence",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.16",
    verifyInitialRuntimeEvidence := {
      val evidence = cozyRuntimeEvidenceFiles.value
      val classpath = target.value / "cncf.d" / "runtime-classpath.txt"
      val manifest = target.value / "cncf.d" / "car-runtime-manifest.json"
      if (evidence != Seq(classpath, manifest) || !classpath.isFile || !manifest.isFile)
        sys.error(s"cozyPrepareRuntime did not produce the complete evidence pair: $evidence")
      val text = IO.read(manifest)
      if (!text.contains("cncf.car-development-runtime-manifest.v1") ||
          !text.contains("development-directory") ||
          text.contains("target/scala"))
        sys.error(s"development manifest has the wrong stable-evidence shape: $manifest")
      IO.write(target.value / "initial-runtime-manifest.json", text)
    },
    rewriteRuntimeContractEvidence := {
      val cardir = baseDirectory.value / "src" / "main" / "car"
      IO.write(
        cardir / "component-descriptor.json",
        """{"name":"development-runtime-evidence","version":"0.1.0-SNAPSHOT","component":"development-runtime-evidence","config":{"evidenceRevision":"2"}}"""
      )
      IO.write(
        cardir / "abi-manifest.json",
        """{"format":"cozy.car.abi-manifest.v1","car":{"name":"development-runtime-evidence","version":"0.1.0-SNAPSHOT"},"abi":{"exports":{"components":[{"name":"development-runtime-evidence"}]}}}"""
      )
    },
    verifyRegeneratedRuntimeEvidence := {
      val initial = IO.read(target.value / "initial-runtime-manifest.json")
      val current = IO.read(target.value / "cncf.d" / "car-runtime-manifest.json")
      if (current == initial || !current.contains("0.1.0-SNAPSHOT"))
        sys.error("development runtime evidence was not regenerated from the changed stable contract inputs")
    }
  )
