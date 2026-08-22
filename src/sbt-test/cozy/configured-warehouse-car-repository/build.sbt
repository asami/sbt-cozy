import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val verifyConfiguredWarehouseRepository = taskKey[Unit](
  "Verify that a configured warehouse CAR repository is the first resolver"
)

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "configured-warehouse-car-repository",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    verifyConfiguredWarehouseRepository := {
      val repositories = cozyCarRepositories.value
      val configured = (baseDirectory.value / "warehouse" / "repository" / "car").getCanonicalPath
      if (repositories.headOption != Some(configured))
        sys.error(s"configured warehouse CAR repository is not first: ${repositories.mkString(", ")}")
      if (repositories.count(_ == configured) != 1)
        sys.error(s"configured warehouse CAR repository must occur exactly once: ${repositories.mkString(", ")}")

      val home = file(sys.props.getOrElse("user.home", "."))
      val legacy = Seq(
        (cozyLocalWarehouseDir.value / "repository" / "car").getCanonicalPath,
        (home / ".cncf" / "repository" / "car").getCanonicalPath,
        (home / ".cncf" / "cache" / "car").getCanonicalPath,
        "https://www.simplemodeling.org/repository/car"
      )
      val positions = legacy.map { repository =>
        val position = repositories.indexOf(repository)
        if (position <= 0)
          sys.error(s"legacy CAR repository is missing or precedes configured warehouse: $repository in ${repositories.mkString(", ")}")
        position
      }
      if (positions != positions.sorted)
        sys.error(s"legacy CAR repository order changed: ${repositories.mkString(", ")}")
    }
  )
