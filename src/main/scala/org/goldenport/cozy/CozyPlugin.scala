package org.goldenport.cozy

import java.time.Instant
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._

import sbt._
import sbt.Keys._
import scala.sys.process._

/*
 * @since   Mar. 22, 2026
 *  version Mar. 23, 2026
 *  version Mar. 25, 2026
 *  version Apr.  1, 2026
 *  version Apr.  4, 2026
 *  version Apr. 25, 2026
 *  version May. 26, 2026
 *  version Jun. 18, 2026
 * @version Jul. 12, 2026
 * @author  ASAMI, Tomoharu
 */
final case class CozyProjectConfig(values: Map[String, String], lists: Map[String, Seq[String]]) {
  def value(path: String): Option[String] = values.get(path).map(_.trim).filter(_.nonEmpty)
  def boolean(path: String): Option[Boolean] = value(path).map(_.toLowerCase(java.util.Locale.ROOT)).collect {
    case "true" | "yes" | "on" => true
    case "false" | "no" | "off" => false
  }
  def list(path: String): Seq[String] = lists.getOrElse(path, Seq.empty).map(_.trim).filter(_.nonEmpty)
  def mapUnder(path: String): Map[String, String] = {
    val prefix = path + "."
    values.collect {
      case (k, v) if k.startsWith(prefix) && v.trim.nonEmpty => k.substring(prefix.length) -> v.trim
    }
  }
}
object CozyProjectConfig {
  val empty: CozyProjectConfig = CozyProjectConfig(Map.empty, Map.empty)

  def load(file: File): CozyProjectConfig =
    if (file.isFile)
      parse(Files.readAllLines(file.toPath, StandardCharsets.UTF_8).asScala.toSeq)
    else
      empty

  def parse(lines: Seq[String]): CozyProjectConfig = {
    var stack = Vector.empty[(Int, String)]
    var values = Map.empty[String, String]
    var lists = Map.empty[String, Vector[String]]

    def _current_path_ = stack.map(_._2).mkString(".")

    lines.foreach { raw =>
      val line = if (raw.trim.startsWith("#")) "" else raw
      if (line.trim.nonEmpty) {
        val indent = line.takeWhile(_ == ' ').length
        val trimmed = line.trim
        if (trimmed.startsWith("- ")) {
          stack = stack.dropRight(stack.reverse.takeWhile(_._1 >= indent).length)
          val key = _current_path_
          if (key.nonEmpty)
            lists = lists.updated(key, lists.getOrElse(key, Vector.empty) :+ _unquote(trimmed.substring(2).trim))
        } else {
          val n = trimmed.indexOf(':')
          if (n >= 0) {
            val key = trimmed.substring(0, n).trim
            val rest = trimmed.substring(n + 1).trim
            stack = stack.dropRight(stack.reverse.takeWhile(_._1 >= indent).length)
            if (rest.isEmpty)
              stack = stack :+ (indent -> key)
            else
              values = values.updated((stack.map(_._2) :+ key).mkString("."), _unquote(rest))
          }
        }
      }
    }
    CozyProjectConfig(values, lists)
  }

  private def _unquote(s: String): String = {
    val t = s.trim
    if (t.length >= 2 && ((t.head == '"' && t.last == '"') || (t.head == '\'' && t.last == '\'')))
      t.substring(1, t.length - 1)
    else
      t
  }
}

final case class CozyCoursierChannelEntry(
  name: String,
  repositories: Seq[String],
  dependencies: Seq[String],
  mainClass: String
)

object CozyPlugin extends AutoPlugin {
  object autoImport {
    type CozyCoursierChannelEntry = org.goldenport.cozy.CozyCoursierChannelEntry
    val CozyCoursierChannelEntry = org.goldenport.cozy.CozyCoursierChannelEntry

    val cozyConfigFile = settingKey[File]("Project-local Cozy config file, normally .cozy/config.yaml")
    val cozyProjectConfig = settingKey[CozyProjectConfig]("Parsed project-local Cozy config")
    val cozyProjectFile = settingKey[File]("Project publication metadata file, normally project.yaml")
    val cozyProjectMetadata = settingKey[CozyProjectConfig]("Parsed project publication metadata")
    val cozyConfig = settingKey[CozyConfig]("Configuration for sbt-cozy code generation")
    val cozySourceDir = settingKey[File]("Directory containing CML/cozy sources")
    val cozyTargetDir = settingKey[File]("Directory where Scala sources are generated")
    val cozyGeneratorBackend = settingKey[String]("Generator backend. Either 'cozy' or 'legacy'.")
    val cozyDelegateProjectDir = settingKey[Option[File]]("Optional path to cozy project used by delegated generation during development.")
    val cozyDelegateCoursierVersion = settingKey[Option[String]]("Optional cozy version used to execute delegated generation through coursier during development.")
    val cozyDelegateCommand = settingKey[Seq[String]]("Command prefix used to execute delegated cozy generation and packaging.")
    val cozyGenerationVersionOverrides = settingKey[Map[String, String]]("Explicit generation version overrides passed to Cozy bridge settings.")
    val cozySkipUnchangedGeneration = settingKey[Boolean]("Skip code generation when CML timestamps and generator settings are unchanged.")
    val cozyWebDescriptorSync = settingKey[Boolean]("Synchronize src/main/web-inf/form.yaml from CML WEB operation form metadata.")
    val cozyGenerate = taskKey[Seq[File]]("Generate Scala sources from CML/cozy definitions")
    val cozyRuntimeClasspathFile = taskKey[File]("Write runtime classpath file for direct Java execution.")
    val cozyPrepareRuntime = taskKey[File]("Compile sample outputs and prepare runtime classpath file.")

    val cozyPackaging = settingKey[String]("Default packaging target. Either 'car' or 'sar'.")
    val cozyWireStandardPublishTasks = settingKey[Boolean]("Wire sbt publish/publishLocal to cozy CAR/SAR publication tasks.")
    val cozyCarName = settingKey[String]("Base file name of the generated CAR archive")
    val cozySarName = settingKey[String]("Base file name of the generated SAR archive")
    val cozyComponentApiJar = taskKey[Option[File]]("Build the generated contract-only component API jar when the component provides an API")
    val cozySpiJars = settingKey[Seq[File]]("Additional SPI jars to include under CAR /spi")
    val cozySarExtensionJars = settingKey[Seq[File]]("Injected extension jars to include under SAR /extension")
    val cozyManifestMetadata = settingKey[Map[String, String]]("Additional metadata fields written to the CAR component descriptor")
    val cozyLocalRepositoryDir = settingKey[File]("Legacy local repository directory; publish tasks use cozyWarehouseDir")
    val cozyDistributionDir = settingKey[File]("Release distribution repository directory for cozyDistributeCar/cozyDistributeSar")
    val cozyDistributionRequireReleaseVersion = settingKey[Boolean]("Reject distribution tasks for SNAPSHOT versions")
    val cozyWarehouseDir = settingKey[File]("Warehouse directory indexed by cozyIndexWarehouse")
    val cozyLocalWarehouseDir = settingKey[File]("Local CNCF warehouse root used by cozyPublishLocalCar/cozyPublishLocalSar")
    val cozyWarehouseMavenCoordinates = settingKey[Seq[String]]("Maven coordinates indexed from the warehouse")
    val cozyWarehouseRepositoryArtifacts = settingKey[Seq[String]]("Repository artifact types indexed from the warehouse, such as car or sar")
    val cozyWarehouseRepositoryModules = settingKey[Seq[String]]("Repository artifact module names indexed from the warehouse")
    val cozyWarehouseDownloadSamples = settingKey[Seq[String]]("Download sample publications indexed from the warehouse")
    val cozyPublicationDir = settingKey[File]("Directory where cozyPublishProject writes BoK publication sources")
    val cozyPublicationKind = settingKey[Option[String]]("Optional publication project kind: car, sar, sample-single, or sample-multi")
    val cozyPublicationName = settingKey[Option[String]]("Optional publication stable name used for URLs and BoK keys")
    val cozyPublicationTitle = settingKey[Option[String]]("Optional publication display title")
    val cozyPublicationPath = settingKey[Option[String]]("Optional publication site path from publication.path or project.path")
    val cozyPublicationSamplesDir = settingKey[Option[File]]("Optional sample collection directory for sample-multi publication and distribution")
    val cozyCoursierChannelWarehouseDir = settingKey[Option[File]]("Optional warehouse root for Coursier channel descriptor publication")
    val cozyCoursierChannelPath = settingKey[String]("Warehouse-relative Coursier channel descriptor path")
    val cozyCoursierChannelEntries = settingKey[Seq[CozyCoursierChannelEntry]]("Coursier channel entries published by this project")

    val cozyBuildCar = taskKey[File]("Build Car archive from compiled outputs")
    val cozyBuildSar = taskKey[File]("Build Sar archive from cozy source definitions")
    val cozyPublishCar = taskKey[File]("Publish Car archive and catalog to the warehouse")
    val cozyPublishSar = taskKey[File]("Publish Sar archive and catalog to the warehouse")
    val cozyPublishLocalCar = taskKey[File]("Publish Car archive and catalog to the local CNCF repository")
    val cozyPublishLocalSar = taskKey[File]("Publish Sar archive and catalog to the local CNCF repository")
    val cozyDistributeCar = taskKey[File]("Copy Car archive to the release distribution repository")
    val cozyDistributeSar = taskKey[File]("Copy Sar archive to the release distribution repository")
    val cozyBuildCAR = taskKey[File]("Compatibility alias for cozyBuildCar")
    val cozyBuildSAR = taskKey[File]("Compatibility alias for cozyBuildSar")
    val cozyPublishCAR = taskKey[File]("Compatibility alias for cozyPublishCar")
    val cozyPublishSAR = taskKey[File]("Compatibility alias for cozyPublishSar")
    val cozyDistributeCAR = taskKey[File]("Compatibility alias for cozyDistributeCar")
    val cozyDistributeSAR = taskKey[File]("Compatibility alias for cozyDistributeSar")
    val cozyDistributeSamples = taskKey[Seq[File]]("Copy versioned sample ZIP archives to the release distribution warehouse")
    val cozyPlanDistributeSamples = taskKey[Seq[File]]("Print planned sample ZIP archive paths without writing to the warehouse")
    val cozyDistribute = taskKey[File]("Copy the configured CAR or SAR archive to the release distribution repository")
    val cozyPublishProject = taskKey[File]("Generate SmartDox site BoK publication sources from this sbt project")
    val cozyIndexWarehouse = taskKey[File]("Generate publish.d artifact and release metadata by indexing the warehouse")
    val cozyPublishCoursierChannel = taskKey[File]("Publish configured Coursier channel entries to the warehouse")
  }

  import autoImport._

  override def requires: Plugins = plugins.JvmPlugin
  override def trigger: PluginTrigger = noTrigger

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    cozyConfigFile := baseDirectory.value / ".cozy" / "config.yaml",
    cozyProjectConfig := CozyProjectConfig.load(cozyConfigFile.value),
    cozyProjectFile := baseDirectory.value / "project.yaml",
    cozyProjectMetadata := CozyProjectConfig.load(cozyProjectFile.value),
    cozyConfig := CozyConfig.default,
    cozySourceDir := _config_file(baseDirectory.value, cozyProjectConfig.value.value("generation.source_dir")).getOrElse((Compile / sourceDirectory).value / "cozy"),
    cozyTargetDir := _config_file(baseDirectory.value, cozyProjectConfig.value.value("generation.target_dir")).getOrElse((Compile / sourceManaged).value),
    cozyGeneratorBackend := cozyProjectConfig.value.value("generation.backend").orElse(sys.env.get("SBT_COZY_GENERATOR_BACKEND")).getOrElse("cozy"),
    cozyDelegateProjectDir := _config_file(baseDirectory.value, cozyProjectConfig.value.value("generation.delegate.project_dir")).orElse(sys.env.get("SBT_COZY_PROJECT_DIR").map(file)),
    cozyDelegateCoursierVersion := cozyProjectConfig.value.value("generation.delegate.coursier_version").orElse(sys.env.get("SBT_COZY_COURSIER_VERSION")).map(_.trim).filter(_.nonEmpty),
    cozyDelegateCommand := {
      val command = cozyProjectConfig.value.list("generation.delegate.command")
      if (command.nonEmpty) command else cozyDelegateCoursierVersion.value.map(CozySbtBridge.coursierCommand).getOrElse(Seq("cozy"))
    },
    cozyGenerationVersionOverrides := Map.empty,
    cozySkipUnchangedGeneration := cozyProjectConfig.value.boolean("generation.skip_unchanged").getOrElse(true),
    cozyWebDescriptorSync := cozyProjectConfig.value.boolean("generation.web_descriptor_sync").getOrElse(true),

    cozyPackaging := cozyProjectConfig.value.value("packaging.kind").getOrElse("car"),
    cozyWireStandardPublishTasks := cozyProjectConfig.value.boolean("packaging.wire_standard_publish_tasks").getOrElse(true),
    cozyCarName := s"${moduleName.value}-${version.value}",
    cozySarName := s"${moduleName.value}-${version.value}",
    cozySpiJars := Seq.empty,
    cozySarExtensionJars := Seq.empty,
    cozyManifestMetadata := Map.empty,
    cozyLocalRepositoryDir := _config_file(baseDirectory.value, cozyProjectConfig.value.value("packaging.localRepositoryDir")).getOrElse(target.value / "cozy-repository"),
    cozyDistributionDir := _config_file(baseDirectory.value, cozyProjectConfig.value.value("distribution.repository")).orElse(_config_file(baseDirectory.value, cozyProjectConfig.value.value("warehouse.repository"))).getOrElse(target.value / "cozy-distribution"),
    cozyDistributionRequireReleaseVersion := cozyProjectConfig.value.boolean("distribution.require_release_version").getOrElse(true),
    cozyWarehouseDir := _config_file(baseDirectory.value, cozyProjectConfig.value.value("warehouse.repository")).getOrElse(cozyDistributionDir.value),
    cozyLocalWarehouseDir := localRepositoryDir(baseDirectory.value, cozyProjectConfig.value, file(sys.props.getOrElse("user.home", "."))),
    cozyWarehouseMavenCoordinates := {
      val configured = cozyProjectConfig.value.list("warehouse.maven.coordinates")
      if (configured.nonEmpty) configured
      else Seq(_default_maven_coordinate(organization.value, moduleName.value, crossPaths.value, scalaBinaryVersion.value, sbtPlugin.value, sbtBinaryVersion.value))
    },
    cozyWarehouseRepositoryArtifacts := cozyProjectConfig.value.list("warehouse.repository_artifacts.include"),
    cozyWarehouseRepositoryModules := {
      val configured = cozyProjectConfig.value.list("warehouse.repository_artifacts.modules")
      if (configured.nonEmpty) configured else Seq(cozyPublicationName.value.getOrElse(moduleName.value))
    },
    cozyWarehouseDownloadSamples := {
      val configured = cozyProjectConfig.value.list("warehouse.download.samples")
      if (configured.nonEmpty) configured else Seq(cozyPublicationName.value.getOrElse(moduleName.value))
    },
    cozyPublicationDir := _config_file(baseDirectory.value, cozyProjectConfig.value.value("publication.output")).getOrElse(target.value / "publish.d"),
    cozyPublicationKind := cozyProjectConfig.value.value("publication.kind"),
    cozyPublicationName := cozyProjectConfig.value.value("publication.name"),
    cozyPublicationTitle := cozyProjectConfig.value.value("publication.title"),
    cozyPublicationPath := publicationPath(cozyProjectConfig.value, cozyProjectMetadata.value),
    cozyPublicationSamplesDir := _config_file(baseDirectory.value, cozyProjectConfig.value.value("publication.samples_dir")),
    cozyCoursierChannelWarehouseDir := _config_file(baseDirectory.value, cozyProjectConfig.value.value("coursier.channel.warehouse")),
    cozyCoursierChannelPath := cozyProjectConfig.value.value("coursier.channel.path").getOrElse("repository/cozy/coursier-channel.json"),
    cozyCoursierChannelEntries := Seq.empty,
    cozyGenerate := {
      val sourcedir = cozySourceDir.value
      val targetdir = cozyTargetDir.value
      val config = cozyConfig.value
      val backend = cozyGeneratorBackend.value.trim.toLowerCase
      val delegateprojectdir = cozyDelegateProjectDir.value
      val delegatecommand = cozyDelegateCommand.value
      val generationversionoverrides = cozyGenerationVersionOverrides.value ++ Map(
        "component.module" -> moduleName.value,
        "component.version" -> version.value
      )
      val skipUnchanged = cozySkipUnchangedGeneration.value
      val log = streams.value.log

      CozyConfigValidator.validate(config) match {
        case Right(_) =>
        case Left(message) => sys.error(s"[sbt-cozy] ${message}")
      }

      val cozyfiles = CozyFileLoader.load(sourcedir)
      if (cozyfiles.isEmpty) {
        log.debug(s"[sbt-cozy] no cozy sources found under ${sourcedir.getAbsolutePath}")
        Seq.empty
      } else {
        if (cozyWebDescriptorSync.value) {
          CozyWebDescriptorSync.sync(
            projectdir = baseDirectory.value,
            componentname = moduleName.value,
            cozyfiles = cozyfiles,
            log = log
          )
        }
        val statefile = target.value / "sbt-cozy" / "generation-state.properties"
        val currentinputs = CozyGenerationState.capture(sourcedir, cozyfiles, backend, config, generationversionoverrides)
        val currentoutputs = CozyGenerationState.currentoutputs(targetdir)

        if (skipUnchanged && CozyGenerationState.isUpToDate(statefile, currentinputs, currentoutputs)) {
          log.info(s"[sbt-cozy] skipped generation; CML timestamps unchanged (${currentoutputs.size} source(s) reused)")
          currentoutputs
        } else {
        backend match {
          case "cozy" =>
            if (config != CozyConfig.default) {
              log.warn("[sbt-cozy] cozy backend ignores cozyConfig options; using cozy modeler defaults")
            }
            val generated = CozyDelegatedGenerator.generate(
              sourcedir = sourcedir,
              cozyfiles = cozyfiles,
              targetdir = targetdir,
              targetbasedir = target.value,
              basedir = baseDirectory.value,
              delegateprojectdir = delegateprojectdir,
              delegatecommand = delegatecommand,
              settings = generationversionoverrides,
              log = log
            )
            CozyGenerationState.write(statefile, currentinputs)
            generated
          case "legacy" =>
            val model = _parse_validated_model(cozyfiles)
            val generated = CozyGenerator.generate(model, targetdir, config)
            log.info(s"[sbt-cozy] generated ${generated.size} Scala source(s) using legacy backend")
            CozyGenerationState.write(statefile, currentinputs)
            generated
          case other =>
            sys.error(s"[sbt-cozy] invalid cozyGeneratorBackend '${other}'. expected 'cozy' or 'legacy'")
        }
        }
      }
    },

    cozyComponentApiJar := {
      val log = streams.value.log
      val descriptor = target.value / "cozy" / "component-api-descriptor.json"
      val mainjar = (Compile / packageBin).value
      if (!descriptor.isFile) {
        None
      } else {
        val output = target.value / "cozy" / "spi" / s"${moduleName.value}-api.jar"
        IO.createDirectory(output.getParentFile)
        CozySbtBridge.buildComponentApiJar(
          output = output,
          mainjar = mainjar,
          descriptor = descriptor,
          basedir = baseDirectory.value,
          delegateprojectdir = cozyDelegateProjectDir.value,
          delegatecommand = cozyDelegateCommand.value,
          log = log
        )
        Option(output).filter(_.isFile)
      }
    },

    cozyBuildCar := {
      val sourcedir = cozySourceDir.value
      val log = streams.value.log
      val archive = target.value / s"${cozyCarName.value}.car"

      val mainjar = (Compile / packageBin).value
      val classpathjars = (Compile / dependencyClasspath).value
        .map(_.data)
        .filter(file => file.isFile && file.getName.endsWith(".jar"))
      val libjars = classpathjars
        .filterNot(_.getAbsolutePath == mainjar.getAbsolutePath)
        .distinct
        .sortBy(_.getName)
      val generatedapijar = cozyComponentApiJar.value.toSeq
      val spijars = (generatedapijar ++ cozySpiJars.value)
        .filter(file => file.isFile && file.getName.endsWith(".jar"))
        .distinct
        .sortBy(_.getName)
      val componentapidescriptor = Option(target.value / "cozy" / "component-api-descriptor.json").filter(_.isFile)
      val packagingmetadata = CozyManifestMetadata.from(cozyManifestMetadata.value, moduleName.value, version.value)
      CozySbtBridge.packageCar(
        archive = archive,
        mainjar = mainjar,
        libjars = libjars,
        spijars = spijars,
        componentapidescriptor = componentapidescriptor,
        projectdir = baseDirectory.value,
        name = moduleName.value,
        version = version.value,
        component = packagingmetadata.component,
        extensions = packagingmetadata.extensions,
        config = packagingmetadata.config,
        basedir = baseDirectory.value,
        delegateprojectdir = cozyDelegateProjectDir.value,
        delegatecommand = cozyDelegateCommand.value,
        log = log
      )

      streams.value.log.info(s"[sbt-cozy] built CAR: ${archive.getAbsolutePath}")
      archive
    },

    cozyBuildCAR := cozyBuildCar.value,

    cozyBuildSar := {
      val sourcedir = cozySourceDir.value
      val sarsources = CozyFileLoader.loadSarSources(sourcedir)
      val log = streams.value.log
      if (sarsources.isEmpty) {
        sys.error(s"[sbt-cozy] no subsystem sources found under ${sourcedir.getAbsolutePath}; SAR requires descriptor or cozy definition sources")
      }
      val archive = target.value / s"${cozySarName.value}.sar"
      val subsystemsources = sarsources.map(file => file -> CozyPackaging.relativepath(sourcedir, file))
      val extensionJars = cozySarExtensionJars.value
        .filter(file => file.isFile && file.getName.endsWith(".jar"))
        .distinct
        .sortBy(_.getName)
      val applicationConf = (Compile / resourceDirectory).value / "application.conf"

      CozySbtBridge.packageSar(
        archive = archive,
        sourcedir = sourcedir,
        sourceFiles = subsystemsources.map(_._2),
        extensionJars = extensionJars,
        applicationConf = if (applicationConf.exists()) Some(applicationConf) else None,
        basedir = baseDirectory.value,
        delegateprojectdir = cozyDelegateProjectDir.value,
        delegatecommand = cozyDelegateCommand.value,
        log = log
      )

      streams.value.log.info(s"[sbt-cozy] built SAR: ${archive.getAbsolutePath}")
      archive
    },

    cozyBuildSAR := cozyBuildSar.value,

    cozyPublishCar := Def.taskDyn {
      validatePublishVersion(version.value, "cozyPublishCar", expectsnapshot = false)
      Def.task {
        val archive = cozyBuildCar.value
        val artifactname = cozyPublicationName.value.getOrElse(moduleName.value)
        val destination = _repository_artifact_destination(cozyWarehouseDir.value, "car", artifactname, version.value)
        CozySbtBridge.publishCar(
          projectdir = baseDirectory.value,
          warehousedir = cozyWarehouseDir.value,
          name = artifactname,
          version = version.value,
          archive = archive,
          basedir = baseDirectory.value,
          delegateprojectdir = cozyDelegateProjectDir.value,
          delegatecommand = cozyDelegateCommand.value,
          log = streams.value.log
        )
        streams.value.log.info(s"[sbt-cozy] published Car to ${destination.getAbsolutePath}")
        destination
      }
    }.value,

    cozyPublishCAR := cozyPublishCar.value,

    cozyPublishSar := Def.taskDyn {
      validatePublishVersion(version.value, "cozyPublishSar", expectsnapshot = false)
      Def.task {
        val archive = cozyBuildSar.value
        val artifactname = cozyPublicationName.value.getOrElse(moduleName.value)
        val destination = _repository_artifact_destination(cozyWarehouseDir.value, "sar", artifactname, version.value)
        CozySbtBridge.publishSar(
          projectdir = baseDirectory.value,
          warehousedir = cozyWarehouseDir.value,
          name = artifactname,
          version = version.value,
          archive = archive,
          basedir = baseDirectory.value,
          delegateprojectdir = cozyDelegateProjectDir.value,
          delegatecommand = cozyDelegateCommand.value,
          log = streams.value.log
        )
        streams.value.log.info(s"[sbt-cozy] published Sar to ${destination.getAbsolutePath}")
        destination
      }
    }.value,

    cozyPublishSAR := cozyPublishSar.value,

    cozyPublishLocalCar := Def.taskDyn {
      validatePublishVersion(version.value, "cozyPublishLocalCar", expectsnapshot = true)
      Def.task {
        val archive = cozyBuildCar.value
        val artifactname = cozyPublicationName.value.getOrElse(moduleName.value)
        val localwarehouse = cozyLocalWarehouseDir.value
        val destination = _repository_artifact_destination(localwarehouse, "car", artifactname, version.value)
        CozySbtBridge.publishCar(
          projectdir = baseDirectory.value,
          warehousedir = localwarehouse,
          name = artifactname,
          version = version.value,
          archive = archive,
          basedir = baseDirectory.value,
          delegateprojectdir = cozyDelegateProjectDir.value,
          delegatecommand = cozyDelegateCommand.value,
          log = streams.value.log
        )
        streams.value.log.info(s"[sbt-cozy] published local Car to ${destination.getAbsolutePath}")
        destination
      }
    }.value,

    cozyPublishLocalSar := Def.taskDyn {
      validatePublishVersion(version.value, "cozyPublishLocalSar", expectsnapshot = true)
      Def.task {
        val archive = cozyBuildSar.value
        val artifactname = cozyPublicationName.value.getOrElse(moduleName.value)
        val localwarehouse = cozyLocalWarehouseDir.value
        val destination = _repository_artifact_destination(localwarehouse, "sar", artifactname, version.value)
        CozySbtBridge.publishSar(
          projectdir = baseDirectory.value,
          warehousedir = localwarehouse,
          name = artifactname,
          version = version.value,
          archive = archive,
          basedir = baseDirectory.value,
          delegateprojectdir = cozyDelegateProjectDir.value,
          delegatecommand = cozyDelegateCommand.value,
          log = streams.value.log
        )
        streams.value.log.info(s"[sbt-cozy] published local Sar to ${destination.getAbsolutePath}")
        destination
      }
    }.value,

    publish := Def.taskDyn {
      if (!cozyWireStandardPublishTasks.value) {
        sys.error("[sbt-cozy] standard sbt publish is disabled by cozyWireStandardPublishTasks=false; define publish explicitly or use cozyPublishCar/cozyPublishSar")
      }
      publishTaskLabel(cozyPackaging.value, local = false) match {
        case "cozyPublishCar" => Def.task { val _ = cozyPublishCar.value; () }
        case "cozyPublishSar" => Def.task { val _ = cozyPublishSar.value; () }
      }
    }.value,

    publishLocal := Def.taskDyn {
      if (!cozyWireStandardPublishTasks.value) {
        sys.error("[sbt-cozy] standard sbt publishLocal is disabled by cozyWireStandardPublishTasks=false; define publishLocal explicitly or use cozyPublishLocalCar/cozyPublishLocalSar")
      }
      publishTaskLabel(cozyPackaging.value, local = true) match {
        case "cozyPublishLocalCar" => Def.task { val _ = cozyPublishLocalCar.value; () }
        case "cozyPublishLocalSar" => Def.task { val _ = cozyPublishLocalSar.value; () }
      }
    }.value,

    cozyDistributeCar := {
      _validate_release_distribution(version.value, cozyDistributionRequireReleaseVersion.value)
      val archive = cozyBuildCar.value
      val destination = _repository_artifact_destination(cozyWarehouseDir.value, "car", version.value, archive)
      IO.createDirectory(destination.getParentFile)
      IO.copyFile(archive, destination, preserveLastModified = true)
      streams.value.log.info(s"[sbt-cozy] distributed CAR to ${destination.getAbsolutePath}")
      destination
    },

    cozyDistributeCAR := cozyDistributeCar.value,

    cozyDistributeSar := {
      _validate_release_distribution(version.value, cozyDistributionRequireReleaseVersion.value)
      val archive = cozyBuildSar.value
      val destination = _repository_artifact_destination(cozyWarehouseDir.value, "sar", version.value, archive)
      IO.createDirectory(destination.getParentFile)
      IO.copyFile(archive, destination, preserveLastModified = true)
      streams.value.log.info(s"[sbt-cozy] distributed SAR to ${destination.getAbsolutePath}")
      destination
    },

    cozyDistributeSAR := cozyDistributeSar.value,

    cozyDistributeSamples := {
      _validate_release_distribution(version.value, cozyDistributionRequireReleaseVersion.value)
      val name = cozyPublicationName.value.getOrElse(moduleName.value)
      val publicationpath = cozyPublicationPath.value
      CozySbtBridge.distributeSamples(
        projectDir = baseDirectory.value,
        warehouseDir = cozyWarehouseDir.value,
        name = name,
        publicationPath = publicationpath,
        version = version.value,
        samplesDir = cozyPublicationSamplesDir.value,
        dryRun = false,
        basedir = baseDirectory.value,
        delegateprojectdir = cozyDelegateProjectDir.value,
        delegatecommand = cozyDelegateCommand.value,
        log = streams.value.log
      )
      val root = _sample_download_root(cozyWarehouseDir.value, name, publicationpath)
      val files = if (root.exists()) (root ** s"*-${version.value}.zip").get.sortBy(_.getAbsolutePath) else Seq.empty[File]
      streams.value.log.info(s"[sbt-cozy] distributed ${files.size} sample archive(s) under ${root.getAbsolutePath}")
      files
    },

    cozyPlanDistributeSamples := {
      val name = cozyPublicationName.value.getOrElse(moduleName.value)
      val publicationpath = cozyPublicationPath.value
      val samplesdir = cozyPublicationSamplesDir.value.getOrElse(baseDirectory.value / "samples")
      CozySbtBridge.distributeSamples(
        projectDir = baseDirectory.value,
        warehouseDir = cozyWarehouseDir.value,
        name = name,
        publicationPath = publicationpath,
        version = version.value,
        samplesDir = Some(samplesdir),
        dryRun = true,
        basedir = baseDirectory.value,
        delegateprojectdir = cozyDelegateProjectDir.value,
        delegatecommand = cozyDelegateCommand.value,
        log = streams.value.log
      )
      val root = _sample_download_root(cozyWarehouseDir.value, name, publicationpath)
      val planned = _planned_sample_archives(root, name, version.value, samplesdir)
      val warehousedir = cozyWarehouseDir.value
      val log = streams.value.log
      log.info("[sbt-cozy] planned sample archive tree:")
      sampleArchiveTreeLines(warehousedir, root, planned).foreach { line =>
        log.info(s"[sbt-cozy] ${line}")
      }
      log.info(s"[sbt-cozy] planned ${planned.size} sample archive path(s)")
      planned
    },

    cozyDistribute := Def.taskDyn[File] {
      cozyPackaging.value.trim.toLowerCase(java.util.Locale.ROOT) match {
        case "car" => cozyDistributeCar
        case "sar" => cozyDistributeSar
        case "sample-multi" => Def.task { sys.error("[sbt-cozy] cozyDistributeSamples must be used for sample-multi because it can produce multiple archives"): File }
        case other => Def.task { sys.error(s"[sbt-cozy] invalid cozyPackaging '${other}'. expected 'car', 'sar', or 'sample-multi'"): File }
      }
    }.value,

    cozyPublishProject := {
      val out = cozyPublicationDir.value
      CozySbtBridge.publishProject(
        projectDir = baseDirectory.value,
        savedir = out,
        kind = cozyPublicationKind.value,
        name = cozyPublicationName.value.getOrElse(moduleName.value),
        title = cozyPublicationTitle.value,
        publicationPath = cozyPublicationPath.value,
        organization = organization.value,
        version = version.value,
        scalaVersion = scalaVersion.value,
        sbtVersion = appConfiguration.value.provider.id.version,
        basedir = baseDirectory.value,
        delegateprojectdir = cozyDelegateProjectDir.value,
        delegatecommand = cozyDelegateCommand.value,
        log = streams.value.log
      )
      streams.value.log.info(s"[sbt-cozy] generated publication sources: ${out.getAbsolutePath}")
      out
    },

    cozyIndexWarehouse := {
      val out = cozyPublicationDir.value
      CozySbtBridge.indexWarehouse(
        warehouseDir = cozyWarehouseDir.value,
        savedir = out,
        name = cozyPublicationName.value.getOrElse(moduleName.value),
        title = cozyPublicationTitle.value,
        mavenCoordinates = cozyWarehouseMavenCoordinates.value,
        repositoryArtifacts = cozyWarehouseRepositoryArtifacts.value,
        repositoryModules = cozyWarehouseRepositoryModules.value,
        downloadSamples = cozyWarehouseDownloadSamples.value,
        basedir = baseDirectory.value,
        delegateprojectdir = cozyDelegateProjectDir.value,
        delegatecommand = cozyDelegateCommand.value,
        log = streams.value.log
      )
      streams.value.log.info(s"[sbt-cozy] indexed warehouse metadata into: ${out.getAbsolutePath}")
      out
    },

    cozyPublishCoursierChannel := {
      publishCoursierChannelFile(
        warehouse = cozyCoursierChannelWarehouseDir.value.getOrElse(coursierChannelWarehouseDir(publishTo.value, cozyWarehouseDir.value)),
        channelpath = cozyCoursierChannelPath.value,
        entries = cozyCoursierChannelEntries.value,
        log = streams.value.log
      )
    },

    cozyRuntimeClasspathFile := {
      val out = target.value / "cncf.d" / "runtime-classpath.txt"
      val classpath = (Compile / fullClasspath).value
        .map(_.data.getAbsoluteFile)
        .distinct
        .map(_.getAbsolutePath)
      IO.createDirectory(out.getParentFile)
      IO.writeLines(out, classpath)
      streams.value.log.info(s"[sbt-cozy] wrote runtime classpath file: ${out.getAbsolutePath}")
      out
    },

    cozyPrepareRuntime := {
      val _ = (Compile / compile).value
      cozyRuntimeClasspathFile.value
    },

    Compile / sourceGenerators += cozyGenerate.taskValue
  )

  private def _validate_release_distribution(version: String, requirereleaseversion: Boolean): Unit =
    if (requirereleaseversion && version.toUpperCase(java.util.Locale.ROOT).contains("SNAPSHOT"))
      sys.error(s"[sbt-cozy] release distribution rejects SNAPSHOT version: ${version}")

  private[cozy] def validatePublishVersion(version: String, taskname: String, expectsnapshot: Boolean): Unit = {
    val issnapshot = isSnapshotVersion(version)
    if (expectsnapshot && !issnapshot)
      sys.error(s"[sbt-cozy] ${taskname} requires a SNAPSHOT version; release version '${version}' must use cozyPublishCar/cozyPublishSar")
    else if (!expectsnapshot && issnapshot)
      sys.error(s"[sbt-cozy] ${taskname} rejects SNAPSHOT version '${version}'; use cozyPublishLocalCar/cozyPublishLocalSar during SNAPSHOT development")
  }

  private[cozy] def isSnapshotVersion(version: String): Boolean =
    version.toUpperCase(java.util.Locale.ROOT).contains("SNAPSHOT")

  private[cozy] def publishTaskLabel(packaging: String, local: Boolean): String =
    packaging.trim.toLowerCase match {
      case "car" => if (local) "cozyPublishLocalCar" else "cozyPublishCar"
      case "sar" => if (local) "cozyPublishLocalSar" else "cozyPublishSar"
      case other => sys.error(s"[sbt-cozy] invalid cozyPackaging '${other}'. expected 'car' or 'sar'")
    }

  private[cozy] def publicationPath(config: CozyProjectConfig, projectmetadata: CozyProjectConfig): Option[String] =
    config.value("publication.path").orElse(projectmetadata.value("project.path"))

  private[cozy] def localRepositoryDir(base: File, config: CozyProjectConfig, home: File): File =
    _config_file(base, config.value("local.repository")).
      orElse(_config_file(base, config.value("cncf.local.repository"))).
      getOrElse(home / ".cncf" / "local")

  private[cozy] def coursierChannelWarehouseDir(publishresolver: Option[Resolver], fallback: File): File =
    publishresolver.
      flatMap(coursierChannelRepositoryFile).
      map(coursierChannelWarehouseDirFromMavenRepository).
      getOrElse(fallback)

  private[cozy] def coursierChannelRepositoryFile(resolver: Resolver): Option[File] =
    resolver match {
      case m: MavenRepository =>
        val root = m.root
        if (root.startsWith("file:"))
          Some(new File(new java.net.URI(root)))
        else
          Some(file(root))
      case f: FileRepository =>
        f.patterns.artifactPatterns.headOption.flatMap { pattern =>
          val marker = "/[organisation]/"
          val index = pattern.indexOf(marker)
          if (index >= 0)
            Some(file(pattern.take(index)))
          else
            None
        }
      case _ =>
        None
    }

  private[cozy] def coursierChannelWarehouseDirFromMavenRepository(repository: File): File =
    repository.getCanonicalFile match {
      case canonical
          if canonical.getName == "maven" &&
            canonical.getParentFile != null &&
            canonical.getParentFile.getName == "repository" =>
        canonical.getParentFile.getParentFile
      case canonical if canonical.getName == "maven" =>
        canonical.getParentFile
      case canonical =>
        sys.error(
          s"[sbt-cozy] Coursier channel publish requires publishTo to point at a Maven repository " +
            s"under a warehouse, but got: ${canonical}"
        )
    }

  private[cozy] def coursierChannelJson(existing: Option[String], entries: Seq[org.goldenport.cozy.CozyCoursierChannelEntry]): String = {
    val incoming = entries.map(_.name).toSet
    val preserved = existing.toVector.flatMap(parseCoursierChannelEntries).filterNot { case (name, _) =>
      incoming.contains(name)
    }
    val rendered = preserved ++ entries.map(entry => entry.name -> renderCoursierChannelEntry(entry))
    _render_coursier_channel(rendered)
  }

  private[cozy] def parseCoursierChannelEntries(text: String): Vector[(String, String)] = {
    val source = text.trim
    if (source.isEmpty)
      Vector.empty
    else {
      val start = source.indexOf('{')
      val end = source.lastIndexOf('}')
      if (start < 0 || end <= start)
        sys.error("[sbt-cozy] invalid Coursier channel JSON: root object not found")
      var i = start + 1
      val entries = Vector.newBuilder[(String, String)]
      def _skip_ws_and_commas_(): Unit =
        while (i < end && (source.charAt(i).isWhitespace || source.charAt(i) == ',')) i += 1
      while (i < end) {
        _skip_ws_and_commas_()
        if (i < end) {
          if (source.charAt(i) != '"')
            sys.error(s"[sbt-cozy] invalid Coursier channel JSON near offset $i: expected entry name")
          val key = _read_json_string(source, i)
          i = key._2
          while (i < end && source.charAt(i).isWhitespace) i += 1
          if (i >= end || source.charAt(i) != ':')
            sys.error(s"[sbt-cozy] invalid Coursier channel JSON near offset $i: expected ':'")
          i += 1
          while (i < end && source.charAt(i).isWhitespace) i += 1
          if (i >= end || source.charAt(i) != '{')
            sys.error(s"[sbt-cozy] invalid Coursier channel JSON near offset $i: expected entry object")
          val valuestart = i
          i = _read_json_object_end(source, i)
          entries += key._1 -> source.substring(valuestart, i)
        }
      }
      entries.result()
    }
  }

  private[cozy] def renderCoursierChannelEntry(entry: org.goldenport.cozy.CozyCoursierChannelEntry): String = {
    val repositories = _render_json_string_array(entry.repositories)
    val dependencies = _render_json_string_array(entry.dependencies)
    s"""{
       |  "repositories": $repositories,
       |  "dependencies": $dependencies,
       |  "mainClass": "${_json_escape(entry.mainClass)}"
       |}""".stripMargin
  }

  private[cozy] def publishCoursierChannelFile(
    warehouse: File,
    channelpath: String,
    entries: Seq[org.goldenport.cozy.CozyCoursierChannelEntry],
    log: sbt.util.Logger
  ): File = {
    if (entries.isEmpty)
      sys.error("[sbt-cozy] cozyPublishCoursierChannel requires at least one cozyCoursierChannelEntries value")
    val target = warehouse / channelpath
    val existing =
      if (target.isFile)
        Some(IO.read(target))
      else
        None
    IO.createDirectory(target.getParentFile)
    IO.write(target, coursierChannelJson(existing, entries))
    log.info(s"[sbt-cozy] published Coursier channel entries ${entries.map(_.name).mkString(", ")} to ${target.getAbsolutePath}")
    target
  }

  private def _render_coursier_channel(entries: Seq[(String, String)]): String = {
    val rendered = entries.map { case (name, value) =>
      val lines = value.linesIterator.toVector
      val head = lines.headOption.getOrElse("{}")
      val tail = lines.drop(1).map { line =>
        val stripped = line.stripLeading
        if (stripped == "}")
          s"  $stripped"
        else
          s"    $stripped"
      }
      (s"""  "${_json_escape(name)}": $head""" +: tail).mkString("\n")
    }
    (Vector("{") ++ rendered.zipWithIndex.map { case (entry, index) =>
      if (index + 1 == rendered.length) entry else s"$entry,"
    } ++ Vector("}")).mkString("\n") + "\n"
  }

  private def _render_json_string_array(values: Seq[String]): String =
    values.map(value => s""""${_json_escape(value)}"""").mkString("[", ", ", "]")

  private def _read_json_string(source: String, start: Int): (String, Int) = {
    val out = new StringBuilder
    var i = start + 1
    var escaped = false
    var done = false
    while (i < source.length && !done) {
      val c = source.charAt(i)
      if (escaped) {
        out.append(c)
        escaped = false
      } else {
        c match {
          case '\\' => escaped = true
          case '"' => done = true
          case other => out.append(other)
        }
      }
      i += 1
    }
    if (!done)
      sys.error(s"[sbt-cozy] invalid Coursier channel JSON near offset $start: unterminated string")
    out.toString -> i
  }

  private def _read_json_object_end(source: String, start: Int): Int = {
    var i = start
    var depth = 0
    var instring = false
    var escaped = false
    var done = false
    while (i < source.length && !done) {
      val c = source.charAt(i)
      if (instring) {
        if (escaped)
          escaped = false
        else {
          c match {
            case '\\' => escaped = true
            case '"' => instring = false
            case _ => ()
          }
        }
      } else {
        c match {
          case '"' => instring = true
          case '{' => depth += 1
          case '}' =>
            depth -= 1
            if (depth == 0) done = true
          case _ => ()
        }
      }
      i += 1
    }
    if (!done)
      sys.error(s"[sbt-cozy] invalid Coursier channel JSON near offset $start: unterminated object")
    i
  }

  private def _json_escape(value: String): String =
    value.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    }

  private def _sample_download_root(warehousedir: File, publicationname: String, publicationpath: Option[String]): File =
    warehousedir / "repository" / "download" / publicationpath.getOrElse(s"samples/${publicationname}")

  private def _planned_sample_archives(root: File, publicationname: String, version: String, samplesdir: File): Seq[File] = {
    val collection = root / version / s"${publicationname}-${version}.zip"
    val samples = _sample_project_dirs(samplesdir).map { sample =>
      val samplename = _sample_slug(sample.getName)
      root / version / samplename / s"${samplename}-${version}.zip"
    }
    (collection +: samples).sortBy(_.getAbsolutePath)
  }

  private def _sample_project_dirs(samplesdir: File): Seq[File] =
    if (!samplesdir.isDirectory)
      Seq.empty
    else
      Option(samplesdir.listFiles()).toSeq.flatten.
        filter(f => f.isDirectory && (f / "build.sbt").isFile).
        sortBy(_.getName)

  private def _sample_slug(value: String): String =
    value.toLowerCase(java.util.Locale.ROOT).
      replaceAll("[^a-z0-9]+", "-").
      stripPrefix("-").
      stripSuffix("-")

  private[cozy] def sampleArchiveTreeLines(warehousedir: File, root: File, files: Seq[File]): Seq[String] = {
    val rootlabel = _warehouse_relative_path(warehousedir, root)
    val paths = files.map(file => _relative_path(root, file).split('/').toVector.filter(_.nonEmpty))
    rootlabel +: _render_tree(_tree_nodes(paths), "")
  }

  private case class TreeNode(name: String, children: Vector[TreeNode])

  private def _tree_nodes(paths: Seq[Vector[String]]): Vector[TreeNode] =
    paths.filter(_.nonEmpty).groupBy(_.head).toVector.sortBy(_._1).map { case (name, group) =>
      TreeNode(name, _tree_nodes(group.map(_.tail)))
    }

  private def _render_tree(nodes: Vector[TreeNode], prefix: String): Vector[String] =
    nodes.zipWithIndex.flatMap { case (node, index) =>
      val last = index == nodes.length - 1
      val connector = if (last) "+-- " else "|-- "
      val childprefix = prefix + (if (last) "    " else "|   ")
      (prefix + connector + node.name) +: _render_tree(node.children, childprefix)
    }

  private def _warehouse_relative_path(warehousedir: File, file: File): String =
    _relative_path(warehousedir, file)

  private def _relative_path(base: File, file: File): String =
    base.toPath.toAbsolutePath.normalize().relativize(file.toPath.toAbsolutePath.normalize()).toString.replace('\\', '/')

  private def _repository_artifact_destination(root: File, kind: String, version: String, archive: File): File = {
    val module = _repository_artifact_module(archive, kind, version)
    _repository_artifact_destination(root, kind, module, version)
  }

  private def _repository_artifact_destination(root: File, kind: String, module: String, version: String): File =
    root / "repository" / kind / module / version / s"$module-$version.$kind"

  private def _repository_artifact_module(archive: File, kind: String, version: String): String = {
    val suffix = s".$kind"
    val base0 = archive.getName.stripSuffix(suffix)
    val versionSuffix = s"-$version"
    if (base0.endsWith(versionSuffix)) base0.substring(0, base0.length - versionSuffix.length) else base0
  }

  private def _default_maven_coordinate(
    organization: String,
    moduleName: String,
    crossPaths: Boolean,
    scalaBinaryVersion: String,
    isSbtPlugin: Boolean,
    sbtBinaryVersion: String
  ): String = {
    val artifact =
      if (isSbtPlugin) s"${moduleName}_${scalaBinaryVersion}_${sbtBinaryVersion}"
      else if (crossPaths) s"${moduleName}_${scalaBinaryVersion}"
      else moduleName
    s"$organization:$artifact"
  }

  private def _config_file(base: File, value: Option[String]): Option[File] =
    value.map { x =>
      val f =
        if (x == "~")
          file(sys.props.getOrElse("user.home", ".")).getAbsoluteFile
        else if (x.startsWith("~/"))
          file(sys.props.getOrElse("user.home", ".")).getAbsoluteFile / x.drop(2)
        else
          file(x)
      if (f.isAbsolute) f else base / f.getPath
    }

  private def _parse_validated_model(cozyfiles: Seq[File]): CozyModel = {
    val model = CozyParser.parseAll(cozyfiles) match {
      case Right(value) => value
      case Left(error)  => sys.error(error.render)
    }

    CozyModelValidator.validate(model) match {
      case Right(_) =>
      case Left(error) => sys.error(error.render)
    }

    model
  }
}

final case class CozyConfig(
  generateDerivedAggregates: Boolean = true,
  generateDerivedViews: Boolean = true,
  packagePrefix: Option[String] = None
) {
  def applyPackagePrefix(basePackage: String): String = packagePrefix match {
    case Some(prefix) => s"${prefix}.${basePackage}"
    case None => basePackage
  }
}

object CozyConfig {
  val default: CozyConfig = CozyConfig()
}

private[cozy] object CozyConfigValidator {
  def validate(config: CozyConfig): Either[String, Unit] = {
    config.packagePrefix match {
      case Some(prefix) if !_is_valid_package_name(prefix) =>
        Left(s"invalid packagePrefix '${prefix}'")
      case _ =>
        Right(())
    }
  }

  private def _is_valid_package_name(name: String): Boolean = {
    val SegmentPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r
    name.nonEmpty &&
    name.split("\\.", -1).toVector.forall(segment => SegmentPattern.pattern.matcher(segment).matches())
  }
}

private[cozy] object CozyFileLoader {
  private val _accepted_extensions = Set(".cml", ".cozy", ".dox")
  private val _accepted_sar_descriptor_names = Set(
    "descriptor.json",
    "descriptor.yaml",
    "descriptor.yml",
    "descriptor.conf",
    "descriptor.hocon",
    "descriptor.xml",
    "subsystem-descriptor.json",
    "subsystem-descriptor.yaml",
    "subsystem-descriptor.yml",
    "subsystem-descriptor.conf",
    "subsystem-descriptor.hocon",
    "subsystem-descriptor.xml"
  )

  def load(sourcedir: File): Seq[File] = {
    if (!sourcedir.exists()) {
      Seq.empty
    } else {
      (sourcedir ** "*").get
        .filter(_.isFile)
        .filter(file => _accepted_extensions.exists(file.getName.endsWith))
        .sortBy(_.getAbsolutePath)
    }
  }

  def loadSarSources(sourcedir: File): Seq[File] = {
    if (!sourcedir.exists()) {
      Seq.empty
    } else {
      (sourcedir ** "*").get
        .filter(_.isFile)
        .filter(file =>
          _accepted_extensions.exists(file.getName.endsWith) ||
            _accepted_sar_descriptor_names.contains(file.getName)
        )
        .sortBy(_.getAbsolutePath)
    }
  }
}

private[cozy] object CozyWebDescriptorSync {
  private final case class OperationForm(
    service: String,
    operation: String,
    operationType: String,
    input: Option[String],
    settings: Map[String, String]
  ) {
    def selector(componentname: String): String =
      Vector(componentname, service, operation).map(_normalize_segment).mkString(".")

    def enabled: Boolean =
      !settings.get("form").exists(_.equalsIgnoreCase("false"))

    def explicitFormSelection: Boolean =
      settings.get("form").exists(_.equalsIgnoreCase("true"))

    def access(defaultaccess: Option[String]): Option[String] =
      settings.get("access").orElse(defaultaccess)
  }

  private final case class InputModel(attributes: Vector[Attribute])
  private final case class Attribute(name: String, datatype: String, multiplicity: String, settings: Map[String, String]) {
    def required: Boolean =
      multiplicity.trim.toLowerCase(java.util.Locale.ROOT) match {
        case "1" | "one" => true
        case _ => false
      }

    def controlType: String =
      settings.get("control").getOrElse {
        datatype.trim.toLowerCase(java.util.Locale.ROOT) match {
          case "uri" | "url" => "url"
          case _ => "text"
        }
      }
  }

  private final case class Model(
    defaultFormAccess: Option[String],
    defaultStayOnError: Option[String],
    operations: Vector[OperationForm],
    inputs: Map[String, InputModel]
  )

  def sync(
    projectdir: File,
    componentname: String,
    cozyfiles: Seq[File],
    log: Logger
  ): Unit = {
    val model = _parse(cozyfiles)
    val descriptor = projectdir / "src" / "main" / "web-inf" / "form.yaml"
    val current = if (descriptor.isFile) IO.read(descriptor) else ""
    val existingselectors = _form_block_selectors(current)
    val enabled = model.operations.filter { op =>
      op.enabled && (op.explicitFormSelection || !existingselectors.contains(op.selector(componentname)))
    }
    val generated = enabled.map(_operation_block(componentname, _, model)).toMap
    if (generated.nonEmpty || model.defaultFormAccess.nonEmpty || model.defaultStayOnError.nonEmpty) {
      val updated = merge(current, model, generated)
      if (updated != current) {
        IO.createDirectory(descriptor.getParentFile)
        IO.write(descriptor, updated)
        log.info(s"[sbt-cozy] synchronized ${generated.size} Web form descriptor entr${if (generated.size == 1) "y" else "ies"} from CML")
      }
    }
  }

  private def _parse(files: Seq[File]): Model = {
    val all = files.toVector.flatMap(file => IO.readLines(file).zipWithIndex.map { case (line, index) => (file, index + 1, line) })
    var top = ""
    var service: Option[String] = None
    var inoperations = false
    var currentoperation: Option[String] = None
    var currentoperationsettings = Map.empty[String, String]
    var currentoperationtype = ""
    var currentoperationinput: Option[String] = None
    var serviceform = false
    var defaultformaccess: Option[String] = None
    var defaultstayonerror: Option[String] = None
    var webconcerntarget: Option[String] = None
    var operations = Vector.empty[OperationForm]

    var inputname: Option[String] = None
    var inattributetable = false
    var currentattribute: Option[String] = None
    var attributes = Vector.empty[Attribute]
    var attributesettings = Map.empty[String, Map[String, String]]
    var inputs = Map.empty[String, InputModel]

    def _finish_operation_(): Unit = {
      for {
        svc <- service
        op <- currentoperation
      } {
        val selected =
          currentoperationsettings.get("form").exists(_.equalsIgnoreCase("true")) ||
            (serviceform && !currentoperationsettings.get("form").exists(_.equalsIgnoreCase("false")))
        if (selected)
          operations :+= OperationForm(svc, op, currentoperationtype, currentoperationinput, currentoperationsettings)
      }
      currentoperation = None
      currentoperationsettings = Map.empty
      currentoperationtype = ""
      currentoperationinput = None
    }

    def _finish_input_(): Unit = {
      inputname.foreach { name =>
        inputs += name -> InputModel(attributes.map { attr =>
          attr.copy(settings = attributesettings.getOrElse(attr.name, Map.empty))
        })
      }
      inputname = None
      attributes = Vector.empty
      attributesettings = Map.empty
      currentattribute = None
      inattributetable = false
    }

    all.foreach { case (_, _, raw) =>
      val line = raw.trim
      if (line.matches("#\\s+WEB\\s*")) {
        _finish_operation_()
        _finish_input_()
        top = "WEB"
        webconcerntarget = Some("top")
      } else if (line.startsWith("# ") && !line.matches("#\\s+WEB\\s*")) {
        _finish_operation_()
        _finish_input_()
        top = line.drop(1).trim
        service = None
        inoperations = false
        webconcerntarget = None
      } else if (top == "WEB") {
        _yaml_pair(line).foreach {
          case ("form.access", value) => defaultformaccess = Some(value)
          case ("form.stayOnError", value) => defaultstayonerror = Some(value)
          case ("access", value) => defaultformaccess = Some(value)
          case ("stayOnError", value) => defaultstayonerror = Some(value)
          case ("stay-on-error", value) => defaultstayonerror = Some(value)
          case _ =>
        }
      } else if (line.startsWith("## ") && top == "SERVICE") {
        _finish_operation_()
        _finish_input_()
        service = Some(line.drop(3).trim)
        serviceform = false
        inoperations = false
        webconcerntarget = None
      } else if (line.startsWith("## ") && top != "SERVICE") {
        _finish_operation_()
        _finish_input_()
        inputname = Some(line.drop(3).trim)
        webconcerntarget = None
      } else if (line.matches("###\\s+OPERATION\\s*")) {
        inoperations = true
        webconcerntarget = None
      } else if (line.matches("###\\s+Attribute\\s*")) {
        inattributetable = true
        currentattribute = None
        webconcerntarget = None
      } else if (line.startsWith("#### ") && top == "SERVICE" && inoperations) {
        _finish_operation_()
        currentoperation = Some(line.drop(5).trim)
        webconcerntarget = None
      } else if (line.startsWith("#### ") && inputname.nonEmpty) {
        currentattribute = Some(line.drop(5).trim)
        webconcerntarget = None
      } else if (line.matches("#####\\s+WEB\\s*")) {
        webconcerntarget =
          if (currentoperation.nonEmpty) Some("operation")
          else if (currentattribute.nonEmpty) Some("attribute")
          else if (service.nonEmpty) Some("service")
          else Some("top")
      } else if (line.startsWith("- ")) {
        _bullet_pair(line).foreach { case (key, value) =>
          val normalized = _normalize_key(key)
          if (normalized.startsWith("web.")) {
            val webkey = normalized.stripPrefix("web.")
            if (currentoperation.nonEmpty)
              currentoperationsettings += webkey -> value
            else if (currentattribute.nonEmpty)
              currentattribute.foreach { attr =>
                val current = attributesettings.getOrElse(attr, Map.empty)
                attributesettings += attr -> (current + (webkey -> value))
              }
            else if (service.nonEmpty && webkey == "form")
              serviceform = value.equalsIgnoreCase("true")
            else {
              webkey match {
                case "form.access" | "access" => defaultformaccess = Some(value)
                case "form.stayOnError" | "form.stay-on-error" | "stayOnError" | "stay-on-error" => defaultstayonerror = Some(value)
                case _ =>
              }
            }
          } else {
            webconcerntarget match {
              case Some("operation") => currentoperationsettings += normalized -> value
              case Some("attribute") => currentattribute.foreach { attr =>
                val current = attributesettings.getOrElse(attr, Map.empty)
                attributesettings += attr -> (current + (normalized -> value))
              }
              case Some("service") if normalized == "form" => serviceform = value.equalsIgnoreCase("true")
              case _ =>
                normalized match {
                  case "type" if currentoperation.nonEmpty => currentoperationtype = value
                  case "input" if currentoperation.nonEmpty => currentoperationinput = Some(value)
                  case _ =>
                }
            }
          }
        }
      } else if (inattributetable && line.startsWith("|") && line.endsWith("|") && !line.contains("---")) {
        val cells = line.stripPrefix("|").stripSuffix("|").split("\\|").toVector.map(_.trim)
        if (cells.size >= 3 && cells.head != "name")
          attributes :+= Attribute(cells(0), cells(1), cells(2), Map.empty)
      }
    }
    _finish_operation_()
    _finish_input_()
    Model(defaultformaccess, defaultstayonerror, operations, inputs)
  }

  def merge(
    current: String,
    model: Model,
    generated: Map[String, String]
  ): String = {
    val withoutgenerated = _remove_generated_blocks(current, generated.keySet)
    val base = if (withoutgenerated.trim.isEmpty) "form:\n" else withoutgenerated.stripSuffix("\n") + "\n"
    val withdefault = _sync_default(base, model)
    val withform = if (withdefault.linesIterator.exists(_.trim == "form:")) withdefault else withdefault + "form:\n"
    val generatedtext = generated.toVector.sortBy(_._1).map(_._2.stripSuffix("\n")).mkString("\n")
    (withform.stripSuffix("\n") + "\n" + generatedtext).stripSuffix("\n") + "\n"
  }

  private def _operation_block(componentname: String, op: OperationForm, model: Model): (String, String) = {
    val selector = op.selector(componentname)
    val controls = op.input.flatMap(model.inputs.get).toVector.flatMap(_.attributes)
    val stay = op.settings.get("stayOnError").orElse(op.settings.get("stay-on-error")).orElse(model.defaultStayOnError)
    val lines =
      Vector(s"  $selector:", "    # generated by sbt-cozy from CML WEB metadata") ++
        op.access(model.defaultFormAccess).map(value => s"    access: ${_normalize_access(value)}").toVector ++
        op.settings.get("successRedirect").orElse(op.settings.get("success-redirect")).map(value => s"    successRedirect: $value").toVector ++
        stay.map(value => s"    stayOnError: $value").toVector ++
        (if (controls.nonEmpty)
          Vector("    controls:") ++ controls.flatMap { attr =>
            Vector(
              s"      ${attr.name}:",
              s"        type: ${attr.controlType}",
              s"        required: ${attr.settings.get("required").getOrElse(attr.required.toString)}"
            ) ++ attr.settings.get("readonly").map(value => s"        readonly: $value").toVector
          }
        else Vector.empty)
    selector -> (lines.mkString("\n") + "\n")
  }

  private def _remove_generated_blocks(current: String, selectors: Set[String]): String = {
    if (selectors.isEmpty)
      current
    else {
      val lines = current.linesIterator.toVector
      val out = scala.collection.mutable.ArrayBuffer.empty[String]
      var i = 0
      while (i < lines.size) {
        val trimmed = lines(i).trim
        if (lines(i).startsWith("  ") && !lines(i).startsWith("    ") && _form_block_selector(trimmed).exists(selectors.contains)) {
          i += 1
          while (i < lines.size && (lines(i).startsWith("    ") || lines(i).trim.isEmpty))
            i += 1
        } else {
          out += lines(i)
          i += 1
        }
      }
      out.mkString("\n")
    }
  }

  private def _sync_default(current: String, model: Model): String = {
    if (model.defaultFormAccess.isEmpty && model.defaultStayOnError.isEmpty)
      current
    else {
      val lines = current.stripSuffix("\n").linesIterator.toVector
      val withoutdefault = _remove_default_block(lines).mkString("\n")
      val body = _default_block(model)
      if (withoutdefault.trim.isEmpty)
        body + "\n"
      else
        body + "\n" + withoutdefault.stripPrefix("\n").stripSuffix("\n") + "\n"
    }
  }

  private def _default_block(model: Model): String = {
    val lines = Vector("default:", "  form:") ++
      model.defaultFormAccess.map(value => s"    access: ${_normalize_access(value)}").toVector ++
      model.defaultStayOnError.map(value => s"    stayOnError: $value").toVector
    lines.mkString("\n")
  }

  private def _remove_default_block(lines: Vector[String]): Vector[String] = {
    val start = lines.indexWhere(_.trim == "default:")
    if (start < 0)
      lines
    else {
      val end = (start + 1 until lines.size).find { i =>
        val line = lines(i)
        line.nonEmpty && !line.startsWith(" ") && !line.startsWith("\t")
      }.getOrElse(lines.size)
      lines.take(start) ++ lines.drop(end)
    }
  }

  private def _form_block_selector(trimmed: String): Option[String] = {
    val i = trimmed.indexOf(':')
    if (i > 0)
      Some(trimmed.take(i))
    else
      None
  }

  private def _form_block_selectors(text: String): Set[String] =
    text.linesIterator.collect {
      case line if line.startsWith("  ") && !line.startsWith("    ") =>
        _form_block_selector(line.trim)
    }.flatten.toSet

  private def _bullet_pair(line: String): Option[(String, String)] = {
    val body = line.stripPrefix("- ").trim
    val i = body.indexOf("::")
    if (i >= 0) Some(body.take(i).trim -> body.drop(i + 2).trim) else None
  }

  private def _yaml_pair(line: String): Option[(String, String)] = {
    val i = line.indexOf(':')
    if (i >= 0) {
      val key = line.take(i).trim
      val value = line.drop(i + 1).trim
      if (key.nonEmpty && value.nonEmpty) Some(key -> value) else None
    } else None
  }

  private def _normalize_key(value: String): String =
    value.trim.replace("_", "-")

  private def _normalize_segment(value: String): String = {
    val s = value.trim
    s.zipWithIndex.flatMap { case (c, i) =>
      if (c.isUpper && i > 0) Vector('-', c.toLower)
      else Vector(c.toLower)
    }.mkString.replace("_", "-")
  }

  private def _normalize_access(value: String): String =
    value.trim.toLowerCase(java.util.Locale.ROOT) match {
      case "public" => "anonymous"
      case "protected" => "authenticated"
      case x => x
    }
}

private[cozy] object CozyGenerationState {
  private val _state_version = "2"
  private val _input_prefix = "input."
  private val _setting_prefix = "setting."

  final case class Inputs(
    backend: String,
    packagePrefix: String,
    generateDerivedAggregates: Boolean,
    generateDerivedViews: Boolean,
    settings: Vector[(String, String)],
    files: Vector[(String, Long)]
  )

  def capture(sourcedir: File, cozyfiles: Seq[File], backend: String, config: CozyConfig, settings: Map[String, String] = Map.empty): Inputs = {
    val files = cozyfiles.map { path =>
      CozyPackaging.relativepath(sourcedir, path).replace('\\', '/') -> path.lastModified()
    }.toVector.sortBy(_._1)
    Inputs(
      backend = backend,
      packagePrefix = config.packagePrefix.getOrElse(""),
      generateDerivedAggregates = config.generateDerivedAggregates,
      generateDerivedViews = config.generateDerivedViews,
      settings = settings.toVector.sortBy(_._1),
      files = files
    )
  }

  def currentoutputs(targetdir: File): Seq[File] = {
    val legacygenerated = CozyGenerator.generatedFiles(targetdir)
    val delegatedgenerated = CozyDelegatedGenerator.generatedFiles(targetdir)
    (legacygenerated ++ delegatedgenerated)
      .groupBy(_.getAbsolutePath)
      .values
      .map(_.head)
      .toVector
      .sortBy(_.getAbsolutePath)
  }

  def isUpToDate(statefile: File, currentinputs: Inputs, currentoutputs: Seq[File]): Boolean = {
    _read(statefile).contains(currentinputs) &&
    currentoutputs.nonEmpty &&
    currentoutputs.forall(_.isFile)
  }

  def write(statefile: File, inputs: Inputs): Unit = {
    val properties = new java.util.Properties()
    properties.setProperty("version", _state_version)
    properties.setProperty("backend", inputs.backend)
    properties.setProperty("packagePrefix", inputs.packagePrefix)
    properties.setProperty("generateDerivedAggregates", inputs.generateDerivedAggregates.toString)
    properties.setProperty("generateDerivedViews", inputs.generateDerivedViews.toString)
    properties.setProperty("setting.count", inputs.settings.size.toString)
    inputs.settings.zipWithIndex.foreach {
      case ((key, value), index) =>
        properties.setProperty(s"${_setting_prefix}${index}.key", key)
        properties.setProperty(s"${_setting_prefix}${index}.value", value)
    }
    properties.setProperty("input.count", inputs.files.size.toString)
    inputs.files.zipWithIndex.foreach {
      case ((path, timestamp), index) =>
        properties.setProperty(s"${_input_prefix}${index}.path", path)
        properties.setProperty(s"${_input_prefix}${index}.timestamp", timestamp.toString)
    }

    IO.createDirectory(statefile.getParentFile)
    val out = new java.io.FileOutputStream(statefile)
    try properties.store(out, "Generated by sbt-cozy")
    finally out.close()
  }

  private def _read(statefile: File): Option[Inputs] = {
    if (!statefile.isFile) {
      None
    } else {
      val properties = new java.util.Properties()
      val in = new java.io.FileInputStream(statefile)
      try properties.load(in)
      finally in.close()

      if (properties.getProperty("version") != _state_version) {
        None
      } else {
        val settingcount = Option(properties.getProperty("setting.count")).flatMap(_parse_int).getOrElse(0)
        val settings = Vector.tabulate(settingcount) { index =>
          val key = properties.getProperty(s"${_setting_prefix}${index}.key")
          val value = properties.getProperty(s"${_setting_prefix}${index}.value")
          key -> value
        }
        val inputcount = Option(properties.getProperty("input.count")).flatMap(_parse_int).getOrElse(0)
        val files = Vector.tabulate(inputcount) { index =>
          val path = properties.getProperty(s"${_input_prefix}${index}.path")
          val timestamp = properties.getProperty(s"${_input_prefix}${index}.timestamp")
          path -> timestamp.toLong
        }
        Some(
          Inputs(
            backend = properties.getProperty("backend", ""),
            packagePrefix = properties.getProperty("packagePrefix", ""),
            generateDerivedAggregates = properties.getProperty("generateDerivedAggregates", "true").toBoolean,
            generateDerivedViews = properties.getProperty("generateDerivedViews", "true").toBoolean,
            settings = settings,
            files = files
          )
        )
      }
    }
  }

  private def _parse_int(value: String): Option[Int] =
    try Some(value.toInt)
    catch {
      case _: NumberFormatException => None
    }
}

private[cozy] final case class SourceLocation(file: File, line: Int) {
  def render: String = s"${file.getPath}:${line}"
}

private[cozy] sealed trait CozyDefinition {
  def name: String
  def location: SourceLocation
}

private[cozy] final case class EntityDef(name: String, location: SourceLocation) extends CozyDefinition
private[cozy] final case class AggregateDef(name: String, location: SourceLocation) extends CozyDefinition
private[cozy] final case class ViewDef(name: String, location: SourceLocation) extends CozyDefinition
private[cozy] final case class CommandDef(name: String, location: SourceLocation) extends CozyDefinition
private[cozy] final case class QueryDef(name: String, location: SourceLocation) extends CozyDefinition
private[cozy] final case class EventDef(name: String, location: SourceLocation) extends CozyDefinition
private[cozy] final case class OperationDef(
  name: String,
  linkedKind: Option[String],
  linkedName: Option[String],
  location: SourceLocation
) extends CozyDefinition

private[cozy] final case class CozyModel(
  packageName: String,
  entities: Vector[EntityDef],
  aggregates: Vector[AggregateDef],
  views: Vector[ViewDef],
  commands: Vector[CommandDef],
  queries: Vector[QueryDef],
  events: Vector[EventDef],
  operations: Vector[OperationDef]
)

private[cozy] final case class CozyError(location: SourceLocation, message: String) {
  def render: String = s"[sbt-cozy] ${location.render}: ${message}"
}

private[cozy] object CozyParser {
  private val _default_package_name = "cozy.generated"

  private sealed trait Statement {
    def location: SourceLocation
  }

  private final case class PackageStmt(name: String, location: SourceLocation) extends Statement
  private final case class EntityStmt(name: String, location: SourceLocation) extends Statement
  private final case class AggregateStmt(name: String, location: SourceLocation) extends Statement
  private final case class ViewStmt(name: String, location: SourceLocation) extends Statement
  private final case class CommandStmt(name: String, location: SourceLocation) extends Statement
  private final case class QueryStmt(name: String, location: SourceLocation) extends Statement
  private final case class EventStmt(name: String, location: SourceLocation) extends Statement
  private final case class OperationStmt(
    name: String,
    kind: Option[String],
    target: Option[String],
    location: SourceLocation
  ) extends Statement

  def parseAll(files: Seq[File]): Either[CozyError, CozyModel] = {
    var packageDecls = Vector.empty[PackageStmt]
    var entities = Vector.empty[EntityDef]
    var aggregates = Vector.empty[AggregateDef]
    var views = Vector.empty[ViewDef]
    var commands = Vector.empty[CommandDef]
    var queries = Vector.empty[QueryDef]
    var events = Vector.empty[EventDef]
    var operations = Vector.empty[OperationDef]

    files.foreach { file =>
      val lines = IO.readLines(file)
      lines.zipWithIndex.foreach {
        case (rawLine, idx) =>
          val location = SourceLocation(file, idx + 1)
          _parse_line(rawLine, location) match {
            case Right(None) =>
            case Right(Some(stmt)) =>
              stmt match {
                case s: PackageStmt => packageDecls :+= s
                case s: EntityStmt => entities :+= EntityDef(s.name, s.location)
                case s: AggregateStmt => aggregates :+= AggregateDef(s.name, s.location)
                case s: ViewStmt => views :+= ViewDef(s.name, s.location)
                case s: CommandStmt => commands :+= CommandDef(s.name, s.location)
                case s: QueryStmt => queries :+= QueryDef(s.name, s.location)
                case s: EventStmt => events :+= EventDef(s.name, s.location)
                case s: OperationStmt =>
                  operations :+= OperationDef(s.name, s.kind, s.target, s.location)
              }
            case Left(error) => return Left(error)
          }
      }
    }

    val packageName = packageDecls.map(_.name).distinct match {
      case Vector() => _default_package_name
      case Vector(single) => single
      case many =>
        val detail = many.mkString(", ")
        return Left(CozyError(packageDecls.head.location, s"multiple package declarations found: ${detail}"))
    }

    Right(
      CozyModel(
        packageName = packageName,
        entities = entities,
        aggregates = aggregates,
        views = views,
        commands = commands,
        queries = queries,
        events = events,
        operations = operations
      )
    )
  }

  private def _parse_line(raw: String, location: SourceLocation): Either[CozyError, Option[Statement]] = {
    val line = raw.trim
    if (line.isEmpty || line.startsWith("#") || line.startsWith("//")) {
      Right(None)
    } else {
      val tokens = line.split("\\s+").toList
      tokens match {
        case "package" :: pkg :: Nil =>
          _parse_package_name(pkg, location).map(name => Some(PackageStmt(name, location)))

        case "entity" :: name :: Nil =>
          _parse_identifier(name, location).map(n => Some(EntityStmt(n, location)))

        case "aggregate" :: name :: Nil =>
          _parse_identifier(name, location).map(n => Some(AggregateStmt(n, location)))

        case "view" :: name :: Nil =>
          _parse_identifier(name, location).map(n => Some(ViewStmt(n, location)))

        case "command" :: name :: Nil =>
          _parse_identifier(name, location).map(n => Some(CommandStmt(n, location)))

        case "query" :: name :: Nil =>
          _parse_identifier(name, location).map(n => Some(QueryStmt(n, location)))

        case "event" :: name :: Nil =>
          _parse_identifier(name, location).map(n => Some(EventStmt(n, location)))

        case "operation" :: opName :: Nil =>
          _parse_identifier(opName, location).map(n => Some(OperationStmt(n, None, None, location)))

        case "operation" :: opName :: kind :: target :: Nil =>
          _parse_operation(opName, kind, target, location).map(Some(_))

        case "operation" :: opName :: "uses" :: kind :: target :: Nil =>
          _parse_operation(opName, kind, target, location).map(Some(_))

        case _ =>
          Left(
            CozyError(
              location,
              s"unrecognized syntax '${line}'. expected: package/entity/aggregate/view/command/query/event/operation"
            )
          )
      }
    }
  }

  private def _parse_operation(
    opName: String,
    kind: String,
    target: String,
    location: SourceLocation
  ): Either[CozyError, OperationStmt] = {
    for {
      parsedName <- _parse_identifier(opName, location)
      parsedTarget <- _parse_identifier(target, location)
      parsedKind <- kind match {
        case "command" | "query" => Right(kind)
        case other => Left(CozyError(location, s"operation link kind must be command or query, but was '${other}'"))
      }
    } yield OperationStmt(parsedName, Some(parsedKind), Some(parsedTarget), location)
  }

  private def _parse_package_name(name: String, location: SourceLocation): Either[CozyError, String] = {
    val SegmentPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r
    val valid = name.nonEmpty && name.split("\\.", -1).toVector.forall(segment => SegmentPattern.pattern.matcher(segment).matches())
    if (valid) {
      Right(name)
    } else {
      Left(CozyError(location, s"invalid package name '${name}'"))
    }
  }

  private def _parse_identifier(name: String, location: SourceLocation): Either[CozyError, String] = {
    val NamePattern = "[A-Za-z_][A-Za-z0-9_]*".r
    if (NamePattern.pattern.matcher(name).matches()) {
      Right(name)
    } else {
      Left(CozyError(location, s"invalid identifier '${name}'"))
    }
  }
}

private[cozy] object CozyModelValidator {
  def validate(model: CozyModel): Either[CozyError, Unit] = {
    val duplicateCheck =
      _check_duplicates("entity", model.entities)
        .orElse(_check_duplicates("aggregate", model.aggregates))
        .orElse(_check_duplicates("view", model.views))
        .orElse(_check_duplicates("command", model.commands))
        .orElse(_check_duplicates("query", model.queries))
        .orElse(_check_duplicates("event", model.events))
        .orElse(_check_duplicates("operation", model.operations))

    duplicateCheck match {
      case Some(error) => Left(error)
      case None => _validate_operation_links(model)
    }
  }

  private def _check_duplicates(kind: String, definitions: Seq[CozyDefinition]): Option[CozyError] = {
    definitions
      .groupBy(_.name)
      .collectFirst {
        case (name, occurrences) if occurrences.size > 1 =>
          CozyError(occurrences.head.location, s"duplicate ${kind} definition '${name}'")
      }
  }

  private def _validate_operation_links(model: CozyModel): Either[CozyError, Unit] = {
    val commands = model.commands.iterator.map(_.name).toSet
    val queries = model.queries.iterator.map(_.name).toSet

    model.operations.collectFirst {
      case op if op.linkedKind.contains("command") && op.linkedName.exists(name => !commands.contains(name)) =>
        CozyError(op.location, s"operation '${op.name}' references unknown command '${op.linkedName.get}'")
      case op if op.linkedKind.contains("query") && op.linkedName.exists(name => !queries.contains(name)) =>
        CozyError(op.location, s"operation '${op.name}' references unknown query '${op.linkedName.get}'")
    } match {
      case Some(error) => Left(error)
      case None => Right(())
    }
  }
}

private[cozy] object CozyGenerator {
  private val _ownership_marker = "Generated by sbt-cozy"
  private val _sections = Seq("entity", "aggregate", "view", "command", "query", "operation")

  def generatedFiles(targetdir: File): Seq[File] =
    _sections.flatMap(section => ((targetdir / section) ** "*.scala").get)
      .filter(_is_owned)
      .sortBy(_.getAbsolutePath)

  def generate(model: CozyModel, targetdir: File, config: CozyConfig): Seq[File] = {
    val basePackage = config.applyPackagePrefix(model.packageName)

    val entityFiles = model.entities.sortBy(_.name).map { entity =>
      _render_class(
        targetdir = targetdir,
        section = "entity",
        fileName = s"${entity.name}.scala",
        packageName = basePackage,
        body = s"final case class ${entity.name}(id: String)"
      )
    }

    val aggregateNames = if (config.generateDerivedAggregates) {
      val explicit = model.aggregates.map(_.name)
      val derived = model.entities.map(entity => s"${entity.name}Aggregate")
      (explicit ++ derived).distinct.sorted
    } else {
      model.aggregates.map(_.name).distinct.sorted
    }

    val aggregateFiles = aggregateNames.map { name =>
      _render_class(
        targetdir = targetdir,
        section = "aggregate",
        fileName = s"${name}.scala",
        packageName = basePackage,
        body = s"final case class ${name}(id: String)"
      )
    }

    val viewNames = if (config.generateDerivedViews) {
      val explicit = model.views.map(_.name)
      val derived = model.entities.map(entity => s"${entity.name}View")
      (explicit ++ derived).distinct.sorted
    } else {
      model.views.map(_.name).distinct.sorted
    }

    val viewFiles = viewNames.map { name =>
      _render_class(
        targetdir = targetdir,
        section = "view",
        fileName = s"${name}.scala",
        packageName = basePackage,
        body = s"final case class ${name}(id: String)"
      )
    }

    val commandFiles = model.commands.sortBy(_.name).map { command =>
      _render_class(
        targetdir = targetdir,
        section = "command",
        fileName = s"${command.name}.scala",
        packageName = basePackage,
        body = s"final case class ${command.name}(payload: String)"
      )
    }

    val queryFiles = model.queries.sortBy(_.name).map { query =>
      _render_class(
        targetdir = targetdir,
        section = "query",
        fileName = s"${query.name}.scala",
        packageName = basePackage,
        body = s"final case class ${query.name}(criteria: String)"
      )
    }

    val operationFiles = model.operations.sortBy(_.name).map { operation =>
      val body =
        s"""final case class ${operation.name}(input: String)
object ${operation.name} {
  val linkedKind: Option[String] = ${_format_option(operation.linkedKind)}
  val linkedName: Option[String] = ${_format_option(operation.linkedName)}
}"""

      _render_class(
        targetdir = targetdir,
        section = "operation",
        fileName = s"${operation.name}.scala",
        packageName = basePackage,
        body = body
      )
    }

    val generated = (entityFiles ++ aggregateFiles ++ viewFiles ++ commandFiles ++ queryFiles ++ operationFiles).toVector

    _delete_owned_files(targetdir)
    generated.foreach {
      case (path, content) =>
        IO.createDirectory(path.getParentFile)
        IO.write(path, content)
    }

    generated.map(_._1)
  }

  private def _render_class(
    targetdir: File,
    section: String,
    fileName: String,
    packageName: String,
    body: String
  ): (File, String) = {
    val output = targetdir / section / fileName
    val content =
      s"""// ${_ownership_marker}. DO NOT EDIT.
package ${packageName}.${section}

${body}
"""

    (output, content)
  }

  private def _delete_owned_files(targetdir: File): Unit = {
    IO.delete(generatedFiles(targetdir))
  }

  private def _is_owned(path: File): Boolean = {
    path.exists() && path.isFile && IO.readLines(path).headOption.exists(_.contains(_ownership_marker))
  }

  private def _format_option(value: Option[String]): String = value match {
    case Some(v) => s"""Some("${v}")"""
    case None => "None"
  }
}

private[cozy] object CozyDelegatedGenerator {
  private val _manifest_relative_path = "sbt-cozy/generated-files.txt"
  private val _source_marker = "/src_managed/main/scala/"

  def generatedFiles(targetdir: File): Seq[File] =
    (targetdir ** "*.scala").get.filter(_.isFile).sortBy(_.getAbsolutePath)

  def generate(
    sourcedir: File,
    cozyfiles: Seq[File],
    targetdir: File,
    targetbasedir: File,
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    settings: Map[String, String],
    log: Logger
  ): Seq[File] = {
    val workdir = targetbasedir / "sbt-cozy" / "delegate-work"
    val manifestfile = targetbasedir / _manifest_relative_path

    _cleanup_previous_generated_files(manifestfile)
    IO.delete(workdir)
    IO.createDirectory(workdir)
    IO.createDirectory(targetdir)

    val generated = cozyfiles.zipWithIndex.flatMap {
      case (source, index) =>
        _generate_from_one_source(
          source = source,
          runIndex = index,
          targetdir = targetdir,
          workdir = workdir,
          basedir = basedir,
          delegateprojectdir = delegateprojectdir,
          delegatecommand = delegatecommand,
          settings = settings,
          log = log
        )
    }

    val uniquegenerated = generated
      .groupBy(_.getAbsolutePath)
      .values
      .map(_.last)
      .toVector
      .sortBy(_.getAbsolutePath)

    _install_component_api_descriptor(workdir, targetbasedir)

    _write_generated_manifest(manifestfile, uniquegenerated)

    if (uniquegenerated.isEmpty) {
      sys.error(s"[sbt-cozy] cozy backend produced no Scala sources from ${sourcedir.getAbsolutePath}")
    }

    log.info(s"[sbt-cozy] generated ${uniquegenerated.size} Scala source(s) using cozy backend")
    uniquegenerated
  }

  private[cozy] def _install_component_api_descriptor(workdir: File, targetbasedir: File): Unit = {
    val descriptors = (workdir ** "component-api-descriptor.json").get.filter(_.isFile).sortBy(_.getAbsolutePath)
    descriptors match {
      case Seq() =>
        val targetdescriptor = targetbasedir / "cozy" / "component-api-descriptor.json"
        if (targetdescriptor.isFile)
          IO.delete(targetdescriptor)
      case Seq(descriptor) =>
        val targetdescriptor = targetbasedir / "cozy" / "component-api-descriptor.json"
        IO.createDirectory(targetdescriptor.getParentFile)
        IO.copyFile(descriptor, targetdescriptor, preserveLastModified = true)
      case _ =>
        sys.error(s"[sbt-cozy] multiple component API descriptors were generated: ${descriptors.map(_.getAbsolutePath).mkString(", ")}")
    }
  }

  private def _generate_from_one_source(
    source: File,
    runIndex: Int,
    targetdir: File,
    workdir: File,
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    settings: Map[String, String],
    log: Logger
  ): Seq[File] = {
    val savedir = workdir / s"run-${runIndex}"
    IO.createDirectory(savedir)
    val delegate = CozySbtBridge.resolveGenerate(
      basedir = basedir,
      explicitProjectDir = delegateprojectdir,
      delegatecommand = delegatecommand,
      source = source,
      savedir = savedir,
      settings = settings
    )
    val command = delegate.command

    log.info(s"[sbt-cozy] delegate to cozy: ${source.getAbsolutePath}")
    val outlines = scala.collection.mutable.ArrayBuffer.empty[String]
    val errlines = scala.collection.mutable.ArrayBuffer.empty[String]
    val exit = Process(command, delegate.cwd).!(ProcessLogger(
      out => {
        outlines += out
        log.debug(s"[sbt-cozy/cozy] $out")
      },
      err => {
        errlines += err
        log.warn(s"[sbt-cozy/cozy] $err")
      }
    ))
    if (exit != 0) {
      val details = (outlines ++ errlines).takeRight(40).mkString("\n")
      val message =
        s"""[sbt-cozy] cozy delegate failed (${exit}) for ${source.getAbsolutePath}
           |[sbt-cozy] command: ${command.mkString(" ")}
           |[sbt-cozy] cwd: ${delegate.cwd.getAbsolutePath}
           |[sbt-cozy] recent logs:
           |${details}""".stripMargin
      sys.error(message)
    }

    val generatedsources = (savedir ** "*.scala").get
      .filter(_.isFile)
      .filter(path => path.getAbsolutePath.replace('\\', '/').contains(_source_marker))
      .sortBy(_.getAbsolutePath)

    generatedsources.map { generated =>
      val relative = _relative_from_generated_root(generated)
      val destination = targetdir / _normalize_path(relative)
      IO.createDirectory(destination.getParentFile)
      IO.write(destination, IO.read(generated))
      destination
    }
  }

  private def _cleanup_previous_generated_files(manifestfile: File): Unit = {
    if (manifestfile.isFile) {
      IO.readLines(manifestfile)
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(file)
        .foreach { path =>
          if (path.exists()) {
            IO.delete(path)
          }
        }
      IO.delete(manifestfile)
    }
  }

  private def _write_generated_manifest(manifestfile: File, generated: Seq[File]): Unit = {
    IO.createDirectory(manifestfile.getParentFile)
    val content = generated.map(_.getAbsolutePath).mkString("", "\n", "\n")
    IO.write(manifestfile, content)
  }

  private def _relative_from_generated_root(file: File): String = {
    val absolute = file.getAbsolutePath
    val normalized = absolute.replace('\\', '/')
    val index = normalized.indexOf(_source_marker)
    if (index < 0) {
      file.getName
    } else {
      normalized.substring(index + _source_marker.length)
    }
  }

  private def _normalize_path(path: String): String =
    path.replace('\\', '/').stripPrefix("/")
}

final case class CozyPackageMetadata(
  component: String,
  extensions: Map[String, String],
  config: Map[String, String]
)

private[cozy] object CozyManifestMetadata {
  private val _component_key = "component"
  private val _componentlets_key = "componentlets"
  private val _componentlet_prefix = "componentlet."
  private val _descriptor_json_key = "componentDescriptorJson"

  def from(metadata: Map[String, String], defaultcomponent: String, version: String): CozyPackageMetadata = {
    val component = metadata.getOrElse(_component_key, defaultcomponent)
    val componentletnames = _componentlet_names(metadata)
    val reservedkeys = Set(_component_key, _componentlets_key, _descriptor_json_key) ++
      metadata.keySet.filter(_.startsWith(_componentlet_prefix))
    val passthroughextensions = metadata -- reservedkeys
    val descriptorjson = _descriptor_json(defaultcomponent, component, version, passthroughextensions, componentletnames, metadata)
    CozyPackageMetadata(
      component = component,
      extensions = passthroughextensions + (_descriptor_json_key -> descriptorjson),
      config = Map.empty
    )
  }

  private def _componentlet_names(metadata: Map[String, String]): Vector[String] = {
    val fromlist = metadata
      .get(_componentlets_key)
      .toVector
      .flatMap(_.split(",").toVector)
      .map(_.trim)
      .filter(_.nonEmpty)
    val fromkeys = metadata.keysIterator
      .filter(_.startsWith(_componentlet_prefix))
      .flatMap { key =>
        key.stripPrefix(_componentlet_prefix).split("\\.", 2).headOption
      }
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
    (fromlist ++ fromkeys).distinct.sorted.toVector
  }

  private def _descriptor_json(
    carname: String,
    component: String,
    version: String,
    rootmetadata: Map[String, String],
    componentletnames: Vector[String],
    metadata: Map[String, String]
  ): String = {
    val componentlets = componentletnames.map { name =>
      val prefix = s"${_componentlet_prefix}$name."
      val fields = metadata.collect {
        case (key, value) if key.startsWith(prefix) => key.stripPrefix(prefix) -> value
      }.toVector.sortBy(_._1)
      name -> fields
    }
    val rootfields = rootmetadata.toVector.filterNot { case (key, _) =>
      key == "name" || key == "version" || key == "component"
    }.sortBy(_._1)
    val rootjson = _json_fields(Vector(
      ("name", carname),
      ("version", version),
      ("component", component)
    ) ++ rootfields)
    val componentletsjson = componentlets.map { case (name, fields) =>
      _json_fields(Vector(("name", name)) ++ fields)
    }.mkString("[", ",", "]")
    s"""${rootjson.dropRight(1)},"componentlets":$componentletsjson}"""
  }

  private def _json_fields(fields: Vector[(String, String)]): String =
    fields.map { case (key, value) =>
      s"${_json_string(key)}:${_json_string(value)}"
    }.mkString("{", ",", "}")

  private def _json_string(value: String): String = {
    val builder = new StringBuilder
    builder.append('"')
    value.foreach {
      case '"' => builder.append("\\\"")
      case '\\' => builder.append("\\\\")
      case '\b' => builder.append("\\b")
      case '\f' => builder.append("\\f")
      case '\n' => builder.append("\\n")
      case '\r' => builder.append("\\r")
      case '\t' => builder.append("\\t")
      case c if c < ' ' => builder.append(f"\\u${c.toInt}%04x")
      case c => builder.append(c)
    }
    builder.append('"')
    builder.result()
  }
}

private[cozy] object CozySbtBridge {
  private val _development_project_command = Seq(
    "sbt",
    "--batch",
    "-Dsbt.server.autostart=false",
    "-Dsbt.supershell=false"
  )

  private val _coursier_repositories = Seq(
    "--repository", "central",
    "--repository", "https://www.simplemodeling.org/repository/maven",
    "--repository", "https://raw.github.com/asami/maven-repository/2020/releases",
    "--repository", "https://raw.github.com/asami/maven-repository/2025/releases",
    "--repository", "https://maven.pkg.github.com/asami/maven-repository"
  )

  private val _coursier_channels = Seq(
    "--channel", "https://www.simplemodeling.org/repository/cozy/coursier-channel.json"
  )

  private[cozy] def coursierCommand(version: String): Seq[String] = {
    val launcher = sys.env.getOrElse("SBT_COZY_COURSIER_COMMAND", "cs")
    Seq(launcher, "launch") ++ _coursier_channels ++ _coursier_repositories ++
      Seq("cozy", "--", "--runtime", version)
  }

  def resolveGenerate(
    basedir: File,
    explicitProjectDir: Option[File],
    delegatecommand: Seq[String],
    source: File,
    savedir: File,
    settings: Map[String, String] = Map.empty
  ): DelegateExecution = {
    val request = _request(
      action = "generate",
      arguments = Vector(
        "modeler-scala",
        source.getAbsolutePath,
        "--save",
        savedir.getAbsolutePath
      )
    )
    _resolve(basedir, explicitProjectDir, delegatecommand, request.copy(settings = _project_settings(basedir, settings)))
  }

  def packageCar(
    archive: File,
    mainjar: File,
    libjars: Seq[File],
    spijars: Seq[File],
    componentapidescriptor: Option[File],
    projectdir: File,
    name: String,
    version: String,
    component: String,
    extensions: Map[String, String],
    config: Map[String, String],
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "package-car",
        arguments =
          Vector(
            "--save",
            archive.getAbsolutePath,
            "--main-jar",
            mainjar.getAbsolutePath,
            "--name",
            name,
            "--version",
            version,
            "--component",
            component
          ) ++
            _csv_arg("lib-jars", libjars.map(_.getAbsolutePath)) ++
            _csv_arg("spi-jars", spijars.map(_.getAbsolutePath)) ++
            componentapidescriptor.toVector.flatMap(file => Vector("--component-api-descriptor", file.getAbsolutePath)) ++
            Vector("--project-dir", projectdir.getAbsolutePath) ++
            _map_arg("extensions", extensions) ++
            _map_arg("config", config)
      ),
      basedir,
      delegateprojectdir,
      delegatecommand,
      log
    )
  }

  def buildComponentApiJar(
    output: File,
    mainjar: File,
    descriptor: File,
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "component-api-jar",
        arguments = Vector(
          "--save", output.getAbsolutePath,
          "--main-jar", mainjar.getAbsolutePath,
          "--descriptor", descriptor.getAbsolutePath
        )
      ),
      basedir,
      delegateprojectdir,
      delegatecommand,
      log
    )
  }

  def packageSar(
    archive: File,
    sourcedir: File,
    sourceFiles: Seq[String],
    extensionJars: Seq[File],
    applicationConf: Option[File],
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "package-sar",
        arguments =
          Vector(
            "--save",
            archive.getAbsolutePath,
            "--source-dir",
            sourcedir.getAbsolutePath
          ) ++
            _csv_arg("source-files", sourceFiles) ++
            _csv_arg("extension-jars", extensionJars.map(_.getAbsolutePath)) ++
            applicationConf.toVector.flatMap(f => Vector("--application-conf", f.getAbsolutePath))
      ),
      basedir,
      delegateprojectdir,
      delegatecommand,
      log
    )
  }

  def publishCar(
    projectdir: File,
    warehousedir: File,
    name: String,
    version: String,
    archive: File,
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "publish-car",
        arguments =
          Vector(
            projectdir.getAbsolutePath,
            "--warehouse",
            warehousedir.getAbsolutePath,
            "--name",
            name,
            "--version",
            version,
            "--car",
            archive.getAbsolutePath
          )
      ),
      basedir,
      delegateprojectdir,
      delegatecommand,
      log
    )
  }

  def publishSar(
    projectdir: File,
    warehousedir: File,
    name: String,
    version: String,
    archive: File,
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "publish-sar",
        arguments =
          Vector(
            projectdir.getAbsolutePath,
            "--warehouse",
            warehousedir.getAbsolutePath,
            "--name",
            name,
            "--version",
            version,
            "--sar",
            archive.getAbsolutePath
          )
      ),
      basedir,
      delegateprojectdir,
      delegatecommand,
      log
    )
  }

  def distributeSamples(
    projectDir: File,
    warehouseDir: File,
    name: String,
    publicationPath: Option[String],
    version: String,
    samplesDir: Option[File],
    dryRun: Boolean,
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "distribute-samples",
        arguments =
          Vector(
            projectDir.getAbsolutePath,
            "--warehouse",
            warehouseDir.getAbsolutePath,
            "--name",
            name,
            "--version",
            version
          ) ++
            publicationPath.toVector.flatMap(x => Vector("--path", x)) ++
            samplesDir.toVector.flatMap(f => Vector("--samples-dir", f.getAbsolutePath)) ++
            (if (dryRun) Vector("--dry-run") else Vector.empty)
      ),
      basedir,
      delegateprojectdir,
      delegatecommand,
      log
    )
  }

  def publishProject(
    projectDir: File,
    savedir: File,
    kind: Option[String],
    name: String,
    title: Option[String],
    publicationPath: Option[String],
    organization: String,
    version: String,
    scalaVersion: String,
    sbtVersion: String,
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "publish-project",
        arguments =
          Vector(
            projectDir.getAbsolutePath,
            "--save",
            savedir.getAbsolutePath,
            "--name",
            name,
            "--organization",
            organization,
            "--version",
            version,
            "--scala-version",
            scalaVersion,
            "--sbt-version",
            sbtVersion
          ) ++
            kind.toVector.flatMap(x => Vector("--kind", x)) ++
            title.toVector.flatMap(x => Vector("--title", x)) ++
            publicationPath.toVector.flatMap(x => Vector("--path", x))
      ),
      basedir,
      delegateprojectdir,
      delegatecommand,
      log
    )
  }

  def indexWarehouse(
    warehouseDir: File,
    savedir: File,
    name: String,
    title: Option[String],
    mavenCoordinates: Seq[String],
    repositoryArtifacts: Seq[String],
    repositoryModules: Seq[String],
    downloadSamples: Seq[String],
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "index-warehouse",
        arguments =
          Vector(
            warehouseDir.getAbsolutePath,
            "--save",
            savedir.getAbsolutePath,
            "--name",
            name
          ) ++
            title.toVector.flatMap(x => Vector("--title", x)) ++
            _csv_arg("maven-coordinates", mavenCoordinates) ++
            _csv_arg("repository-artifacts", repositoryArtifacts) ++
            _csv_arg("repository-modules", repositoryModules) ++
            _csv_arg("download-samples", downloadSamples)
      ),
      basedir,
      delegateprojectdir,
      delegatecommand,
      log
    )
  }

  private def _run(
    request: BridgeRequest,
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    log: Logger
  ): Unit = {
    val execution = _resolve(basedir, delegateprojectdir, delegatecommand, request)
    val outlines = scala.collection.mutable.ArrayBuffer.empty[String]
    val errlines = scala.collection.mutable.ArrayBuffer.empty[String]
    val exit = Process(execution.command, execution.cwd).!(ProcessLogger(
      out => {
        outlines += out
        log.debug(s"[sbt-cozy/cozy] $out")
      },
      err => {
        errlines += err
        log.warn(s"[sbt-cozy/cozy] $err")
      }
    ))
    if (exit != 0) {
      val details = (outlines ++ errlines).takeRight(40).mkString("\n")
      sys.error(
        s"""[sbt-cozy] cozy packaging delegate failed (${exit})
           |[sbt-cozy] command: ${execution.command.mkString(" ")}
           |[sbt-cozy] cwd: ${execution.cwd.getAbsolutePath}
           |[sbt-cozy] recent logs:
           |${details}""".stripMargin
      )
    }
  }

  private[cozy] final case class DelegateExecution(cwd: File, command: Seq[String])
  private case class BridgeRequest(action: String, arguments: Vector[String], settings: Map[String, String] = Map.empty)

  private[cozy] def renderRequestJsonForTest(action: String, arguments: Vector[String], settings: Map[String, String] = Map.empty): String =
    _render_request_json(BridgeRequest(action, arguments, settings))

  private[cozy] def resolveForTest(
    basedir: File,
    delegateprojectdir: Option[File],
    delegatecommand: Seq[String],
    action: String,
    arguments: Vector[String]
  ): (File, Seq[String]) = {
    val execution = _resolve(basedir, delegateprojectdir, delegatecommand, _request(action, arguments))
    execution.cwd -> execution.command
  }

  private def _resolve(
    basedir: File,
    explicitProjectDir: Option[File],
    delegatecommand: Seq[String],
    request: BridgeRequest
  ): DelegateExecution = {
    val requestFile = _write_request_file(request)
    explicitProjectDir match {
      case Some(cozyDir) =>
        if (!cozyDir.isDirectory || !(cozyDir / "build.sbt").isFile)
          sys.error(s"[sbt-cozy] cozyDelegateProjectDir is not a valid cozy project: ${cozyDir.getAbsolutePath}")
        val runMainArgs = Vector(
          "sbt-bridge",
          "v1",
          "--request",
          requestFile.getAbsolutePath
        ).map(_quote).mkString(" ")
        DelegateExecution(cozyDir, _development_project_command :+ s"runMain cozy.Cozy $runMainArgs")
      case None =>
        val commandprefix = if (delegatecommand.nonEmpty) delegatecommand else Seq("cozy")
        DelegateExecution(basedir, commandprefix ++ Seq("sbt-bridge", "v1", "--request", requestFile.getAbsolutePath))
    }
  }

  private def _quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def _request(action: String, arguments: Vector[String]): BridgeRequest =
    BridgeRequest(action, arguments)

  private def _project_settings(basedir: File, settings: Map[String, String]): Map[String, String] =
    settings + ("sbt.project_dir" -> basedir.getAbsoluteFile.toPath.normalize.toString)

  private def _write_request_file(request: BridgeRequest): File = {
    val path = Files.createTempFile("sbt-cozy-bridge-", ".json")
    Files.write(path, _render_request_json(request).getBytes(StandardCharsets.UTF_8))
    path.toFile.getAbsoluteFile
  }

  private def _render_request_json(request: BridgeRequest): String =
    s"""{
       |  "version": "v1",
       |  "action": ${_json(request.action)},
       |  "arguments": ${request.arguments.map(_json).mkString("[", ", ", "]")},
       |  "settings": ${_json_map(request.settings)}
       |}
       |""".stripMargin

  private def _json(s: String): String =
    "\"" + s.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c => c.toString
    } + "\""

  private def _json_map(values: Map[String, String]): String =
    values.toVector.sortBy(_._1).map { case (k, v) => s"${_json(k)}: ${_json(v)}" }.mkString("{", ", ", "}")

  private def _csv_arg(name: String, values: Seq[String]): Vector[String] =
    if (values.isEmpty) Vector.empty else Vector(s"--${name}", values.mkString(","))

  private def _map_arg(name: String, values: Map[String, String]): Vector[String] =
    if (values.isEmpty) Vector.empty
    else Vector(s"--${name}", _json_map(values))
}

private[cozy] object CozyPackaging {
  def collectDocs(docsDir: File): Seq[(File, String)] = {
    if (!docsDir.exists()) {
      Seq.empty
    } else {
      (docsDir ** "*.md").get
        .filter(_.isFile)
        .sortBy(_.getAbsolutePath)
        .map(file => file -> relativepath(docsDir, file))
    }
  }

  def relativepath(basedir: File, file: File): String = {
    IO.relativize(basedir, file).getOrElse(file.getName).replace('\\', '/')
  }
}
