package org.goldenport.cozy

import java.nio.file.Files
import java.util.zip.ZipFile

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import sbt._

/*
 * @since   Mar. 22, 2026
 *  version Apr.  1, 2026
 *  version Apr.  4, 2026
 *  version Apr. 23, 2026
 * @version May. 22, 2026
 * @author  ASAMI, Tomoharu
 */
abstract class CozyTestBase extends AnyFunSuite {
  protected def withTempDir[A](prefix: String = "sbt-cozy-test")(f: File => A): A = {
    val dir = Files.createTempDirectory(prefix).toFile
    try f(dir)
    finally IO.delete(dir)
  }

  protected def write(path: File, content: String): File = {
    IO.createDirectory(path.getParentFile)
    IO.write(path, content)
    path
  }

  protected def zipEntries(path: File): Set[String] = {
    val zip = new ZipFile(path)
    try zip.entries().asScala.map(_.getName).toSet
    finally zip.close()
  }
}

final class CozyConfigValidatorSpec extends CozyTestBase {
  test("accepts default config") {
    assert(CozyConfigValidator.validate(CozyConfig.default) == Right(()))
  }

  test("accepts valid packagePrefix") {
    val config = CozyConfig(packagePrefix = Some("com.example.generated"))
    assert(CozyConfigValidator.validate(config) == Right(()))
  }

  test("rejects invalid packagePrefix") {
    val config = CozyConfig(packagePrefix = Some("com..example"))
    CozyConfigValidator.validate(config) match {
      case Left(message) => assert(message.contains("invalid packagePrefix"))
      case Right(_) => fail("expected invalid packagePrefix error")
    }
  }
}

final class CozyProjectConfigSpec extends CozyTestBase {
  test("parses project-local .cozy/config.yaml") {
    val config = CozyProjectConfig.parse(
      Seq(
        "generation:",
        "  source_dir: src/main/cozy",
        "  skip_unchanged: false",
        "  delegate:",
        "    command:",
        "      - cozy",
        "      - --batch",
        "publication:",
        "  name: cncf-samples",
        "  title: Textus Tutorial",
        "  path: samples/textus/tutorial",
        "  output: /Users/asami/src/dev2025/simplemodeling-org/publish.d",
        "  samples_dir: samples",
        "distribution:",
        "  repository: /Users/asami/src/maven-repository",
        "  require_release_version: true",
        "warehouse:",
        "  repository: /Users/asami/src/maven-repository",
        "  maven:",
        "    coordinates:",
        "      - org.goldenport:sbt-cozy_2.12_1.0",
        "      - org.simplemodeling:simplemodeling-model_3",
        "  repository_artifacts:",
        "    include:",
        "      - car",
        "      - sar",
        "    modules:",
        "      - textus-tutorial",
        "  download:",
        "    samples:",
        "      - textus-tutorial",
        "packaging:",
        "  car:",
        "    include_dependencies: false",
        "    dependencies:",
        "      provided:",
        "        - org.goldenport:goldenport-cncf_3:0.4.8-SNAPSHOT",
        "      shared:",
        "        - org.postgresql:postgresql:42.7.3",
        "      local:",
        "        - com.example:legacy-driver:1.2.0",
        "      repositories:",
        "        - maven-central",
        "    manifest_metadata:",
        "      component: textus",
        "      bounded_context: default"
      )
    )

    assert(config.value("generation.source_dir").contains("src/main/cozy"))
    assert(config.boolean("generation.skip_unchanged").contains(false))
    assert(config.list("generation.delegate.command") == Seq("cozy", "--batch"))
    assert(config.value("publication.name").contains("cncf-samples"))
    assert(config.value("publication.title").contains("Textus Tutorial"))
    assert(config.value("publication.path").contains("samples/textus/tutorial"))
    assert(config.value("publication.output").contains("/Users/asami/src/dev2025/simplemodeling-org/publish.d"))
    assert(config.value("publication.samples_dir").contains("samples"))
    assert(config.value("distribution.repository").contains("/Users/asami/src/maven-repository"))
    assert(config.boolean("distribution.require_release_version").contains(true))
    assert(config.value("warehouse.repository").contains("/Users/asami/src/maven-repository"))
    assert(config.list("warehouse.maven.coordinates") == Seq("org.goldenport:sbt-cozy_2.12_1.0", "org.simplemodeling:simplemodeling-model_3"))
    assert(config.list("warehouse.repository_artifacts.include") == Seq("car", "sar"))
    assert(config.list("warehouse.repository_artifacts.modules") == Seq("textus-tutorial"))
    assert(config.list("warehouse.download.samples") == Seq("textus-tutorial"))
    assert(config.mapUnder("packaging.car.manifest_metadata") == Map("component" -> "textus", "bounded_context" -> "default"))
  }

  test("resolves publication path from project metadata when local config omits it") {
    val config = CozyProjectConfig.parse(
      Seq(
        "publication:",
        "  name: textus-tutorial"
      )
    )
    val projectmetadata = CozyProjectConfig.parse(
      Seq(
        "project:",
        "  name: textus-tutorial",
        "  path: textus/tutorial/textus-tutorial"
      )
    )
    val configwithpath = CozyProjectConfig.parse(
      Seq(
        "publication:",
        "  path: textus/tutorial/custom"
      )
    )

    assert(CozyPlugin.publication_path(config, projectmetadata).contains("textus/tutorial/textus-tutorial"))
    assert(CozyPlugin.publication_path(configwithpath, projectmetadata).contains("textus/tutorial/custom"))
  }

  test("resolves local CNCF repository root from config or home default") {
    withTempDir("sbt-cozy-local-repository") { dir =>
      val home = dir / "home"
      val config = CozyProjectConfig.parse(
        Seq(
          "local:",
          "  repository: target/local-cncf-repository"
        )
      )
      val cncfconfig = CozyProjectConfig.parse(
        Seq(
          "cncf:",
          "  local:",
          "    repository: target/cncf-repository"
        )
      )

      assert(CozyPlugin.local_repository_dir(dir, config, home) == dir / "target" / "local-cncf-repository")
      assert(CozyPlugin.local_repository_dir(dir, cncfconfig, home) == dir / "target" / "cncf-repository")
      assert(CozyPlugin.local_repository_dir(dir, CozyProjectConfig.empty, home) == home / ".cncf" / "repository")
    }
  }

  test("renders planned sample archives as a compact tree") {
    withTempDir() { warehouse =>
      val root = warehouse / "repository" / "download" / "textus" / "tutorial" / "textus-tutorial"
      val files = Seq(
        root / "0.1.0" / "textus-tutorial-0.1.0.zip",
        root / "0.1.0" / "01-minimal" / "01-minimal-0.1.0.zip"
      )

      assert(CozyPlugin.sample_archive_tree_lines(warehouse, root, files) == Seq(
        "repository/download/textus/tutorial/textus-tutorial",
        "+-- 0.1.0",
        "    |-- 01-minimal",
        "    |   +-- 01-minimal-0.1.0.zip",
        "    +-- textus-tutorial-0.1.0.zip"
      ))
    }
  }
}

final class CozyParserSpec extends CozyTestBase {
  test("parses minimal valid model") {
    withTempDir() { dir =>
      val source = write(
        dir / "model.cml",
        """package com.example.cozy
          |entity UserAccount
          |command CreateUser
          |query GetUser
          |operation CreateUserOp command CreateUser
          |""".stripMargin
      )

      CozyParser.parseAll(Seq(source)) match {
        case Right(model) =>
          assert(model.packageName == "com.example.cozy")
          assert(model.entities.map(_.name) == Vector("UserAccount"))
          assert(model.commands.map(_.name) == Vector("CreateUser"))
          assert(model.queries.map(_.name) == Vector("GetUser"))
          assert(model.operations.map(_.name) == Vector("CreateUserOp"))
        case Left(error) =>
          fail(s"unexpected parse error: ${error.render}")
      }
    }
  }

  test("rejects invalid package declaration") {
    withTempDir() { dir =>
      val source = write(dir / "invalid.cml", "package com..example\nentity User\n")
      CozyParser.parseAll(Seq(source)) match {
        case Left(error) => assert(error.message.contains("invalid package name"))
        case Right(_) => fail("expected invalid package name error")
      }
    }
  }
}

final class CozyModelValidatorSpec extends CozyTestBase {
  test("detects duplicate definitions") {
    withTempDir() { dir =>
      val source = write(
        dir / "duplicate.cml",
        """package com.example
          |entity User
          |entity User
          |""".stripMargin
      )

      val model = CozyParser.parseAll(Seq(source)) match {
        case Right(value) => value
        case Left(error) => fail(s"unexpected parse error: ${error.render}")
      }

      CozyModelValidator.validate(model) match {
        case Left(error) => assert(error.message.contains("duplicate entity definition"))
        case Right(_) => fail("expected duplicate definition error")
      }
    }
  }

  test("detects unknown operation link target") {
    withTempDir() { dir =>
      val source = write(
        dir / "unknown-command.cml",
        """package com.example
          |operation ExecuteOp command MissingCommand
          |""".stripMargin
      )

      val model = CozyParser.parseAll(Seq(source)) match {
        case Right(value) => value
        case Left(error) => fail(s"unexpected parse error: ${error.render}")
      }

      CozyModelValidator.validate(model) match {
        case Left(error) => assert(error.message.contains("references unknown command"))
        case Right(_) => fail("expected unknown command validation error")
      }
    }
  }
}

final class CozyGeneratorSpec extends CozyTestBase {
  test("respects derived generation toggles and package prefix") {
    withTempDir() { dir =>
      val source = write(
        dir / "model.cml",
        """package app.model
          |entity User
          |aggregate ExplicitAggregate
          |view ExplicitView
          |command CreateUser
          |query GetUser
          |operation CreateUserOp command CreateUser
          |""".stripMargin
      )
      val out = dir / "out"
      val config = CozyConfig(
        generateDerivedAggregates = false,
        generateDerivedViews = false,
        packagePrefix = Some("com.acme")
      )

      val model = CozyParser.parseAll(Seq(source)) match {
        case Right(value) => value
        case Left(error) => fail(s"unexpected parse error: ${error.render}")
      }

      val generated = CozyGenerator.generate(model, out, config).map(_.getAbsolutePath).toSet
      assert(generated.contains((out / "aggregate" / "ExplicitAggregate.scala").getAbsolutePath))
      assert(generated.contains((out / "view" / "ExplicitView.scala").getAbsolutePath))
      assert(!generated.contains((out / "aggregate" / "UserAggregate.scala").getAbsolutePath))
      assert(!generated.contains((out / "view" / "UserView.scala").getAbsolutePath))

      val commandFile = out / "command" / "CreateUser.scala"
      val content = IO.read(commandFile)
      assert(content.contains("package com.acme.app.model.command"))
    }
  }
}

final class CozyGenerationStateSpec extends CozyTestBase {
  test("treats unchanged timestamps and outputs as up to date") {
    withTempDir("sbt-cozy-state") { dir =>
      val sourceDir = dir / "src"
      val targetDir = dir / "target"
      val stateFile = dir / "state.properties"
      val source = write(sourceDir / "model.cml", "package app\nentity User\n")
      val output = write(targetDir / "entity" / "User.scala", "// Generated by sbt-cozy. DO NOT EDIT.\n")
      val inputs = CozyGenerationState.capture(sourceDir, Seq(source), "legacy", CozyConfig.default)

      CozyGenerationState.write(stateFile, inputs)

      assert(CozyGenerationState.isUpToDate(stateFile, inputs, Seq(output)))
    }
  }

  test("invalidates state when input timestamp changes") {
    withTempDir("sbt-cozy-state") { dir =>
      val sourceDir = dir / "src"
      val stateFile = dir / "state.properties"
      val source = write(sourceDir / "model.cml", "package app\nentity User\n")
      val initialInputs = CozyGenerationState.capture(sourceDir, Seq(source), "legacy", CozyConfig.default)
      CozyGenerationState.write(stateFile, initialInputs)

      Thread.sleep(1100)
      IO.write(source, "package app\nentity Account\n")
      val updatedInputs = CozyGenerationState.capture(sourceDir, Seq(source), "legacy", CozyConfig.default)

      assert(!CozyGenerationState.isUpToDate(stateFile, updatedInputs, Seq(dir / "dummy.scala")))
    }
  }

  test("invalidates state when generator settings change") {
    withTempDir("sbt-cozy-state") { dir =>
      val sourceDir = dir / "src"
      val targetDir = dir / "target"
      val stateFile = dir / "state.properties"
      val source = write(sourceDir / "model.cml", "package app\nentity User\n")
      val output = write(targetDir / "entity" / "User.scala", "// Generated by sbt-cozy. DO NOT EDIT.\n")
      val defaultInputs = CozyGenerationState.capture(sourceDir, Seq(source), "legacy", CozyConfig.default)
      CozyGenerationState.write(stateFile, defaultInputs)

      val updatedConfig = CozyConfig(generateDerivedAggregates = false)
      val updatedInputs = CozyGenerationState.capture(sourceDir, Seq(source), "legacy", updatedConfig)

      assert(!CozyGenerationState.isUpToDate(stateFile, updatedInputs, Seq(output)))
    }
  }

  test("invalidates state when generated outputs are missing") {
    withTempDir("sbt-cozy-state") { dir =>
      val sourceDir = dir / "src"
      val stateFile = dir / "state.properties"
      val source = write(sourceDir / "model.cml", "package app\nentity User\n")
      val inputs = CozyGenerationState.capture(sourceDir, Seq(source), "legacy", CozyConfig.default)
      CozyGenerationState.write(stateFile, inputs)

      assert(!CozyGenerationState.isUpToDate(stateFile, inputs, Seq.empty))
      assert(!CozyGenerationState.isUpToDate(stateFile, inputs, Seq(dir / "missing.scala")))
    }
  }
}

final class CozyDelegatedGeneratorSpec extends CozyTestBase {
  test("bridge uses direct cozy command by default") {
    withTempDir("sbt-cozy-delegate") { dir =>
      val source = write(dir / "src" / "model.cml", "package app\nentity User\n")
      val saveDir = dir / "out"
      val (cwd, resolved) = CozySbtBridge.resolveForTest(
        basedir = dir,
        delegateprojectdir = None,
        delegatecommand = Seq("cozy"),
        action = "generate",
        arguments = Vector("modeler-scala", source.getAbsolutePath, s"--save=${saveDir.getAbsolutePath}")
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assert(resolved.last.startsWith("--request="))
    }
  }

  test("bridge can use explicit coursier version during development") {
    withTempDir("sbt-cozy-delegate") { dir =>
      val command = CozySbtBridge.coursierCommand("0.2.17-SNAPSHOT")
      val (cwd, resolved) = CozySbtBridge.resolveForTest(
        basedir = dir,
        delegateprojectdir = None,
        delegatecommand = command,
        action = "generate",
        arguments = Vector("modeler-scala", "/tmp/model.cml", "--save=/tmp/out")
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.take(2) == Seq("cs", "launch"))
      assert(resolved.contains("ivy2Local"))
      assert(resolved.contains("org.simplemodeling:cozy_2.12:0.2.17-SNAPSHOT"))
      assert(resolved.contains("cozy.Cozy"))
      assert(resolved.contains("--"))
      assert(resolved.takeRight(2).head == "v1")
      assert(resolved.last.startsWith("--request="))
    }
  }


  test("bridge delegates publish-project requests") {
    withTempDir("sbt-cozy-publish-project-delegate") { dir =>
      val out = dir / "publish.d"
      val (cwd, resolved) = CozySbtBridge.resolveForTest(
        basedir = dir,
        delegateprojectdir = None,
        delegatecommand = Seq("cozy"),
        action = "publish-project",
        arguments = Vector(dir.getAbsolutePath, s"--save=${out.getAbsolutePath}", "--kind=sample-single")
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assert(resolved.last.startsWith("--request="))
    }
  }

  test("bridge uses explicit cozy project during development") {
    withTempDir("sbt-cozy-delegate") { dir =>
      val cozyDir = dir / "cozy"
      write(cozyDir / "build.sbt", """name := "cozy"""")
      val (cwd, resolved) = CozySbtBridge.resolveForTest(
        basedir = dir,
        delegateprojectdir = Some(cozyDir),
        delegatecommand = Seq("cozy"),
        action = "package-sar",
        arguments = Vector("--save=/tmp/sample.sar")
      )
      assert(cwd.getAbsolutePath == cozyDir.getAbsolutePath)
      assert(resolved.take(4) == Seq("sbt", "--batch", "-Dsbt.server.autostart=false", "-Dsbt.supershell=false"))
      assert(resolved.last.startsWith("runMain cozy.Cozy "))
    }
  }
}

final class CozyManifestMetadataSpec extends CozyTestBase {
  test("componentlet metadata is packed into descriptor JSON") {
    val metadata = Map(
      "component" -> "sample-component",
      "boundedContext" -> "default",
      "componentlets" -> "public-notice,notice-admin",
      "componentlet.public-notice.kind" -> "componentlet",
      "componentlet.public-notice.isPrimary" -> "false",
      "componentlet.notice-admin.kind" -> "componentlet"
    )

    val result = CozyManifestMetadata.from(metadata, "sample")

    assert(result.component == "sample-component")
    assert(result.config.isEmpty)
    assert(result.extensions.get("boundedContext").contains("default"))
    assert(result.extensions.get("componentlets").isEmpty)
    val descriptor = result.extensions.getOrElse("componentDescriptorJson", fail("missing descriptor JSON"))
    assert(descriptor.contains("\"component\":{\"name\":\"sample-component\",\"boundedContext\":\"default\"}"))
    assert(descriptor.contains("\"componentlets\":["))
    assert(descriptor.contains("\"name\":\"notice-admin\""))
    assert(descriptor.contains("\"name\":\"public-notice\""))
    assert(descriptor.contains("\"kind\":\"componentlet\""))
    assert(descriptor.contains("\"isPrimary\":\"false\""))
  }
}

final class CozyPackagingSpec extends CozyTestBase {
  test("loadSarSources accepts descriptor-only subsystem definition") {
    withTempDir("sbt-cozy-sar-descriptor") { dir =>
      val descriptor = write(dir / "subsystem-descriptor.yaml", "subsystem: textus-identity")
      val files = CozyFileLoader.loadSarSources(dir)
      assert(files == Seq(descriptor))
    }
  }

  test("packageSar delegates descriptor paths to cozy CLI arguments") {
    withTempDir("sbt-cozy-sar-delegate") { dir =>
      val archive = dir / "out" / "sample.sar"
      val command = Seq("cozy")
      val (cwd, resolved) = CozySbtBridge.resolveForTest(
        basedir = dir,
        delegateprojectdir = None,
        delegatecommand = command,
        action = "package-sar",
        arguments = Vector(
          s"--save=${archive.getAbsolutePath}",
          s"--source-dir=${(dir / "src").getAbsolutePath}",
          "--source-files=subsystem-descriptor.yaml"
        )
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assert(resolved.last.startsWith("--request="))
    }
  }

  test("publishCar delegates warehouse publication to cozy bridge") {
    withTempDir("sbt-cozy-publish-car-delegate") { dir =>
      val archive = write(dir / "target" / "sample-component.car", "car")
      val warehouse = dir / "warehouse"
      val (cwd, resolved) = CozySbtBridge.resolveForTest(
        basedir = dir,
        delegateprojectdir = None,
        delegatecommand = Seq("cozy"),
        action = "publish-car",
        arguments = Vector(
          dir.getAbsolutePath,
          s"--warehouse=${warehouse.getAbsolutePath}",
          "--name=sample-component",
          "--version=0.1.0",
          s"--car=${archive.getAbsolutePath}"
        )
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assert(resolved.last.startsWith("--request="))
    }
  }

  test("publishSar delegates warehouse publication to cozy bridge") {
    withTempDir("sbt-cozy-publish-sar-delegate") { dir =>
      val archive = write(dir / "target" / "sample-subsystem.sar", "sar")
      val warehouse = dir / "warehouse"
      val (cwd, resolved) = CozySbtBridge.resolveForTest(
        basedir = dir,
        delegateprojectdir = None,
        delegatecommand = Seq("cozy"),
        action = "publish-sar",
        arguments = Vector(
          dir.getAbsolutePath,
          s"--warehouse=${warehouse.getAbsolutePath}",
          "--name=sample-subsystem",
          "--version=0.1.0",
          s"--sar=${archive.getAbsolutePath}"
        )
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assert(resolved.last.startsWith("--request="))
    }
  }
}
