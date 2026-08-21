lazy val writePluginVersion = taskKey[File](
  "Write the tested plugin version for the isolated negative project"
)

lazy val root = (project in file("."))
  .settings(
    writePluginVersion := {
      val destination = target.value / "plugin.version"
      IO.createDirectory(destination.getParentFile)
      IO.write(destination, sys.props("plugin.version") + "\n")
      destination
    }
  )
