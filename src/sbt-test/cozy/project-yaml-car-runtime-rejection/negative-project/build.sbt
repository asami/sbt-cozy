import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "project-yaml-car-runtime-rejection",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    cozyWarehouseDir := target.value / "isolated-warehouse",
    cozyPublishCar := {
      val command = cozyDelegateCommand.value
      val warehouse = cozyWarehouseDir.value
      val marker = warehouse / "unexpected-publish"
      IO.write(marker, command.mkString(" ") + "\n")
      marker
    }
  )
