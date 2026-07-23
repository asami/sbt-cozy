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
      IO.write(marker, "project.yaml\n")
      marker
    },
    cozyDistributeCar := {
      val marker = target.value / "car-distributed"
      IO.write(marker, "project.yaml\n")
      marker
    },
    cozyPublishLocalCar := {
      val marker = target.value / "car-published-local"
      IO.write(marker, "project.yaml\n")
      marker
    },
    verifyCarPublication := {
      val marker = target.value / "car-published"
      if (!marker.isFile)
        sys.error(s"Standard publish did not invoke the CAR publisher: $marker")
    },
    verifyCarDistribution := {
      val marker = target.value / "car-distributed"
      if (!marker.isFile)
        sys.error(s"Ordinary distribution did not invoke the CAR distributor: $marker")
    },
    verifyCarLocalPublication := {
      val marker = target.value / "car-published-local"
      if (!marker.isFile)
        sys.error(s"Standard local publish did not invoke the CAR publisher: $marker")
    }
  )
