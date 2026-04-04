package org.goldenport.cozy

import java.nio.file.Files
import java.util.zip.ZipFile

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import sbt._

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
  test("prefers same-workspace cozy candidates before home fallback") {
    withTempDir("sbt-cozy-delegate") { dir =>
      val baseDir = dir / "repo" / "sbt-cozy"
      val homeDir = dir / "home"
      IO.createDirectory(baseDir)

      val candidates = CozyDelegatedGenerator.candidateDelegateProjectDirs(baseDir, homeDir)
      val expectedPrefix = Vector(
        dir / "repo" / "cozy",
        dir / "repo" / "cncf" / "cozy",
        dir / "repo" / "modules" / "cozy",
        dir / "repo" / "tools" / "cozy",
        baseDir / "cozy",
        baseDir / "modules" / "cozy",
        baseDir / "tools" / "cozy"
      ).map(_.getAbsolutePath)

      assert(candidates.take(expectedPrefix.size).map(_.getAbsolutePath) == expectedPrefix)
      assert(candidates.lastOption.exists(_.getAbsolutePath.endsWith("/src/dev2026/cozy")))
    }
  }

  test("resolves explicit same-repo cozy project") {
    withTempDir("sbt-cozy-delegate") { dir =>
      val baseDir = dir / "repo" / "sbt-cozy"
      val cozyDir = dir / "repo" / "cozy"
      IO.createDirectory(baseDir)
      write(cozyDir / "build.sbt", """name := "cozy"""")

      val resolved = CozyDelegatedGenerator.resolveDelegateProjectDir(baseDir, None)
      assert(resolved.getAbsolutePath == cozyDir.getAbsolutePath)
    }
  }
}

final class CozyPackagingSpec extends CozyTestBase {
  test("buildCar produces required CAR layout") {
    withTempDir("sbt-cozy-car") { dir =>
      val mainJar = write(dir / "artifacts" / "main.jar", "main")
      val libJar = write(dir / "artifacts" / "dep.jar", "dep")
      val spiJar = write(dir / "artifacts" / "spi.jar", "spi")
      val defaultConf = write(dir / "conf" / "default.conf", "service.timeout=10")
      val doc = write(dir / "docs" / "guide" / "intro.md", "# intro")

      val manifest = CozyPackaging.renderManifest(
        CozyPackaging.ManifestPayload(
          packaging = "car",
          name = "sample-component",
          module = "sample",
          version = "0.1.0",
          scalaBinaryVersion = "2.12",
          generatedAt = "2026-03-22T00:00:00Z",
          packageName = Some("com.example"),
          entities = Vector("Person"),
          aggregates = Vector("PersonAggregate"),
          views = Vector("PersonView"),
          commands = Vector("SavePerson"),
          queries = Vector("GetPerson"),
          events = Vector("PersonCreated"),
          operations = Vector("SavePersonOp"),
          precedence = Map("extension" -> "SAR > CAR", "config" -> "SAR > CAR"),
          extra = Map("cozyPackaging" -> "car")
        )
      )

      val archive = dir / "out" / "sample-component.car"
      CozyPackaging.buildCar(
        archive = archive,
        mainJar = mainJar,
        libJars = Seq(libJar),
        spiJars = Seq(spiJar),
        defaultConf = Some(defaultConf),
        docsFiles = Seq(doc -> "guide/intro.md"),
        manifestJson = manifest
      )

      val entries = zipEntries(archive)
      assert(entries.contains("component/main.jar"))
      assert(entries.contains("lib/dep.jar"))
      assert(entries.contains("spi/spi.jar"))
      assert(entries.contains("config/default.conf"))
      assert(entries.contains("docs/guide/intro.md"))
      assert(entries.contains("meta/manifest.json"))
    }
  }

  test("buildSar produces required SAR layout and precedence metadata") {
    withTempDir("sbt-cozy-sar") { dir =>
      val subsystem = write(dir / "cozy" / "identity" / "subsystem.cml", "entity Person")
      val extension = write(dir / "ext" / "grpc.jar", "grpc")
      val appConf = write(dir / "conf" / "application.conf", "env=dev")

      val manifest = CozyPackaging.renderManifest(
        CozyPackaging.ManifestPayload(
          packaging = "sar",
          name = "sample-subsystem",
          module = "sample",
          version = "0.1.0",
          scalaBinaryVersion = "2.12",
          generatedAt = "2026-03-22T00:00:00Z",
          packageName = Some("com.example"),
          entities = Vector("Person"),
          aggregates = Vector.empty,
          views = Vector.empty,
          commands = Vector.empty,
          queries = Vector.empty,
          events = Vector.empty,
          operations = Vector.empty,
          precedence = Map("extension" -> "SAR > CAR", "config" -> "SAR > CAR"),
          extra = Map("cozyPackaging" -> "sar")
        )
      )

      val archive = dir / "out" / "sample-subsystem.sar"
      CozyPackaging.buildSar(
        archive = archive,
        subsystemSources = Seq(subsystem -> "identity/subsystem.cml"),
        extensionJars = Seq(extension),
        applicationConf = Some(appConf),
        manifestJson = manifest
      )

      val entries = zipEntries(archive)
      assert(entries.contains("subsystem/identity/subsystem.cml"))
      assert(entries.contains("extension/grpc.jar"))
      assert(entries.contains("config/application.conf"))
      assert(entries.contains("meta/manifest.json"))
      assert(manifest.contains("\"extension\": \"SAR > CAR\""))
      assert(manifest.contains("\"config\": \"SAR > CAR\""))
    }
  }
}
