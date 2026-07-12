import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val verifyCarPublication = taskKey[Unit](
  "Verify that project.yaml routes standard publish to the CAR publisher"
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "project-yaml-car",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    cozyPublishCar := {
      val marker = target.value / "car-published"
      IO.write(marker, "project.yaml\n")
      marker
    },
    verifyCarPublication := {
      val marker = target.value / "car-published"
      if (!marker.isFile)
        sys.error(s"Standard publish did not invoke the CAR publisher: $marker")
    }
  )
