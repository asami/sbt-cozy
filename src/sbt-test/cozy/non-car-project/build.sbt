import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val verifyNoComponentApiDependencies = taskKey[Unit](
  "Verify that a non-CAR project does not require a component API descriptor"
)
lazy val verifyMavenPublication = taskKey[Unit](
  "Verify that standard publish writes a Maven artifact instead of a CAR"
)
lazy val verifyLocalPublication = taskKey[Unit](
  "Verify that standard publishLocal writes an Ivy artifact instead of a CAR"
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "launcher",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    publishMavenStyle := true,
    publishTo := Some(Resolver.file("fixture-maven", target.value / "maven")(Resolver.mavenStylePatterns)),
    ivyPaths := IvyPaths(baseDirectory.value, Some(target.value / "ivy-home")),
    verifyNoComponentApiDependencies := {
      val jars = cozyResolvedComponentApiJars.value
      if (jars.nonEmpty)
        sys.error(s"Expected no resolved component API JARs, but got: ${jars.mkString(", ")}")
      val descriptor = target.value / "cozy" / "component-api-descriptor.json"
      if (descriptor.exists())
        sys.error(s"Non-CAR project unexpectedly generated a component API descriptor: $descriptor")
    },
    verifyMavenPublication := {
      val artifact = target.value / "maven" / "example" / "launcher_2.13" / "0.1.0" / "launcher_2.13-0.1.0.jar"
      if (!artifact.isFile)
        sys.error(s"Expected Maven artifact is missing: $artifact")
      val cars = (target.value ** "*.car").get
      if (cars.nonEmpty)
        sys.error(s"Non-CAR project unexpectedly published CAR artifacts: ${cars.mkString(", ")}")
    },
    verifyLocalPublication := {
      val artifact = target.value / "ivy-home" / "local" / "example" / "launcher_2.13" / "0.1.0" / "jars" / "launcher_2.13.jar"
      if (!artifact.isFile)
        sys.error(s"Expected local Ivy artifact is missing: $artifact")
      val cars = (target.value ** "*.car").get
      if (cars.nonEmpty)
        sys.error(s"Non-CAR project unexpectedly published local CAR artifacts: ${cars.mkString(", ")}")
    }
  )
