import org.goldenport.cozy.CozyPlugin.autoImport._

lazy val root = (project in file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "example",
    name := "project-yaml-car-runtime-rejection-ambient-project-dir",
    version := "0.1.0",
    scalaVersion := "2.13.16",
    cozyWarehouseDir := target.value / "isolated-warehouse",
    cozyPublishCar := {
      val delegateprojectdir = cozyDelegateProjectDir.value
      val warehouse = cozyWarehouseDir.value
      val marker = warehouse / "unexpected-publish"
      IO.write(marker, "ambient project directory bypassed CAR runtime authority\n")
      marker
    }
  )
