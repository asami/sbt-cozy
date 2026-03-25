ThisBuild / organization := "org.goldenport"
ThisBuild / version := "0.1.2-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "sbt-cozy",
    sbtPlugin := true,
    scalaVersion := "2.12.20",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test
  )
