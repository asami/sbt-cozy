import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val verifyCarPublication = taskKey[Unit](
  "Verify that project.yaml routes standard publish to the CAR publisher"
)
lazy val verifyCarDistribution = taskKey[Unit](
  "Verify that ordinary distribution does not require a CBD Review gate"
)
lazy val verifyCarLocalPublication = taskKey[Unit](
  "Verify that standard local publication does not require a CBD Review gate"
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "project-yaml-car",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    cozyReviewGate := sys.error("ordinary publication or distribution must not invoke cozyReviewGate"),
    cozyPublishCar := {
      val marker = target.value / "car-published"
      val command = cozyDelegateCommand.value
      IO.write(marker, command.mkString(" ") + "\n")
      marker
    },
    cozyDistributeCar := {
      val marker = target.value / "car-distributed"
      IO.write(marker, "project.yaml\n")
      marker
    },
    cozyPublishLocalCar := {
      val marker = target.value / "car-published-local"
      val command = cozyDelegateCommand.value
      IO.write(marker, command.mkString(" ") + "\n")
      marker
    },
    verifyCarPublication := {
      val marker = target.value / "car-published"
      val cozydir = baseDirectory.value / ".cozy"
      if (!marker.isFile)
        sys.error(s"Standard publish did not invoke the CAR publisher: $marker")
      if (cozydir.isDirectory)
        sys.error(s"Standard publish fixture must not use a project-local .cozy directory: $cozydir")
      val command = IO.read(marker).trim.split("\\s+").toVector
      if (!command.sliding(2).contains(Vector("--runtime", "0.3.1-SNAPSHOT")))
        sys.error(s"Standard publish did not record the project.yaml Cozy runtime: ${command.mkString(" ")}")
    },
    verifyCarDistribution := {
      val marker = target.value / "car-distributed"
      val cozydir = baseDirectory.value / ".cozy"
      if (!marker.isFile)
        sys.error(s"Ordinary distribution did not invoke the CAR distributor: $marker")
      if (cozydir.isDirectory)
        sys.error(s"Distribution fixture must not use a project-local .cozy directory: $cozydir")
    },
    verifyCarLocalPublication := {
      val marker = target.value / "car-published-local"
      val cozydir = baseDirectory.value / ".cozy"
      if (!marker.isFile)
        sys.error(s"Standard local publish did not invoke the CAR publisher: $marker")
      if (cozydir.isDirectory)
        sys.error(s"Standard local publish fixture must not use a project-local .cozy directory: $cozydir")
      val command = IO.read(marker).trim.split("\\s+").toVector
      if (!command.sliding(2).contains(Vector("--runtime", "0.3.1-SNAPSHOT")))
        sys.error(s"Standard local publish did not record the project.yaml Cozy runtime: ${command.mkString(" ")}")
    }
  )
