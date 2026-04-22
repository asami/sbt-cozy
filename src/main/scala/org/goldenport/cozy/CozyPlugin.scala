package org.goldenport.cozy

import java.time.Instant
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import sbt._
import sbt.Keys._
import scala.sys.process._

/*
 * @since   Mar. 22, 2026
 *  version Mar. 23, 2026
 *  version Mar. 25, 2026
 *  version Apr.  1, 2026
 *  version Apr.  4, 2026
 * @version Apr. 23, 2026
 * @author  ASAMI, Tomoharu
 */
object CozyPlugin extends AutoPlugin {
  object autoImport {
    val cozyConfig = settingKey[CozyConfig]("Configuration for sbt-cozy code generation")
    val cozySourceDir = settingKey[File]("Directory containing CML/cozy sources")
    val cozyTargetDir = settingKey[File]("Directory where Scala sources are generated")
    val cozyGeneratorBackend = settingKey[String]("Generator backend. Either 'cozy' or 'legacy'.")
    val cozyDelegateProjectDir = settingKey[Option[File]]("Optional path to cozy project used by delegated generation during development.")
    val cozyDelegateCommand = settingKey[Seq[String]]("Command prefix used to execute delegated cozy generation and packaging.")
    val cozyCncfVersion = settingKey[String]("CNCF version propagated to delegated cozy generation and generated CAR project build files.")
    val cozySimpleModelingModelVersion = settingKey[String]("simplemodeling-model version propagated to delegated cozy generation and generated CAR project build files.")
    val cozyCncfCollaboratorApiVersion = settingKey[String]("cncf-collaborator-api version propagated to delegated cozy generation and generated CAR project build files.")
    val cozySkipUnchangedGeneration = settingKey[Boolean]("Skip code generation when CML timestamps and generator settings are unchanged.")
    val cozyGenerate = taskKey[Seq[File]]("Generate Scala sources from CML/cozy definitions")
    val cozyRuntimeClasspathFile = taskKey[File]("Write runtime classpath file for direct Java execution.")
    val cozyPrepareRuntime = taskKey[File]("Compile sample outputs and prepare runtime classpath file.")

    val cozyPackaging = settingKey[String]("Default packaging target. Either 'car' or 'sar'.")
    val cozyCarName = settingKey[String]("Base file name of the generated CAR archive")
    val cozySarName = settingKey[String]("Base file name of the generated SAR archive")
    val cozySpiJars = settingKey[Seq[File]]("Additional SPI jars to include under CAR /spi")
    val cozySarExtensionJars = settingKey[Seq[File]]("Injected extension jars to include under SAR /extension")
    val cozyManifestMetadata = settingKey[Map[String, String]]("Additional metadata fields written to manifest.json")
    val cozyLocalRepositoryDir = settingKey[File]("Local destination directory for cozyPublishCAR/cozyPublishSAR")

    val cozyBuildCAR = taskKey[File]("Build CAR archive from compiled outputs")
    val cozyBuildSAR = taskKey[File]("Build SAR archive from cozy source definitions")
    val cozyPublishCAR = taskKey[File]("Copy CAR archive to cozy local repository")
    val cozyPublishSAR = taskKey[File]("Copy SAR archive to cozy local repository")
    val cozyAppName = settingKey[String]("Application name used by cozyScaffoldApp.")
    val cozyAppRootDir = settingKey[File]("Target directory where cozyScaffoldApp writes the application scaffold.")
    val cozyScaffoldApp = taskKey[Seq[File]]("Generate an application root scaffold with component/ and subsystem/ modules.")
  }

  import autoImport._

  override def requires: Plugins = plugins.JvmPlugin
  override def trigger: PluginTrigger = noTrigger

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    cozyConfig := CozyConfig.default,
    cozySourceDir := (Compile / sourceDirectory).value / "cozy",
    cozyTargetDir := (Compile / sourceManaged).value,
    cozyGeneratorBackend := sys.env.getOrElse("SBT_COZY_GENERATOR_BACKEND", "cozy"),
    cozyDelegateProjectDir := sys.env.get("SBT_COZY_PROJECT_DIR").map(file),
    cozyDelegateCommand := Seq("cozy"),
    cozyCncfVersion := _dependencyVersion((Compile / libraryDependencies).value, "org.goldenport", "goldenport-cncf").getOrElse("0.4.2-SNAPSHOT"),
    cozySimpleModelingModelVersion := _dependencyVersion((Compile / libraryDependencies).value, "org.simplemodeling", "simplemodeling-model").getOrElse("0.1.2-SNAPSHOT"),
    cozyCncfCollaboratorApiVersion := _dependencyVersion((Compile / libraryDependencies).value, "org.goldenport", "cncf-collaborator-api").getOrElse("0.1.0-SNAPSHOT"),
    cozySkipUnchangedGeneration := true,

    cozyPackaging := "car",
    cozyCarName := s"${moduleName.value}-${version.value}",
    cozySarName := s"${moduleName.value}-${version.value}",
    cozySpiJars := Seq.empty,
    cozySarExtensionJars := Seq.empty,
    cozyManifestMetadata := Map.empty,
    cozyLocalRepositoryDir := target.value / "cozy-repository",
    cozyAppName := moduleName.value,
    cozyAppRootDir := baseDirectory.value / cozyAppName.value,

    cozyGenerate := {
      val sourceDir = cozySourceDir.value
      val targetDir = cozyTargetDir.value
      val config = cozyConfig.value
      val backend = cozyGeneratorBackend.value.trim.toLowerCase
      val delegateProjectDir = cozyDelegateProjectDir.value
      val delegateCommand = cozyDelegateCommand.value
      val dependencyVersions = CozyDependencyVersions(
        cozyCncfVersion.value,
        cozySimpleModelingModelVersion.value,
        cozyCncfCollaboratorApiVersion.value
      )
      val skipUnchanged = cozySkipUnchangedGeneration.value
      val log = streams.value.log

      CozyConfigValidator.validate(config) match {
        case Right(_) =>
        case Left(message) => sys.error(s"[sbt-cozy] ${message}")
      }

      val cozyFiles = CozyFileLoader.load(sourceDir)
      if (cozyFiles.isEmpty) {
        log.debug(s"[sbt-cozy] no cozy sources found under ${sourceDir.getAbsolutePath}")
        Seq.empty
      } else {
        val stateFile = target.value / "sbt-cozy" / "generation-state.properties"
        val currentInputs = CozyGenerationState.capture(sourceDir, cozyFiles, backend, config)
        val currentOutputs = CozyGenerationState.currentOutputs(targetDir)

        if (skipUnchanged && CozyGenerationState.isUpToDate(stateFile, currentInputs, currentOutputs)) {
          log.info(s"[sbt-cozy] skipped generation; CML timestamps unchanged (${currentOutputs.size} source(s) reused)")
          currentOutputs
        } else {
        backend match {
          case "cozy" =>
            if (config != CozyConfig.default) {
              log.warn("[sbt-cozy] cozy backend ignores cozyConfig options; using cozy modeler defaults")
            }
            val generated = CozyDelegatedGenerator.generate(
              sourceDir = sourceDir,
              cozyFiles = cozyFiles,
              targetDir = targetDir,
              targetBaseDir = target.value,
              baseDir = baseDirectory.value,
              delegateProjectDir = delegateProjectDir,
              delegateCommand = delegateCommand,
              dependencyVersions = dependencyVersions,
              log = log
            )
            CozyGenerationState.write(stateFile, currentInputs)
            generated
          case "legacy" =>
            val model = parseValidatedModel(cozyFiles)
            val generated = CozyGenerator.generate(model, targetDir, config)
            log.info(s"[sbt-cozy] generated ${generated.size} Scala source(s) using legacy backend")
            CozyGenerationState.write(stateFile, currentInputs)
            generated
          case other =>
            sys.error(s"[sbt-cozy] invalid cozyGeneratorBackend '${other}'. expected 'cozy' or 'legacy'")
        }
        }
      }
    },

    cozyBuildCAR := {
      val sourceDir = cozySourceDir.value
      val log = streams.value.log
      val archive = target.value / s"${cozyCarName.value}.car"

      val mainJar = (Compile / packageBin).value
      val classpathJars = (Compile / dependencyClasspath).value
        .map(_.data)
        .filter(file => file.isFile && file.getName.endsWith(".jar"))
      val libJars = classpathJars
        .filterNot(_.getAbsolutePath == mainJar.getAbsolutePath)
        .distinct
        .sortBy(_.getName)
      val spiJars = cozySpiJars.value
        .filter(file => file.isFile && file.getName.endsWith(".jar"))
        .distinct
        .sortBy(_.getName)
      val defaultConf = (Compile / resourceDirectory).value / "default.conf"
      val packagingMetadata = CozyManifestMetadata.from(cozyManifestMetadata.value, moduleName.value)
      CozySbtBridge.packageCar(
        archive = archive,
        mainJar = mainJar,
        libJars = libJars,
        spiJars = spiJars,
        defaultConf = if (defaultConf.exists()) Some(defaultConf) else None,
        docsDir = {
          val d = baseDirectory.value / "docs"
          if (d.exists()) Some(d) else None
        },
        name = cozyCarName.value,
        version = version.value,
        component = packagingMetadata.component,
        extensions = packagingMetadata.extensions,
        config = packagingMetadata.config,
        baseDir = baseDirectory.value,
        delegateProjectDir = cozyDelegateProjectDir.value,
        delegateCommand = cozyDelegateCommand.value,
        log = log
      )

      streams.value.log.info(s"[sbt-cozy] built CAR: ${archive.getAbsolutePath}")
      archive
    },

    cozyBuildSAR := {
      val sourceDir = cozySourceDir.value
      val sarSources = CozyFileLoader.loadSarSources(sourceDir)
      val log = streams.value.log
      if (sarSources.isEmpty) {
        sys.error(s"[sbt-cozy] no subsystem sources found under ${sourceDir.getAbsolutePath}; SAR requires descriptor or cozy definition sources")
      }
      val archive = target.value / s"${cozySarName.value}.sar"
      val subsystemSources = sarSources.map(file => file -> CozyPackaging.relativePath(sourceDir, file))
      val extensionJars = cozySarExtensionJars.value
        .filter(file => file.isFile && file.getName.endsWith(".jar"))
        .distinct
        .sortBy(_.getName)
      val applicationConf = (Compile / resourceDirectory).value / "application.conf"

      CozySbtBridge.packageSar(
        archive = archive,
        sourceDir = sourceDir,
        sourceFiles = subsystemSources.map(_._2),
        extensionJars = extensionJars,
        applicationConf = if (applicationConf.exists()) Some(applicationConf) else None,
        baseDir = baseDirectory.value,
        delegateProjectDir = cozyDelegateProjectDir.value,
        delegateCommand = cozyDelegateCommand.value,
        log = log
      )

      streams.value.log.info(s"[sbt-cozy] built SAR: ${archive.getAbsolutePath}")
      archive
    },

    cozyPublishCAR := {
      val archive = cozyBuildCAR.value
      val destination = cozyLocalRepositoryDir.value / "car" / archive.getName
      IO.createDirectory(destination.getParentFile)
      IO.copyFile(archive, destination, preserveLastModified = true)
      streams.value.log.info(s"[sbt-cozy] published CAR to ${destination.getAbsolutePath}")
      destination
    },

    cozyPublishSAR := {
      val archive = cozyBuildSAR.value
      val destination = cozyLocalRepositoryDir.value / "sar" / archive.getName
      IO.createDirectory(destination.getParentFile)
      IO.copyFile(archive, destination, preserveLastModified = true)
      streams.value.log.info(s"[sbt-cozy] published SAR to ${destination.getAbsolutePath}")
      destination
    },

    cozyScaffoldApp := {
      val generated = CozyAppScaffold.generate(
        CozyAppScaffold.Spec(
          appName = cozyAppName.value,
          rootDir = cozyAppRootDir.value,
          organization = organization.value,
          version = version.value,
          scalaVersion = scalaVersion.value,
          sbtVersion = appConfiguration.value.provider.id.version,
          pluginVersion = CozyAppScaffold.CurrentPluginVersion,
          cncfVersion = cozyCncfVersion.value,
          simpleModelingModelVersion = cozySimpleModelingModelVersion.value,
          cncfCollaboratorApiVersion = cozyCncfCollaboratorApiVersion.value
        )
      )
      streams.value.log.info(s"[sbt-cozy] scaffolded application root: ${cozyAppRootDir.value.getAbsolutePath}")
      generated
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

  private def _dependencyVersion(deps: Seq[ModuleID], org: String, moduleBaseName: String): Option[String] =
    deps.collectFirst {
      case m if m.organization == org && (m.name == moduleBaseName || m.name.startsWith(moduleBaseName + "_")) =>
        m.revision
    }

  private def parseValidatedModel(cozyFiles: Seq[File]): CozyModel = {
    val model = CozyParser.parseAll(cozyFiles) match {
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
      case Some(prefix) if !isValidPackageName(prefix) =>
        Left(s"invalid packagePrefix '${prefix}'")
      case _ =>
        Right(())
    }
  }

  private def isValidPackageName(name: String): Boolean = {
    val SegmentPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r
    name.nonEmpty &&
    name.split("\\.", -1).toVector.forall(segment => SegmentPattern.pattern.matcher(segment).matches())
  }
}

private[cozy] object CozyFileLoader {
  private val AcceptedExtensions = Set(".cml", ".cozy", ".dox")
  private val AcceptedSarDescriptorNames = Set(
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

  def load(sourceDir: File): Seq[File] = {
    if (!sourceDir.exists()) {
      Seq.empty
    } else {
      (sourceDir ** "*").get
        .filter(_.isFile)
        .filter(file => AcceptedExtensions.exists(file.getName.endsWith))
        .sortBy(_.getAbsolutePath)
    }
  }

  def loadSarSources(sourceDir: File): Seq[File] = {
    if (!sourceDir.exists()) {
      Seq.empty
    } else {
      (sourceDir ** "*").get
        .filter(_.isFile)
        .filter(file =>
          AcceptedExtensions.exists(file.getName.endsWith) ||
            AcceptedSarDescriptorNames.contains(file.getName)
        )
        .sortBy(_.getAbsolutePath)
    }
  }
}

private[cozy] object CozyGenerationState {
  private val StateVersion = "1"
  private val InputPrefix = "input."

  final case class Inputs(
    backend: String,
    packagePrefix: String,
    generateDerivedAggregates: Boolean,
    generateDerivedViews: Boolean,
    files: Vector[(String, Long)]
  )

  def capture(sourceDir: File, cozyFiles: Seq[File], backend: String, config: CozyConfig): Inputs = {
    val files = cozyFiles.map { path =>
      CozyPackaging.relativePath(sourceDir, path).replace('\\', '/') -> path.lastModified()
    }.toVector.sortBy(_._1)
    Inputs(
      backend = backend,
      packagePrefix = config.packagePrefix.getOrElse(""),
      generateDerivedAggregates = config.generateDerivedAggregates,
      generateDerivedViews = config.generateDerivedViews,
      files = files
    )
  }

  def currentOutputs(targetDir: File): Seq[File] = {
    val legacyGenerated = CozyGenerator.generatedFiles(targetDir)
    val delegatedGenerated = CozyDelegatedGenerator.generatedFiles(targetDir)
    (legacyGenerated ++ delegatedGenerated)
      .groupBy(_.getAbsolutePath)
      .values
      .map(_.head)
      .toVector
      .sortBy(_.getAbsolutePath)
  }

  def isUpToDate(stateFile: File, currentInputs: Inputs, currentOutputs: Seq[File]): Boolean = {
    read(stateFile).contains(currentInputs) &&
    currentOutputs.nonEmpty &&
    currentOutputs.forall(_.isFile)
  }

  def write(stateFile: File, inputs: Inputs): Unit = {
    val properties = new java.util.Properties()
    properties.setProperty("version", StateVersion)
    properties.setProperty("backend", inputs.backend)
    properties.setProperty("packagePrefix", inputs.packagePrefix)
    properties.setProperty("generateDerivedAggregates", inputs.generateDerivedAggregates.toString)
    properties.setProperty("generateDerivedViews", inputs.generateDerivedViews.toString)
    properties.setProperty("input.count", inputs.files.size.toString)
    inputs.files.zipWithIndex.foreach {
      case ((path, timestamp), index) =>
        properties.setProperty(s"${InputPrefix}${index}.path", path)
        properties.setProperty(s"${InputPrefix}${index}.timestamp", timestamp.toString)
    }

    IO.createDirectory(stateFile.getParentFile)
    val out = new java.io.FileOutputStream(stateFile)
    try properties.store(out, "Generated by sbt-cozy")
    finally out.close()
  }

  private def read(stateFile: File): Option[Inputs] = {
    if (!stateFile.isFile) {
      None
    } else {
      val properties = new java.util.Properties()
      val in = new java.io.FileInputStream(stateFile)
      try properties.load(in)
      finally in.close()

      if (properties.getProperty("version") != StateVersion) {
        None
      } else {
        val count = Option(properties.getProperty("input.count")).flatMap(parseInt).getOrElse(0)
        val files = Vector.tabulate(count) { index =>
          val path = properties.getProperty(s"${InputPrefix}${index}.path")
          val timestamp = properties.getProperty(s"${InputPrefix}${index}.timestamp")
          path -> timestamp.toLong
        }
        Some(
          Inputs(
            backend = properties.getProperty("backend", ""),
            packagePrefix = properties.getProperty("packagePrefix", ""),
            generateDerivedAggregates = properties.getProperty("generateDerivedAggregates", "true").toBoolean,
            generateDerivedViews = properties.getProperty("generateDerivedViews", "true").toBoolean,
            files = files
          )
        )
      }
    }
  }

  private def parseInt(value: String): Option[Int] =
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
  private val DefaultPackageName = "cozy.generated"

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
          parseLine(rawLine, location) match {
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
      case Vector() => DefaultPackageName
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

  private def parseLine(raw: String, location: SourceLocation): Either[CozyError, Option[Statement]] = {
    val line = raw.trim
    if (line.isEmpty || line.startsWith("#") || line.startsWith("//")) {
      Right(None)
    } else {
      val tokens = line.split("\\s+").toList
      tokens match {
        case "package" :: pkg :: Nil =>
          parsePackageName(pkg, location).map(name => Some(PackageStmt(name, location)))

        case "entity" :: name :: Nil =>
          parseIdentifier(name, location).map(n => Some(EntityStmt(n, location)))

        case "aggregate" :: name :: Nil =>
          parseIdentifier(name, location).map(n => Some(AggregateStmt(n, location)))

        case "view" :: name :: Nil =>
          parseIdentifier(name, location).map(n => Some(ViewStmt(n, location)))

        case "command" :: name :: Nil =>
          parseIdentifier(name, location).map(n => Some(CommandStmt(n, location)))

        case "query" :: name :: Nil =>
          parseIdentifier(name, location).map(n => Some(QueryStmt(n, location)))

        case "event" :: name :: Nil =>
          parseIdentifier(name, location).map(n => Some(EventStmt(n, location)))

        case "operation" :: opName :: Nil =>
          parseIdentifier(opName, location).map(n => Some(OperationStmt(n, None, None, location)))

        case "operation" :: opName :: kind :: target :: Nil =>
          parseOperation(opName, kind, target, location).map(Some(_))

        case "operation" :: opName :: "uses" :: kind :: target :: Nil =>
          parseOperation(opName, kind, target, location).map(Some(_))

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

  private def parseOperation(
    opName: String,
    kind: String,
    target: String,
    location: SourceLocation
  ): Either[CozyError, OperationStmt] = {
    for {
      parsedName <- parseIdentifier(opName, location)
      parsedTarget <- parseIdentifier(target, location)
      parsedKind <- kind match {
        case "command" | "query" => Right(kind)
        case other => Left(CozyError(location, s"operation link kind must be command or query, but was '${other}'"))
      }
    } yield OperationStmt(parsedName, Some(parsedKind), Some(parsedTarget), location)
  }

  private def parsePackageName(name: String, location: SourceLocation): Either[CozyError, String] = {
    val SegmentPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r
    val valid = name.nonEmpty && name.split("\\.", -1).toVector.forall(segment => SegmentPattern.pattern.matcher(segment).matches())
    if (valid) {
      Right(name)
    } else {
      Left(CozyError(location, s"invalid package name '${name}'"))
    }
  }

  private def parseIdentifier(name: String, location: SourceLocation): Either[CozyError, String] = {
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
      checkDuplicates("entity", model.entities)
        .orElse(checkDuplicates("aggregate", model.aggregates))
        .orElse(checkDuplicates("view", model.views))
        .orElse(checkDuplicates("command", model.commands))
        .orElse(checkDuplicates("query", model.queries))
        .orElse(checkDuplicates("event", model.events))
        .orElse(checkDuplicates("operation", model.operations))

    duplicateCheck match {
      case Some(error) => Left(error)
      case None => validateOperationLinks(model)
    }
  }

  private def checkDuplicates(kind: String, definitions: Seq[CozyDefinition]): Option[CozyError] = {
    definitions
      .groupBy(_.name)
      .collectFirst {
        case (name, occurrences) if occurrences.size > 1 =>
          CozyError(occurrences.head.location, s"duplicate ${kind} definition '${name}'")
      }
  }

  private def validateOperationLinks(model: CozyModel): Either[CozyError, Unit] = {
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
  private val OwnershipMarker = "Generated by sbt-cozy"
  private val Sections = Seq("entity", "aggregate", "view", "command", "query", "operation")

  def generatedFiles(targetDir: File): Seq[File] =
    Sections.flatMap(section => ((targetDir / section) ** "*.scala").get)
      .filter(isOwned)
      .sortBy(_.getAbsolutePath)

  def generate(model: CozyModel, targetDir: File, config: CozyConfig): Seq[File] = {
    val basePackage = config.applyPackagePrefix(model.packageName)

    val entityFiles = model.entities.sortBy(_.name).map { entity =>
      renderClass(
        targetDir = targetDir,
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
      renderClass(
        targetDir = targetDir,
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
      renderClass(
        targetDir = targetDir,
        section = "view",
        fileName = s"${name}.scala",
        packageName = basePackage,
        body = s"final case class ${name}(id: String)"
      )
    }

    val commandFiles = model.commands.sortBy(_.name).map { command =>
      renderClass(
        targetDir = targetDir,
        section = "command",
        fileName = s"${command.name}.scala",
        packageName = basePackage,
        body = s"final case class ${command.name}(payload: String)"
      )
    }

    val queryFiles = model.queries.sortBy(_.name).map { query =>
      renderClass(
        targetDir = targetDir,
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
  val linkedKind: Option[String] = ${formatOption(operation.linkedKind)}
  val linkedName: Option[String] = ${formatOption(operation.linkedName)}
}"""

      renderClass(
        targetDir = targetDir,
        section = "operation",
        fileName = s"${operation.name}.scala",
        packageName = basePackage,
        body = body
      )
    }

    val generated = (entityFiles ++ aggregateFiles ++ viewFiles ++ commandFiles ++ queryFiles ++ operationFiles).toVector

    deleteOwnedFiles(targetDir)
    generated.foreach {
      case (path, content) =>
        IO.createDirectory(path.getParentFile)
        IO.write(path, content)
    }

    generated.map(_._1)
  }

  private def renderClass(
    targetDir: File,
    section: String,
    fileName: String,
    packageName: String,
    body: String
  ): (File, String) = {
    val output = targetDir / section / fileName
    val content =
      s"""// ${OwnershipMarker}. DO NOT EDIT.
package ${packageName}.${section}

${body}
"""

    (output, content)
  }

  private def deleteOwnedFiles(targetDir: File): Unit = {
    IO.delete(generatedFiles(targetDir))
  }

  private def isOwned(path: File): Boolean = {
    path.exists() && path.isFile && IO.readLines(path).headOption.exists(_.contains(OwnershipMarker))
  }

  private def formatOption(value: Option[String]): String = value match {
    case Some(v) => s"""Some("${v}")"""
    case None => "None"
  }
}

private[cozy] final case class CozyDependencyVersions(
  cncfVersion: String,
  simpleModelingModelVersion: String,
  cncfCollaboratorApiVersion: String
) {
  def toSettings: Map[String, String] = Map(
    "cncfVersion" -> cncfVersion,
    "simpleModelingModelVersion" -> simpleModelingModelVersion,
    "cncfCollaboratorApiVersion" -> cncfCollaboratorApiVersion
  )
}

private[cozy] object CozyDelegatedGenerator {
  private val ManifestRelativePath = "sbt-cozy/generated-files.txt"
  private val SourceMarker = "/src_managed/main/scala/"

  def generatedFiles(targetDir: File): Seq[File] =
    (targetDir ** "*.scala").get.filter(_.isFile).sortBy(_.getAbsolutePath)

  def generate(
    sourceDir: File,
    cozyFiles: Seq[File],
    targetDir: File,
    targetBaseDir: File,
    baseDir: File,
    delegateProjectDir: Option[File],
    delegateCommand: Seq[String],
    dependencyVersions: CozyDependencyVersions,
    log: Logger
  ): Seq[File] = {
    val workDir = targetBaseDir / "sbt-cozy" / "delegate-work"
    val manifestFile = targetBaseDir / ManifestRelativePath

    cleanupPreviousGeneratedFiles(manifestFile)
    IO.delete(workDir)
    IO.createDirectory(workDir)
    IO.createDirectory(targetDir)

    val generated = cozyFiles.zipWithIndex.flatMap {
      case (source, index) =>
        generateFromOneSource(
          source = source,
          runIndex = index,
          targetDir = targetDir,
          workDir = workDir,
          baseDir = baseDir,
          delegateProjectDir = delegateProjectDir,
          delegateCommand = delegateCommand,
          dependencyVersions = dependencyVersions,
          log = log
        )
    }

    val uniqueGenerated = generated
      .groupBy(_.getAbsolutePath)
      .values
      .map(_.last)
      .toVector
      .sortBy(_.getAbsolutePath)

    writeGeneratedManifest(manifestFile, uniqueGenerated)

    if (uniqueGenerated.isEmpty) {
      sys.error(s"[sbt-cozy] cozy backend produced no Scala sources from ${sourceDir.getAbsolutePath}")
    }

    log.info(s"[sbt-cozy] generated ${uniqueGenerated.size} Scala source(s) using cozy backend")
    uniqueGenerated
  }

  private def generateFromOneSource(
    source: File,
    runIndex: Int,
    targetDir: File,
    workDir: File,
    baseDir: File,
    delegateProjectDir: Option[File],
    delegateCommand: Seq[String],
    dependencyVersions: CozyDependencyVersions,
    log: Logger
  ): Seq[File] = {
    val saveDir = workDir / s"run-${runIndex}"
    IO.createDirectory(saveDir)
    val delegate = CozySbtBridge.resolveGenerate(
      baseDir = baseDir,
      explicitProjectDir = delegateProjectDir,
      delegateCommand = delegateCommand,
      source = source,
      saveDir = saveDir,
      settings = dependencyVersions.toSettings
    )
    val command = delegate.command

    log.info(s"[sbt-cozy] delegate to cozy: ${source.getAbsolutePath}")
    val outLines = scala.collection.mutable.ArrayBuffer.empty[String]
    val errLines = scala.collection.mutable.ArrayBuffer.empty[String]
    val exit = Process(command, delegate.cwd).!(ProcessLogger(
      out => {
        outLines += out
        log.debug(s"[sbt-cozy/cozy] $out")
      },
      err => {
        errLines += err
        log.warn(s"[sbt-cozy/cozy] $err")
      }
    ))
    if (exit != 0) {
      val details = (outLines ++ errLines).takeRight(40).mkString("\n")
      val message =
        s"""[sbt-cozy] cozy delegate failed (${exit}) for ${source.getAbsolutePath}
           |[sbt-cozy] command: ${command.mkString(" ")}
           |[sbt-cozy] cwd: ${delegate.cwd.getAbsolutePath}
           |[sbt-cozy] recent logs:
           |${details}""".stripMargin
      sys.error(message)
    }

    val generatedSources = (saveDir ** "*.scala").get
      .filter(_.isFile)
      .filter(path => path.getAbsolutePath.replace('\\', '/').contains(SourceMarker))
      .sortBy(_.getAbsolutePath)

    generatedSources.map { generated =>
      val relative = relativeFromGeneratedRoot(generated)
      val destination = targetDir / normalizePath(relative)
      IO.createDirectory(destination.getParentFile)
      val content = injectCncfEntityImports(IO.read(generated), relative)
      IO.write(destination, content)
      destination
    }
  }

  private def injectCncfEntityImports(content: String, relativePath: String): String = {
    if (!relativePath.startsWith("org/simplemodeling/textus/useraccount/entity/"))
      content
    else if (content.contains("import org.goldenport.cncf.entity.*"))
      content
    else {
      val newline = if (content.contains("\r\n")) "\r\n" else "\n"
      val lines = content.split("\\r?\\n", -1)
      if (lines.headOption.exists(_.startsWith("package org.simplemodeling.textus.useraccount.entity")))
        (Vector(lines.head, "", "import org.goldenport.cncf.entity.*") ++ lines.drop(1)).mkString(newline)
      else
        content
    }
  }

  private def cleanupPreviousGeneratedFiles(manifestFile: File): Unit = {
    if (manifestFile.isFile) {
      IO.readLines(manifestFile)
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(file)
        .foreach { path =>
          if (path.exists()) {
            IO.delete(path)
          }
        }
      IO.delete(manifestFile)
    }
  }

  private def writeGeneratedManifest(manifestFile: File, generated: Seq[File]): Unit = {
    IO.createDirectory(manifestFile.getParentFile)
    val content = generated.map(_.getAbsolutePath).mkString("", "\n", "\n")
    IO.write(manifestFile, content)
  }

  private def relativeFromGeneratedRoot(file: File): String = {
    val absolute = file.getAbsolutePath
    val normalized = absolute.replace('\\', '/')
    val index = normalized.indexOf(SourceMarker)
    if (index < 0) {
      file.getName
    } else {
      normalized.substring(index + SourceMarker.length)
    }
  }

  private def normalizePath(path: String): String =
    path.replace('\\', '/').stripPrefix("/")
}

final case class CozyPackageMetadata(
  component: String,
  extensions: Map[String, String],
  config: Map[String, String]
)

private[cozy] object CozyManifestMetadata {
  private val ComponentKey = "component"
  private val ComponentletsKey = "componentlets"
  private val ComponentletPrefix = "componentlet."
  private val DescriptorJsonKey = "componentDescriptorJson"

  def from(metadata: Map[String, String], defaultComponent: String): CozyPackageMetadata = {
    val component = metadata.getOrElse(ComponentKey, defaultComponent)
    val componentletNames = _componentletNames(metadata)
    val reservedKeys = Set(ComponentKey, ComponentletsKey) ++
      metadata.keySet.filter(_.startsWith(ComponentletPrefix))
    val passthroughExtensions = metadata -- reservedKeys
    val descriptorJson = _descriptorJson(component, passthroughExtensions, componentletNames, metadata)
    CozyPackageMetadata(
      component = component,
      extensions = passthroughExtensions + (DescriptorJsonKey -> descriptorJson),
      config = Map.empty
    )
  }

  private def _componentletNames(metadata: Map[String, String]): Vector[String] = {
    val fromList = metadata
      .get(ComponentletsKey)
      .toVector
      .flatMap(_.split(",").toVector)
      .map(_.trim)
      .filter(_.nonEmpty)
    val fromKeys = metadata.keysIterator
      .filter(_.startsWith(ComponentletPrefix))
      .flatMap { key =>
        key.stripPrefix(ComponentletPrefix).split("\\.", 2).headOption
      }
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
    (fromList ++ fromKeys).distinct.sorted.toVector
  }

  private def _descriptorJson(
    component: String,
    rootMetadata: Map[String, String],
    componentletNames: Vector[String],
    metadata: Map[String, String]
  ): String = {
    val componentlets = componentletNames.map { name =>
      val prefix = s"$ComponentletPrefix$name."
      val fields = metadata.collect {
        case (key, value) if key.startsWith(prefix) => key.stripPrefix(prefix) -> value
      }.toVector.sortBy(_._1)
      name -> fields
    }
    val rootFields = rootMetadata.toVector.sortBy(_._1)
    val componentJson = _jsonFields(Vector(("name", component)) ++ rootFields)
    val componentletsJson = componentlets.map { case (name, fields) =>
      _jsonFields(Vector(("name", name)) ++ fields)
    }.mkString("[", ",", "]")
    s"""{"component":$componentJson,"componentlets":$componentletsJson}"""
  }

  private def _jsonFields(fields: Vector[(String, String)]): String =
    fields.map { case (key, value) =>
      s"${_jsonString(key)}:${_jsonString(value)}"
    }.mkString("{", ",", "}")

  private def _jsonString(value: String): String = {
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
  private val DevelopmentProjectCommand = Seq(
    "sbt",
    "--batch",
    "-Dsbt.server.autostart=false",
    "-Dsbt.supershell=false"
  )

  def resolveGenerate(
    baseDir: File,
    explicitProjectDir: Option[File],
    delegateCommand: Seq[String],
    source: File,
    saveDir: File,
    settings: Map[String, String] = Map.empty
  ): DelegateExecution = {
    val request = _request(
      action = "generate",
      arguments = Vector(
        "modeler-scala",
        source.getAbsolutePath,
        s"--save=${saveDir.getAbsolutePath}"
      )
    )
    _resolve(baseDir, explicitProjectDir, delegateCommand, request.copy(settings = settings))
  }

  def packageCar(
    archive: File,
    mainJar: File,
    libJars: Seq[File],
    spiJars: Seq[File],
    defaultConf: Option[File],
    docsDir: Option[File],
    name: String,
    version: String,
    component: String,
    extensions: Map[String, String],
    config: Map[String, String],
    baseDir: File,
    delegateProjectDir: Option[File],
    delegateCommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "package-car",
        arguments =
          Vector(
            s"--save=${archive.getAbsolutePath}",
            s"--main-jar=${mainJar.getAbsolutePath}",
            s"--name=$name",
            s"--version=$version",
            s"--component=$component"
          ) ++
            _csv_arg("lib-jars", libJars.map(_.getAbsolutePath)) ++
            _csv_arg("spi-jars", spiJars.map(_.getAbsolutePath)) ++
            defaultConf.toVector.map(f => s"--default-conf=${f.getAbsolutePath}") ++
            docsDir.toVector.map(f => s"--docs-dir=${f.getAbsolutePath}") ++
            _map_arg("extensions", extensions) ++
            _map_arg("config", config)
      ),
      baseDir,
      delegateProjectDir,
      delegateCommand,
      log
    )
  }

  def packageSar(
    archive: File,
    sourceDir: File,
    sourceFiles: Seq[String],
    extensionJars: Seq[File],
    applicationConf: Option[File],
    baseDir: File,
    delegateProjectDir: Option[File],
    delegateCommand: Seq[String],
    log: Logger
  ): Unit = {
    _run(
      _request(
        action = "package-sar",
        arguments =
          Vector(
            s"--save=${archive.getAbsolutePath}",
            s"--source-dir=${sourceDir.getAbsolutePath}"
          ) ++
            _csv_arg("source-files", sourceFiles) ++
            _csv_arg("extension-jars", extensionJars.map(_.getAbsolutePath)) ++
            applicationConf.toVector.map(f => s"--application-conf=${f.getAbsolutePath}")
      ),
      baseDir,
      delegateProjectDir,
      delegateCommand,
      log
    )
  }

  private def _run(
    request: BridgeRequest,
    baseDir: File,
    delegateProjectDir: Option[File],
    delegateCommand: Seq[String],
    log: Logger
  ): Unit = {
    val execution = _resolve(baseDir, delegateProjectDir, delegateCommand, request)
    val outLines = scala.collection.mutable.ArrayBuffer.empty[String]
    val errLines = scala.collection.mutable.ArrayBuffer.empty[String]
    val exit = Process(execution.command, execution.cwd).!(ProcessLogger(
      out => {
        outLines += out
        log.debug(s"[sbt-cozy/cozy] $out")
      },
      err => {
        errLines += err
        log.warn(s"[sbt-cozy/cozy] $err")
      }
    ))
    if (exit != 0) {
      val details = (outLines ++ errLines).takeRight(40).mkString("\n")
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
    baseDir: File,
    delegateProjectDir: Option[File],
    delegateCommand: Seq[String],
    action: String,
    arguments: Vector[String]
  ): (File, Seq[String]) = {
    val execution = _resolve(baseDir, delegateProjectDir, delegateCommand, _request(action, arguments))
    execution.cwd -> execution.command
  }

  private def _resolve(
    baseDir: File,
    explicitProjectDir: Option[File],
    delegateCommand: Seq[String],
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
          s"--request=${requestFile.getAbsolutePath}"
        ).map(_quote).mkString(" ")
        DelegateExecution(cozyDir, DevelopmentProjectCommand :+ s"runMain cozy.Cozy $runMainArgs")
      case None =>
        val commandPrefix = if (delegateCommand.nonEmpty) delegateCommand else Seq("cozy")
        DelegateExecution(baseDir, commandPrefix ++ Seq("sbt-bridge", "v1", s"--request=${requestFile.getAbsolutePath}"))
    }
  }

  private def _quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def _request(action: String, arguments: Vector[String]): BridgeRequest =
    BridgeRequest(action, arguments)

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
    if (values.isEmpty) Vector.empty else Vector(s"--${name}=${values.mkString(",")}")

  private def _map_arg(name: String, values: Map[String, String]): Vector[String] =
    if (values.isEmpty) Vector.empty
    else Vector(s"--${name}=${_json_map(values)}")
}

private[cozy] object CozyAppScaffold {
  val CurrentPluginVersion = "0.1.4-SNAPSHOT"

  final case class Spec(
    appName: String,
    rootDir: File,
    organization: String,
    version: String,
    scalaVersion: String,
    sbtVersion: String,
    pluginVersion: String,
    cncfVersion: String,
    simpleModelingModelVersion: String,
    cncfCollaboratorApiVersion: String
  )

  def generate(spec: Spec): Seq[File] = {
    val root = spec.rootDir.getAbsoluteFile
    if (root.exists() && root.listFiles().toVector.nonEmpty)
      sys.error(s"[sbt-cozy] cozyAppRootDir is not empty: ${root.getAbsolutePath}")
    IO.createDirectory(root)

    val generated = Vector(
      _write(root / "README.md", _rootReadme(spec)),
      _write(root / "build.sbt", _rootBuild(spec)),
      _write(root / "project" / "build.properties", s"sbt.version=${spec.sbtVersion}\n"),
      _write(root / "project" / "plugins.sbt", _pluginsSbt(spec)),
      _write(root / "component" / "src" / "main" / "cozy" / s"${spec.appName}.cml", _componentCml(spec)),
      _write(root / "component" / "src" / "main" / "resources" / ".keep", ""),
      _write(root / "component" / "src" / "main" / "web" / ".keep", ""),
      _write(root / "component" / "src" / "test" / "scala" / ".keep", ""),
      _write(root / "subsystem" / "subsystem-descriptor.yaml", _subsystemDescriptor(spec)),
      _write(root / "subsystem" / "src" / "main" / "resources" / ".keep", ""),
      _write(root / "subsystem" / "src" / "test" / "scala" / ".keep", ""),
      _write(root / "subsystem" / "scripts" / "README.md", _scriptsReadme(spec))
    )

    generated
  }

  private def _write(path: File, content: String): File = {
    IO.createDirectory(path.getParentFile)
    IO.write(path, content)
    path
  }

  private def _normalized_package_name(appname: String): String =
    appname.toLowerCase.replace('-', '.')

  private def _rootReadme(spec: Spec): String =
    s"""# ${spec.appName}
|
|Generated Cozy application scaffold.
|
|Layout:
|- `component/`: application CAR source, Cozy model, web assets, component tests
|- `subsystem/`: subsystem descriptor, provider wiring, local subsystem scripts
|
|Common commands:
|- `sbt compile`
|- `sbt component/cozyGenerate`
|- `sbt component/cozyBuildCAR`
|- `sbt subsystem/cozyBuildSAR`
|
|Next expected edits:
|1. model the application under `component/src/main/cozy/`
|2. add UI assets under `component/src/main/web/`
|3. bind external components in `subsystem/subsystem-descriptor.yaml`
|""".stripMargin

  private def _rootBuild(spec: Spec): String = {
    val apppkg = _normalized_package_name(spec.appName)
    s"""import org.goldenport.cozy.CozyPlugin.autoImport._
|import sbt.Keys.*
|
|val scala3Version = ${_scala_string(spec.scalaVersion)}
|
|def sampleVersion(envName: String, fileName: String, fallback: String): String =
|  sys.env.get(envName)
|    .orElse {
|      sys.env.get("TEXTUS_SAMPLES_ROOT")
|        .orElse(sys.env.get("CNCF_SAMPLES_ROOT"))
|        .flatMap { root =>
|          val versionFile = file(root) / "versions" / fileName
|          if (versionFile.isFile)
|            Some(IO.read(versionFile).trim).filter(_.nonEmpty)
|          else
|            None
|        }
|    }
|    .getOrElse(fallback)
|
|val cncfVersion = sampleVersion("CNCF_VERSION", "cncf-version.conf", ${_scala_string(spec.cncfVersion)})
|val simpleModelingModelVersion = sampleVersion("SIMPLEMODELING_MODEL_VERSION", "simplemodeling-model-version.conf", ${_scala_string(spec.simpleModelingModelVersion)})
|val cncfCollaboratorApiVersion = sampleVersion("CNCF_COLLABORATOR_API_VERSION", "cncf-collaborator-api-version.conf", ${_scala_string(spec.cncfCollaboratorApiVersion)})
|
|lazy val commonSettings = Seq(
|  organization := ${_scala_string(spec.organization)},
|  version := ${_scala_string(spec.version)},
|  scalaVersion := scala3Version,
|  resolvers += Resolver.defaultLocal,
|  resolvers += Resolver.file("Local Ivy", file(Path.userHome.absolutePath + "/.ivy2/local"))(Resolver.ivyStylePatterns),
|  resolvers += "Local Maven Repository" at ("file://" + Path.userHome.absolutePath + "/.m2/repository"),
|  resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/maven"
|)
|
|lazy val root = project
|  .in(file("."))
|  .aggregate(component, subsystem)
|  .settings(commonSettings)
|  .settings(
|    name := ${_scala_string(spec.appName)},
|    publish / skip := true
|  )
|
|lazy val component = project
|  .in(file("component"))
|  .enablePlugins(org.goldenport.cozy.CozyPlugin)
|  .settings(commonSettings)
|  .settings(
|    name := ${_scala_string(spec.appName + "-component")},
|    cozyGeneratorBackend := "cozy",
|    cozyManifestMetadata ++= Map(
|      "component" -> ${_scala_string(spec.appName)},
|      "boundedContext" -> "default",
|      "domain" -> ${_scala_string(spec.appName)}
|    ),
|    libraryDependencies ++= Seq(
|      "org.goldenport" %% "goldenport-cncf" % cncfVersion,
|      "org.simplemodeling" %% "simplemodeling-model" % simpleModelingModelVersion,
|      "org.goldenport" % "cncf-collaborator-api" % cncfCollaboratorApiVersion,
|      "org.scalatest" %% "scalatest" % "3.2.19" % Test
|    ),
|    Test / fork := false
|  )
|
|lazy val subsystem = project
|  .in(file("subsystem"))
|  .enablePlugins(org.goldenport.cozy.CozyPlugin)
|  .settings(commonSettings)
|  .settings(
|    name := ${_scala_string(spec.appName + "-subsystem")},
|    cozyPackaging := "sar",
|    cozySourceDir := baseDirectory.value,
|    libraryDependencies ++= Seq(
|      "org.goldenport" %% "goldenport-cncf" % cncfVersion,
|      "org.scalatest" %% "scalatest" % "3.2.19" % Test
|    ),
|    Test / fork := false
|  )
|
|addCommandAlias("cozyBuildAppCAR", "component/cozyBuildCAR")
|addCommandAlias("cozyBuildAppSAR", "subsystem/cozyBuildSAR")
|addCommandAlias("cozyGenerateApp", "component/cozyGenerate")
|""".stripMargin
  }

  private def _pluginsSbt(spec: Spec): String =
    s"""resolvers += Resolver.defaultLocal
|addSbtPlugin("org.goldenport" % "sbt-cozy" % ${_scala_string(spec.pluginVersion)})
|""".stripMargin

  private def _componentCml(spec: Spec): String = {
    val apppkg = _normalized_package_name(spec.appName)
    s"""package sample.${apppkg}
|entity Post
|command CreatePost
|query GetPost
|query SearchPosts
|operation post-post command CreatePost
|operation get-post query GetPost
|operation search-posts query SearchPosts
|""".stripMargin
  }

  private def _subsystemDescriptor(spec: Spec): String =
    s"""subsystem: ${spec.appName}
|version: ${spec.version}
|components:
|  - component: ${spec.appName}
|    coordinate: ${spec.organization}:${spec.appName}-component:${spec.version}
|#  - component: textus-user-account
|#    coordinate: org.textus:textus-user-account:0.1.0-SNAPSHOT
|#security:
|#  authentication:
|#    convention: enabled
|#    fallback_privilege: disabled
|#    providers:
|#      - name: user-account
|#        component: textus-user-account
|#        kind: human
|#        enabled: true
|#        priority: 100
|#        schemes:
|#          - bearer
|#        default: true
|""".stripMargin

  private def _scriptsReadme(spec: Spec): String =
    s"""# subsystem/scripts
|
|Place local subsystem run helpers here.
|
|Typical workflow:
|- `sbt compile`
|- `sbt component/cozyBuildCAR`
|- `sbt subsystem/cozyBuildSAR`
|
|For ${spec.appName}, external providers such as `textus-user-account` are expected to be bound in `../subsystem-descriptor.yaml` by coordinate, not copied into this project tree.
|""".stripMargin

  private def _scala_string(value: String): String =
    '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
}

private[cozy] object CozyPackaging {
  def collectDocs(docsDir: File): Seq[(File, String)] = {
    if (!docsDir.exists()) {
      Seq.empty
    } else {
      (docsDir ** "*.md").get
        .filter(_.isFile)
        .sortBy(_.getAbsolutePath)
        .map(file => file -> relativePath(docsDir, file))
    }
  }

  def relativePath(baseDir: File, file: File): String = {
    IO.relativize(baseDir, file).getOrElse(file.getName).replace('\\', '/')
  }
}
