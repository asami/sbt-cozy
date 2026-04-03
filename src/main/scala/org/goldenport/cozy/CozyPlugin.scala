package org.goldenport.cozy

import java.time.Instant

import sbt._
import sbt.Keys._
import scala.sys.process._

object CozyPlugin extends AutoPlugin {
  object autoImport {
    val cozyConfig = settingKey[CozyConfig]("Configuration for sbt-cozy code generation")
    val cozySourceDir = settingKey[File]("Directory containing CML/cozy sources")
    val cozyTargetDir = settingKey[File]("Directory where Scala sources are generated")
    val cozyGeneratorBackend = settingKey[String]("Generator backend. Either 'cozy' or 'legacy'.")
    val cozyDelegateProjectDir = settingKey[Option[File]]("Optional path to cozy project used by delegated generation.")
    val cozyDelegateCommand = settingKey[Seq[String]]("Command prefix used to execute delegated cozy generation.")
    val cozySkipUnchangedGeneration = settingKey[Boolean]("Skip code generation when CML timestamps and generator settings are unchanged.")
    val cozyGenerate = taskKey[Seq[File]]("Generate Scala sources from CML/cozy definitions")

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
    cozyDelegateCommand := Seq(
      "sbt",
      "--batch",
      "-Dsbt.server.autostart=false",
      "-Dsbt.supershell=false"
    ),
    cozySkipUnchangedGeneration := true,

    cozyPackaging := "car",
    cozyCarName := s"${moduleName.value}-${version.value}",
    cozySarName := s"${moduleName.value}-${version.value}",
    cozySpiJars := Seq.empty,
    cozySarExtensionJars := Seq.empty,
    cozyManifestMetadata := Map.empty,
    cozyLocalRepositoryDir := target.value / "cozy-repository",

    cozyGenerate := {
      val sourceDir = cozySourceDir.value
      val targetDir = cozyTargetDir.value
      val config = cozyConfig.value
      val backend = cozyGeneratorBackend.value.trim.toLowerCase
      val delegateProjectDir = cozyDelegateProjectDir.value
      val delegateCommand = cozyDelegateCommand.value
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
      val backend = cozyGeneratorBackend.value.trim.toLowerCase
      val sourceDir = cozySourceDir.value
      val cozyFiles = CozyFileLoader.load(sourceDir)
      val log = streams.value.log
      val model =
        if (backend == "legacy")
          parseValidatedModelOption(cozyFiles, log)
        else
          None
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
      val docsFiles = CozyPackaging.collectDocs(baseDirectory.value / "docs")

      val payload = CozyPackaging.ManifestPayload(
        packaging = "car",
        name = cozyCarName.value,
        module = moduleName.value,
        version = version.value,
        scalaBinaryVersion = scalaBinaryVersion.value,
        generatedAt = Instant.now().toString,
        packageName = model.map(_.packageName),
        entities = model.map(_.entities.map(_.name).sorted).getOrElse(Vector.empty),
        aggregates = model.map(_.aggregates.map(_.name).sorted).getOrElse(Vector.empty),
        views = model.map(_.views.map(_.name).sorted).getOrElse(Vector.empty),
        commands = model.map(_.commands.map(_.name).sorted).getOrElse(Vector.empty),
        queries = model.map(_.queries.map(_.name).sorted).getOrElse(Vector.empty),
        events = model.map(_.events.map(_.name).sorted).getOrElse(Vector.empty),
        operations = model.map(_.operations.map(_.name).sorted).getOrElse(Vector.empty),
        precedence = Map("extension" -> "SAR > CAR", "config" -> "SAR > CAR"),
        extra = cozyManifestMetadata.value + ("cozyPackaging" -> "car")
      )

      CozyPackaging.buildCar(
        archive = archive,
        mainJar = mainJar,
        libJars = libJars,
        spiJars = spiJars,
        defaultConf = if (defaultConf.exists()) Some(defaultConf) else None,
        docsFiles = docsFiles,
        manifestJson = CozyPackaging.renderManifest(payload)
      )

      streams.value.log.info(s"[sbt-cozy] built CAR: ${archive.getAbsolutePath}")
      archive
    },

    cozyBuildSAR := {
      val backend = cozyGeneratorBackend.value.trim.toLowerCase
      val sourceDir = cozySourceDir.value
      val cozyFiles = CozyFileLoader.load(sourceDir)
      val log = streams.value.log
      if (cozyFiles.isEmpty) {
        sys.error(s"[sbt-cozy] no cozy sources found under ${sourceDir.getAbsolutePath}; SAR requires subsystem definition sources")
      }

      val model =
        if (backend == "legacy")
          parseValidatedModelOption(cozyFiles, log)
        else
          None
      val archive = target.value / s"${cozySarName.value}.sar"
      val subsystemSources = cozyFiles.map(file => file -> CozyPackaging.relativePath(sourceDir, file))
      val extensionJars = cozySarExtensionJars.value
        .filter(file => file.isFile && file.getName.endsWith(".jar"))
        .distinct
        .sortBy(_.getName)
      val applicationConf = (Compile / resourceDirectory).value / "application.conf"

      val payload = CozyPackaging.ManifestPayload(
        packaging = "sar",
        name = cozySarName.value,
        module = moduleName.value,
        version = version.value,
        scalaBinaryVersion = scalaBinaryVersion.value,
        generatedAt = Instant.now().toString,
        packageName = model.map(_.packageName),
        entities = model.map(_.entities.map(_.name).sorted).getOrElse(Vector.empty),
        aggregates = model.map(_.aggregates.map(_.name).sorted).getOrElse(Vector.empty),
        views = model.map(_.views.map(_.name).sorted).getOrElse(Vector.empty),
        commands = model.map(_.commands.map(_.name).sorted).getOrElse(Vector.empty),
        queries = model.map(_.queries.map(_.name).sorted).getOrElse(Vector.empty),
        events = model.map(_.events.map(_.name).sorted).getOrElse(Vector.empty),
        operations = model.map(_.operations.map(_.name).sorted).getOrElse(Vector.empty),
        precedence = Map("extension" -> "SAR > CAR", "config" -> "SAR > CAR"),
        extra = cozyManifestMetadata.value + ("cozyPackaging" -> "sar")
      )

      CozyPackaging.buildSar(
        archive = archive,
        subsystemSources = subsystemSources,
        extensionJars = extensionJars,
        applicationConf = if (applicationConf.exists()) Some(applicationConf) else None,
        manifestJson = CozyPackaging.renderManifest(payload)
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

    Compile / sourceGenerators += cozyGenerate.taskValue
  )

  private def parseValidatedModelOption(cozyFiles: Seq[File], log: Logger): Option[CozyModel] = {
    if (cozyFiles.isEmpty) {
      None
    } else {
      try {
        Some(parseValidatedModel(cozyFiles))
      } catch {
        case e: RuntimeException =>
          log.warn(s"[sbt-cozy] failed to parse cozy metadata for manifest: ${e.getMessage}")
          None
      }
    }
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
    log: Logger
  ): Seq[File] = {
    val saveDir = workDir / s"run-${runIndex}"
    IO.createDirectory(saveDir)
    val delegate = resolveDelegateExecution(
      baseDir = baseDir,
      explicitProjectDir = delegateProjectDir,
      delegateCommand = delegateCommand,
      source = source,
      saveDir = saveDir
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

  private case class DelegateExecution(cwd: File, command: Seq[String])

  private def resolveDelegateExecution(
    baseDir: File,
    explicitProjectDir: Option[File],
    delegateCommand: Seq[String],
    source: File,
    saveDir: File
  ): DelegateExecution = {
    val commandPrefix = delegateCommand match {
      case Seq() =>
        sys.error("[sbt-cozy] cozy delegate command is empty.")
      case xs => xs
    }
    if (usesProjectDelegate(explicitProjectDir, commandPrefix)) {
      val cozyDir = resolveDelegateProjectDir(baseDir, explicitProjectDir)
      val runMainCommand =
        s"runMain cozy.Cozy modeler-scala ${source.getAbsolutePath} --save=${saveDir.getAbsolutePath}"
      DelegateExecution(cozyDir, commandPrefix :+ runMainCommand)
    } else {
      DelegateExecution(
        cwd = baseDir,
        command = commandPrefix ++ Seq(
          "modeler-scala",
          source.getAbsolutePath,
          s"--save=${saveDir.getAbsolutePath}"
        )
      )
    }
  }

  private def usesProjectDelegate(explicitProjectDir: Option[File], delegateCommand: Seq[String]): Boolean =
    explicitProjectDir.nonEmpty || delegateCommand.headOption.contains("sbt")

  private def resolveDelegateProjectDir(baseDir: File, explicit: Option[File]): File = {
    val home = file(System.getProperty("user.home"))
    val candidates = Vector(
      baseDir.getParentFile / "cozy",
      home / "src" / "dev2025" / "cozy",
      home / "src" / "dev2026" / "cozy"
    )

    val resolved = explicit.orElse(candidates.find(isValidCozyProject))
    resolved.filter(isValidCozyProject).getOrElse {
      sys.error(
        "[sbt-cozy] cozy project directory is not configured. " +
        "Set cozyDelegateProjectDir or SBT_COZY_PROJECT_DIR."
      )
    }
  }

  private def isValidCozyProject(dir: File): Boolean =
    dir != null && dir.isDirectory && (dir / "build.sbt").isFile

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

private[cozy] object CozyPackaging {
  final case class ManifestPayload(
    packaging: String,
    name: String,
    module: String,
    version: String,
    scalaBinaryVersion: String,
    generatedAt: String,
    packageName: Option[String],
    entities: Vector[String],
    aggregates: Vector[String],
    views: Vector[String],
    commands: Vector[String],
    queries: Vector[String],
    events: Vector[String],
    operations: Vector[String],
    precedence: Map[String, String],
    extra: Map[String, String]
  )

  def buildCar(
    archive: File,
    mainJar: File,
    libJars: Seq[File],
    spiJars: Seq[File],
    defaultConf: Option[File],
    docsFiles: Seq[(File, String)],
    manifestJson: String
  ): File = {
    IO.withTemporaryDirectory { staging =>
      copyTo(mainJar, staging / "component" / "main.jar")
      copyCollection(libJars, staging / "lib")
      copyCollection(spiJars, staging / "spi")
      defaultConf.foreach(file => copyTo(file, staging / "config" / "default.conf"))
      docsFiles.foreach {
        case (source, relative) =>
          copyTo(source, staging / "docs" / normalizePath(relative))
      }
      writeText(staging / "meta" / "manifest.json", manifestJson)
      ensureSectionPlaceholders(staging, Vector("component", "lib", "spi", "config", "docs", "meta"))
      writeArchive(staging, archive)
    }
    archive
  }

  def buildSar(
    archive: File,
    subsystemSources: Seq[(File, String)],
    extensionJars: Seq[File],
    applicationConf: Option[File],
    manifestJson: String
  ): File = {
    IO.withTemporaryDirectory { staging =>
      subsystemSources.foreach {
        case (source, relative) =>
          copyTo(source, staging / "subsystem" / normalizePath(relative))
      }
      copyCollection(extensionJars, staging / "extension")
      applicationConf.foreach(file => copyTo(file, staging / "config" / "application.conf"))
      writeText(staging / "meta" / "manifest.json", manifestJson)
      ensureSectionPlaceholders(staging, Vector("subsystem", "extension", "config", "meta"))
      writeArchive(staging, archive)
    }
    archive
  }

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

  def renderManifest(payload: ManifestPayload): String = {
    val fields = Vector(
      "packaging" -> renderString(payload.packaging),
      "name" -> renderString(payload.name),
      "module" -> renderString(payload.module),
      "version" -> renderString(payload.version),
      "scalaBinaryVersion" -> renderString(payload.scalaBinaryVersion),
      "generatedAt" -> renderString(payload.generatedAt),
      "package" -> payload.packageName.map(renderString).getOrElse("null"),
      "entities" -> renderArray(payload.entities),
      "aggregates" -> renderArray(payload.aggregates),
      "views" -> renderArray(payload.views),
      "commands" -> renderArray(payload.commands),
      "queries" -> renderArray(payload.queries),
      "events" -> renderArray(payload.events),
      "operations" -> renderArray(payload.operations),
      "precedence" -> renderMap(payload.precedence),
      "extra" -> renderMap(payload.extra)
    )

    val body = fields.map {
      case (key, value) => s"  ${renderString(key)}: ${value}"
    }.mkString(",\n")

    s"{\n${body}\n}\n"
  }

  private def writeArchive(staging: File, archive: File): Unit = {
    val entries = (staging ** "*").get
      .filter(_.isFile)
      .map(file => file -> relativePath(staging, file))
      .sortBy(_._2)

    IO.createDirectory(archive.getParentFile)
    IO.delete(archive)
    IO.zip(entries, archive)
  }

  private def ensureSectionPlaceholders(staging: File, sections: Vector[String]): Unit = {
    sections.foreach { section =>
      val dir = staging / section
      val hasFiles = (dir ** "*").get.exists(_.isFile)
      if (!hasFiles) {
        writeText(dir / ".keep", "")
      }
    }
  }

  private def copyCollection(files: Seq[File], destinationDir: File): Unit = {
    files.filter(_.isFile).foreach { source =>
      copyTo(source, destinationDir / source.getName)
    }
  }

  private def copyTo(source: File, destination: File): Unit = {
    IO.createDirectory(destination.getParentFile)
    IO.copyFile(source, destination, preserveLastModified = true)
  }

  private def writeText(path: File, content: String): Unit = {
    IO.createDirectory(path.getParentFile)
    IO.write(path, content)
  }

  private def normalizePath(path: String): String = path.replace('\\', '/').stripPrefix("/")

  private def renderArray(values: Vector[String]): String = {
    values.map(renderString).mkString("[", ", ", "]")
  }

  private def renderMap(values: Map[String, String]): String = {
    val ordered = values.toVector.sortBy(_._1)
    ordered.map {
      case (key, value) => s"${renderString(key)}: ${renderString(value)}"
    }.mkString("{", ", ", "}")
  }

  private def renderString(value: String): String = {
    val escaped = value.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c => c.toString
    }
    "\"" + escaped + "\""
  }
}
