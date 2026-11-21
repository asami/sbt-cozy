package org.goldenport.cozy

import sbt._
import sbt.Keys._

/*
 * Explicit opt-in for task-private development artifact selection.
 * @since   Nov. 12, 2026
 * @version Nov. 19, 2026
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
  private def _check_project_references(input: CozyDevelopmentArtifactOverlay.Input,
    project: ResolvedProject, build: java.net.URI): Unit = {
    val declared = input.producers.map(_.project).toSet
    val allowed = input.kind == "producer" && declared.nonEmpty && project.dependencies.forall { dependency =>
      dependency.project.build == build && declared.contains(dependency.project.project)
    }
    if (project.dependencies.nonEmpty && !allowed)
      sys.error("[sbt-cozy artifact] project references must belong to the declared producer set")
  }
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
      val build = thisProjectRef.value.build
      val isplugin = sbtPlugin.value
      input.foreach { value =>
        if (isplugin) sys.error("[sbt-cozy artifact] sbt-plugin projects are unsupported")
        if (unmanaged.nonEmpty) sys.error("[sbt-cozy artifact] unmanaged JARs are unsupported with explicit selection")
        _check_project_references(value, project, build)
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
      val build = thisProjectRef.value.build
      val isplugin = sbtPlugin.value
      input.foreach { value =>
        if (isplugin) sys.error("[sbt-cozy artifact] sbt-plugin projects are unsupported")
        if (unmanaged.nonEmpty) sys.error("[sbt-cozy artifact] unmanaged JARs are unsupported with explicit selection")
        _check_project_references(value, project, build)
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
    smDevelopmentArtifactPublish := Def.taskDyn {
      val input = _selection.value.getOrElse(sys.error("smDevelopmentArtifactPublish requires sm.artifact.selection"))
      val build = thisProjectRef.value.build
      if (input.producers.nonEmpty) {
        val tasks = input.producers.map { declared =>
          val ref = ProjectRef(build, declared.project)
          Def.task {
            val project = (ref / projectID).value
            val structure = (ref / thisProject).value
            val fullversion = (ref / scalaVersion).value
            val binaryversion = (ref / scalaBinaryVersion).value
            val crossname = CrossVersion(project.crossVersion, fullversion, binaryversion)
            val modulename = crossname.fold(project.name)(_(project.name))
            _check_project_references(input, structure, build)
            CozyDevelopmentArtifactOverlay.BatchMember(declared.producer, (ref / Compile / packageBin).value,
              (ref / makeIvyXml).value, project.organization, modulename, project.revision, (ref / update).value,
              (ref / packagedArtifacts).value.toVector, crossname)
          }
        }
        val empty: Def.Initialize[Task[Vector[CozyDevelopmentArtifactOverlay.BatchMember]]] =
          Def.task { Vector.empty[CozyDevelopmentArtifactOverlay.BatchMember] }
        val joined = tasks.foldLeft(empty) { (acc, task) =>
          Def.task { acc.value :+ task.value }
        }
        Def.task {
          val members = joined.value
          CozyDevelopmentArtifactOverlay.publishBatch(input, members).head
        }
      } else Def.task {
        val project = projectID.value
        val modulename = CrossVersion(project.crossVersion, scalaVersion.value, scalaBinaryVersion.value).
          map(_(project.name)).getOrElse(project.name)
        CozyDevelopmentArtifactOverlay.publish(input, (Compile / packageBin).value, makeIvyXml.value,
          project.organization, modulename, project.revision, update.value)
      }
    }.value
  )
}
