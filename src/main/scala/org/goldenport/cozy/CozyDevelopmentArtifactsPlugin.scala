package org.goldenport.cozy

import sbt._
import sbt.Keys._

/*
 * Explicit opt-in for task-private development artifact selection.
 * @since   Nov. 12, 2026
 * @version Nov. 12, 2026
 */
object CozyDevelopmentArtifactsPlugin extends AutoPlugin {
  object autoImport {
    val cozyDevelopmentModules = settingKey[Seq[ModuleID]]("Original direct and transitive modules expected by the development selection")
    val smDevelopmentArtifactPublish = taskKey[File]("Publish the explicitly selected development artifact to its private Ivy repository")

    def cozyDevelopmentDependency(module: ModuleID): Def.Initialize[ModuleID] = Def.setting {
      val input = _selection.value
      val fullversion = scalaVersion.value
      val binaryversion = scalaBinaryVersion.value
      input.fold(module)(value => CozyDevelopmentArtifactOverlay.selectDependency(
        module, value.mappings, fullversion, binaryversion))
    }
  }
  import autoImport._

  private val _selection = SettingKey[Option[CozyDevelopmentArtifactOverlay.Input]]("cozyDevelopmentSelection", "Explicit development artifact input")
  private val _checkcompile = TaskKey[Unit]("cozyDevelopmentCheckCompile", "Validate effective Compile selection before compilation")
  private val _checktest = TaskKey[Unit]("cozyDevelopmentCheckTest", "Validate effective Test selection before test execution")
  override def requires: Plugins = plugins.JvmPlugin
  override def trigger: PluginTrigger = noTrigger

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    cozyDevelopmentModules := Seq.empty,
    _selection := {
      val root = (LocalRootProject / baseDirectory).value
      sys.props.get("sm.artifact.selection").map { path =>
        val input = CozyDevelopmentArtifactOverlay.read(file(path), root)
        java.nio.file.Files.deleteIfExists(input.result)
        input
      }
    },
    resolvers ++= _selection.value.toSeq.map(input =>
      Resolver.file("sm-artifact-" + input.scope, input.repository.toFile)(Resolver.ivyStylePatterns)),
    dependencyOverrides ++= _selection.value.toSeq.flatMap(_.mappings.map(mapping =>
      ModuleID(mapping.selected.organization, mapping.selected.module, mapping.selected.version).withCrossVersion(CrossVersion.disabled))),
    _checkcompile := {
      val classpath = (Compile / dependencyClasspath).value
      val input = _selection.value
      val report = update.value
      val unmanaged = (Compile / unmanagedJars).value
      val expected = cozyDevelopmentModules.value
      val overrides = (Compile / dependencyOverrides).value.toSeq
      val repositories = (Compile / resolvers).value
      val fullversion = scalaVersion.value
      val binaryversion = scalaBinaryVersion.value
      val project = thisProject.value
      val isplugin = sbtPlugin.value
      input.foreach { value =>
        if (isplugin) sys.error("[sbt-cozy artifact] sbt-plugin projects are unsupported")
        if (unmanaged.nonEmpty) sys.error("[sbt-cozy artifact] unmanaged JARs are unsupported with explicit selection")
        if (project.dependencies.nonEmpty) sys.error("[sbt-cozy artifact] project references are unsupported")
        CozyDevelopmentArtifactOverlay.validateSettings(value, expected, overrides, repositories, fullversion, binaryversion)
        CozyDevelopmentArtifactOverlay.validateClasspath(value, report, classpath.map(_.data), "Compile")
      }
      ()
    },
    _checktest := {
      val classpath = (Test / dependencyClasspath).value
      val input = _selection.value
      val report = update.value
      val unmanaged = (Test / unmanagedJars).value
      val expected = cozyDevelopmentModules.value
      val overrides = (Test / dependencyOverrides).value.toSeq
      val repositories = (Test / resolvers).value
      val fullversion = scalaVersion.value
      val binaryversion = scalaBinaryVersion.value
      val project = thisProject.value
      val isplugin = sbtPlugin.value
      input.foreach { value =>
        if (isplugin) sys.error("[sbt-cozy artifact] sbt-plugin projects are unsupported")
        if (unmanaged.nonEmpty) sys.error("[sbt-cozy artifact] unmanaged JARs are unsupported with explicit selection")
        if (project.dependencies.nonEmpty) sys.error("[sbt-cozy artifact] project references are unsupported")
        CozyDevelopmentArtifactOverlay.validateSettings(value, expected, overrides, repositories, fullversion, binaryversion)
        CozyDevelopmentArtifactOverlay.validateClasspath(value, report, classpath.map(_.data), "Test")
      }
      ()
    },
    Compile / compile := {
      val compiled = ((Compile / compile) dependsOn _checkcompile).value
      val input = _selection.value
      val report = update.value
      val classpath = (Compile / dependencyClasspath).value.map(_.data)
      val project = thisProject.value
      input.filter(_.kind == "consumer").foreach(value =>
        CozyDevelopmentArtifactOverlay.record(value, report, classpath, project.id, "Compile"))
      compiled
    },
    Test / compile := ((Test / compile) dependsOn _checktest).value,
    Test / test := {
      ((Test / test) dependsOn _checktest).value
      val input = _selection.value
      val report = update.value
      val classpath = (Test / dependencyClasspath).value.map(_.data)
      val project = thisProject.value
      input.filter(_.kind == "consumer").foreach(value =>
        CozyDevelopmentArtifactOverlay.record(value, report, classpath, project.id, "Test"))
    },
    Test / testOnly := {
      (Test / testOnly).evaluated
      val input = _selection.value
      val report = update.value
      val classpath = (Test / dependencyClasspath).value.map(_.data)
      val project = thisProject.value
      input.filter(_.kind == "consumer").foreach(value =>
        CozyDevelopmentArtifactOverlay.record(value, report, classpath, project.id, "Test"))
    },
    Test / testQuick := {
      (Test / testQuick).evaluated
      val input = _selection.value
      val report = update.value
      val classpath = (Test / dependencyClasspath).value.map(_.data)
      val project = thisProject.value
      input.filter(_.kind == "consumer").foreach(value =>
        CozyDevelopmentArtifactOverlay.record(value, report, classpath, project.id, "Test"))
    },
    smDevelopmentArtifactPublish := {
      val input = _selection.value.getOrElse(sys.error("smDevelopmentArtifactPublish requires sm.artifact.selection"))
      val project = projectID.value
      val modulename = CrossVersion(project.crossVersion, scalaVersion.value, scalaBinaryVersion.value).
        map(_(project.name)).getOrElse(project.name)
      CozyDevelopmentArtifactOverlay.publish(input, (Compile / packageBin).value, makeIvyXml.value,
        project.organization, modulename, project.revision, update.value)
    }
  )
}
