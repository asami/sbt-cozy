import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val verifyNoComponentApiDependencies = taskKey[Unit](
  "Verify that a non-CAR project does not require a component API descriptor"
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    scalaVersion := "2.13.16",
    verifyNoComponentApiDependencies := {
      val jars = cozyResolvedComponentApiJars.value
      if (jars.nonEmpty)
        sys.error(s"Expected no resolved component API JARs, but got: ${jars.mkString(", ")}")
      val descriptor = target.value / "cozy" / "component-api-descriptor.json"
      if (descriptor.exists())
        sys.error(s"Non-CAR project unexpectedly generated a component API descriptor: $descriptor")
    }
  )
