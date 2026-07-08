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
 *  version May. 26, 2026
 *  version Jun. 18, 2026
 * @version Jul.  8, 2026
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

  protected def assertBridgeRequestArgument(args: Seq[String]): Unit = {
    assert(args.takeRight(2).head == "--request")
    assert(args.last.endsWith(".json"))
  }
}

final class CozyPublishVersionPolicySpec extends CozyTestBase {
  test("standard publish task labels follow configured packaging kind") {
    assert(CozyPlugin.publishTaskLabel("car", local = false) == "cozyPublishCar")
    assert(CozyPlugin.publishTaskLabel("car", local = true) == "cozyPublishLocalCar")
    assert(CozyPlugin.publishTaskLabel("sar", local = false) == "cozyPublishSar")
    assert(CozyPlugin.publishTaskLabel("sar", local = true) == "cozyPublishLocalSar")
  }

  test("standard publish task labels reject unknown packaging kind") {
    val error = intercept[RuntimeException] {
      CozyPlugin.publishTaskLabel("jar", local = false)
    }

    assert(error.getMessage.contains("invalid cozyPackaging"))
    assert(error.getMessage.contains("car"))
    assert(error.getMessage.contains("sar"))
  }

  test("release publish tasks accept release versions") {
    CozyPlugin.validatePublishVersion("0.1.2", "cozyPublishCar", expectsnapshot = false)
    CozyPlugin.validatePublishVersion("0.1.2", "cozyPublishSar", expectsnapshot = false)
  }

  test("release publish tasks reject snapshot versions") {
    val error = intercept[RuntimeException] {
      CozyPlugin.validatePublishVersion("0.1.3-SNAPSHOT", "cozyPublishCar", expectsnapshot = false)
    }

    assert(error.getMessage.contains("cozyPublishCar rejects SNAPSHOT version"))
    assert(error.getMessage.contains("cozyPublishLocalCar"))
  }

  test("local publish tasks accept snapshot versions") {
    CozyPlugin.validatePublishVersion("0.1.3-SNAPSHOT", "cozyPublishLocalCar", expectsnapshot = true)
    CozyPlugin.validatePublishVersion("0.1.3-SNAPSHOT", "cozyPublishLocalSar", expectsnapshot = true)
  }

  test("local publish tasks reject release versions") {
    val error = intercept[RuntimeException] {
      CozyPlugin.validatePublishVersion("0.1.2", "cozyPublishLocalCar", expectsnapshot = true)
    }

    assert(error.getMessage.contains("cozyPublishLocalCar requires a SNAPSHOT version"))
    assert(error.getMessage.contains("cozyPublishCar"))
  }
}

final class CozyCoursierChannelSpec extends CozyTestBase {
  test("adds a Coursier channel entry without losing existing entries") {
    val existing =
      """{
        |  "cozy": {
        |    "repositories": ["central"],
        |    "dependencies": ["org.simplemodeling:cozy-launcher_3:0.1.0"],
        |    "mainClass": "cozy.launcher.CozyLauncherMain"
        |  }
        |}
        |""".stripMargin

    val result = CozyPlugin.coursierChannelJson(
      Some(existing),
      Seq(CozyCoursierChannelEntry(
        name = "cozy-runtime",
        repositories = Seq("central", "https://www.simplemodeling.org/repository/maven"),
        dependencies = Seq("org.simplemodeling:cozy_2.12:0.2.20"),
        mainClass = "cozy.Cozy"
      ))
    )

    assert(result.contains("\"cozy\""))
    assert(result.contains("\"cozy-runtime\""))
    assert(result.contains("org.simplemodeling:cozy-launcher_3:0.1.0"))
    assert(result.contains("org.simplemodeling:cozy_2.12:0.2.20"))
  }

  test("replaces only matching Coursier channel entries") {
    val existing =
      """{
        |  "cozy": {
        |    "repositories": ["central"],
        |    "dependencies": ["org.simplemodeling:cozy-launcher_3:0.1.0"],
        |    "mainClass": "cozy.launcher.CozyLauncherMain"
        |  },
        |  "cozy-runtime": {
        |    "repositories": ["central"],
        |    "dependencies": ["org.simplemodeling:cozy_2.12:0.2.19"],
        |    "mainClass": "cozy.Cozy"
        |  }
        |}
        |""".stripMargin

    val result = CozyPlugin.coursierChannelJson(
      Some(existing),
      Seq(CozyCoursierChannelEntry(
        name = "cozy-runtime",
        repositories = Seq("central"),
        dependencies = Seq("org.simplemodeling:cozy_2.12:0.2.20"),
        mainClass = "cozy.Cozy"
      ))
    )

    assert(result.contains("org.simplemodeling:cozy-launcher_3:0.1.0"))
    assert(result.contains("org.simplemodeling:cozy_2.12:0.2.20"))
    assert(!result.contains("org.simplemodeling:cozy_2.12:0.2.19"))
  }

  test("does not accumulate indentation when preserving existing entries") {
    val first = CozyPlugin.coursierChannelJson(
      None,
      Seq(CozyCoursierChannelEntry(
        name = "cozy",
        repositories = Seq("central"),
        dependencies = Seq("org.simplemodeling:cozy-launcher_3:0.1.0"),
        mainClass = "cozy.launcher.CozyLauncherMain"
      ))
    )
    val second = CozyPlugin.coursierChannelJson(
      Some(first),
      Seq(CozyCoursierChannelEntry(
        name = "cozy-runtime",
        repositories = Seq("central"),
        dependencies = Seq("org.simplemodeling:cozy_2.12:0.2.20"),
        mainClass = "cozy.Cozy"
      ))
    )
    val third = CozyPlugin.coursierChannelJson(
      Some(second),
      Seq(CozyCoursierChannelEntry(
        name = "cozy-runtime",
        repositories = Seq("central"),
        dependencies = Seq("org.simplemodeling:cozy_2.12:0.2.21"),
        mainClass = "cozy.Cozy"
      ))
    )

    assert(!third.contains("      \"repositories\""))
    assert(third.contains("    \"repositories\""))
    assert(third.contains("org.simplemodeling:cozy-launcher_3:0.1.0"))
    assert(third.contains("org.simplemodeling:cozy_2.12:0.2.21"))
  }

  test("derives warehouse root from a Maven repository publish target") {
    withTempDir("sbt-cozy-coursier-channel-warehouse") { dir =>
      val warehouse = dir / "warehouse"
      val repository = warehouse / "repository" / "maven"
      val derived = CozyPlugin.coursierChannelWarehouseDirFromMavenRepository(repository)

      assert(derived.getCanonicalFile == warehouse.getCanonicalFile)
    }
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

final class CozyWebDescriptorSyncSpec extends CozyTestBase {
  test("generates form descriptor entries from service operation and attribute WEB metadata") {
    withTempDir("sbt-cozy-web-descriptor-sync") { dir =>
      val cml = write(
        dir / "src" / "main" / "cozy" / "sample.cml",
        """Sample Component
          |================
          |
          |# WEB
          |
          |default:
          |  form:
          |    access: authenticated
          |    stayOnError: true
          |
          |# SERVICE
          |
          |## BookEditor
          |
          |##### WEB
          |
          |- form :: true
          |
          |### OPERATION
          |
          |#### searchBook
          |
          |- type :: QUERY
          |- input :: SearchBook
          |- output :: Result
          |- web.access :: anonymous
          |
          |#### saveBook
          |
          |- type :: COMMAND
          |- input :: SaveBook
          |- output :: Result
          |- web.successRedirect :: /web/books/${result.id}
          |
          |#### internalBook
          |
          |- type :: COMMAND
          |- input :: SaveBook
          |- output :: Result
          |- web.form :: false
          |
          |# COMMAND
          |
          |## SaveBook
          |
          |### Attribute
          |
          || name | type | multiplicity |
          || ---- | ---- | ------------ |
          || informationId | string | one |
          || reason | string | zero-one |
          |
          |#### informationId
          |
          |- web.control :: hidden
          |
          |#### reason
          |
          |- web.control :: textarea
          |
          |# QUERY
          |
          |## SearchBook
          |
          |### Attribute
          |
          || name | type | multiplicity |
          || ---- | ---- | ------------ |
          || query | string | zero-one |
          |""".stripMargin
      )

      CozyWebDescriptorSync.sync(
        projectdir = dir,
        componentname = "sample-editor",
        cozyfiles = Seq(cml),
        log = sbt.util.Logger.Null
      )

      val descriptor = IO.read(dir / "src" / "main" / "web-inf" / "form.yaml")

      assert(descriptor.contains("default:"))
      assert(descriptor.contains("access: authenticated"))
      assert(descriptor.contains("sample-editor.book-editor.search-book:"))
      assert(descriptor.contains("access: anonymous"))
      assert(descriptor.contains("sample-editor.book-editor.save-book:"))
      assert(descriptor.contains("successRedirect: /web/books/${result.id}"))
      assert(descriptor.contains("informationId:"))
      assert(descriptor.contains("type: hidden"))
      assert(descriptor.contains("reason:"))
      assert(descriptor.contains("type: textarea"))
      assert(!descriptor.contains("sample-editor.book-editor.internal-book:"))
    }
  }

  test("replaces previously generated form descriptor block when CML changes") {
    withTempDir("sbt-cozy-web-descriptor-resync") { dir =>
      val cml = write(
        dir / "src" / "main" / "cozy" / "sample.cml",
        """Sample Component
          |================
          |
          |# SERVICE
          |
          |## BookEditor
          |
          |### OPERATION
          |
          |#### saveBook
          |
          |- type :: COMMAND
          |- input :: SaveBook
          |- output :: Result
          |- web.form :: true
          |- web.access :: authenticated
          |
          |# COMMAND
          |
          |## SaveBook
          |
          |### Attribute
          |
          || name | type | multiplicity |
          || ---- | ---- | ------------ |
          || note | string | zero-one |
          |""".stripMargin
      )
      write(
        dir / "src" / "main" / "web-inf" / "form.yaml",
        """form:
          |  sample-editor.book-editor.save-book:
          |    # generated by sbt-cozy from CML WEB metadata
          |    access: anonymous
          |    controls:
          |      old:
          |        type: text
          |        required: false
          |""".stripMargin
      )

      CozyWebDescriptorSync.sync(
        projectdir = dir,
        componentname = "sample-editor",
        cozyfiles = Seq(cml),
        log = sbt.util.Logger.Null
      )

      val descriptor = IO.read(dir / "src" / "main" / "web-inf" / "form.yaml")

      assert(descriptor.contains("access: authenticated"))
      assert(descriptor.contains("note:"))
      assert(!descriptor.contains("access: anonymous"))
      assert(!descriptor.contains("old:"))
    }
  }

  test("service form opt-in keeps existing hand-tuned entries until operation is explicitly generated") {
    withTempDir("sbt-cozy-web-descriptor-service-transition") { dir =>
      val cml = write(
        dir / "src" / "main" / "cozy" / "sample.cml",
        """Sample Component
          |================
          |
          |# SERVICE
          |
          |## BookEditor
          |
          |##### WEB
          |
          |- form :: true
          |
          |### OPERATION
          |
          |#### searchBook
          |
          |- type :: QUERY
          |- input :: SearchBook
          |- output :: Result
          |
          |#### saveBook
          |
          |- type :: COMMAND
          |- input :: SaveBook
          |- output :: Result
          |- web.form :: true
          |- web.access :: authenticated
          |
          |# COMMAND
          |
          |## SaveBook
          |
          |### Attribute
          |
          || name | type | multiplicity |
          || ---- | ---- | ------------ |
          || informationId | string | one |
          |# QUERY
          |
          |## SearchBook
          |
          |### Attribute
          |
          || name | type | multiplicity |
          || ---- | ---- | ------------ |
          || query | string | zero-one |
          |""".stripMargin
      )
      write(
        dir / "src" / "main" / "web-inf" / "form.yaml",
        """form:
          |  sample-editor.book-editor.search-book:
          |    successRedirect: /web/custom-search
          |    controls:
          |      query:
          |        type: text
          |        required: false
          |""".stripMargin
      )

      CozyWebDescriptorSync.sync(
        projectdir = dir,
        componentname = "sample-editor",
        cozyfiles = Seq(cml),
        log = sbt.util.Logger.Null
      )

      val descriptor = IO.read(dir / "src" / "main" / "web-inf" / "form.yaml")

      assert(descriptor.contains("sample-editor.book-editor.search-book:"))
      assert(descriptor.contains("successRedirect: /web/custom-search"))
      assert(!descriptor.contains("sample-editor.book-editor.search-book:\n    # generated"))
      assert(descriptor.contains("sample-editor.book-editor.save-book:\n    # generated by sbt-cozy from CML WEB metadata"))
    }
  }

  test("updates descriptor default even when no operation blocks are generated") {
    withTempDir("sbt-cozy-web-descriptor-default-only") { dir =>
      val cml = write(
        dir / "src" / "main" / "cozy" / "sample.cml",
        """Sample Component
          |================
          |
          |# WEB
          |
          |default:
          |  form:
          |    access: authenticated
          |
          |# SERVICE
          |
          |## BookEditor
          |
          |##### WEB
          |
          |- form :: true
          |
          |### OPERATION
          |
          |#### searchBook
          |
          |- type :: QUERY
          |- input :: SearchBook
          |- output :: Result
          |
          |# QUERY
          |
          |## SearchBook
          |
          |### Attribute
          |
          || name | type | multiplicity |
          || ---- | ---- | ------------ |
          || query | string | zero-one |
          |""".stripMargin
      )
      write(
        dir / "src" / "main" / "web-inf" / "form.yaml",
        """form:
          |  sample-editor.book-editor.search-book:
          |    successRedirect: /web/custom-search
          |    controls:
          |      query:
          |        type: text
          |        required: false
          |""".stripMargin
      )

      CozyWebDescriptorSync.sync(
        projectdir = dir,
        componentname = "sample-editor",
        cozyfiles = Seq(cml),
        log = sbt.util.Logger.Null
      )

      val descriptor = IO.read(dir / "src" / "main" / "web-inf" / "form.yaml")

      assert(descriptor.contains("default:\n  form:\n    access: authenticated"))
      assert(descriptor.contains("successRedirect: /web/custom-search"))
      assert(!descriptor.contains("sample-editor.book-editor.search-book:\n    # generated"))
    }
  }

  test("replaces stale descriptor default with CML WEB default") {
    withTempDir("sbt-cozy-web-descriptor-default-replace") { dir =>
      val cml = write(
        dir / "src" / "main" / "cozy" / "sample.cml",
        """Sample Component
          |================
          |
          |# WEB
          |
          |default:
          |  form:
          |    access: authenticated
          |    stayOnError: true
          |""".stripMargin
      )
      write(
        dir / "src" / "main" / "web-inf" / "form.yaml",
        """default:
          |  form:
          |    access: anonymous
          |form:
          |  sample-editor.book-editor.search-book:
          |    successRedirect: /web/custom-search
          |""".stripMargin
      )

      CozyWebDescriptorSync.sync(
        projectdir = dir,
        componentname = "sample-editor",
        cozyfiles = Seq(cml),
        log = sbt.util.Logger.Null
      )

      val descriptor = IO.read(dir / "src" / "main" / "web-inf" / "form.yaml")

      assert(descriptor.contains("default:\n  form:\n    access: authenticated\n    stayOnError: true"))
      assert(!descriptor.contains("access: anonymous"))
      assert(descriptor.contains("sample-editor.book-editor.search-book:"))
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

    assert(CozyPlugin.publicationPath(config, projectmetadata).contains("textus/tutorial/textus-tutorial"))
    assert(CozyPlugin.publicationPath(configwithpath, projectmetadata).contains("textus/tutorial/custom"))
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

      assert(CozyPlugin.localRepositoryDir(dir, config, home) == dir / "target" / "local-cncf-repository")
      assert(CozyPlugin.localRepositoryDir(dir, cncfconfig, home) == dir / "target" / "cncf-repository")
      assert(CozyPlugin.localRepositoryDir(dir, CozyProjectConfig.empty, home) == home / ".cncf" / "local")
    }
  }

  test("renders planned sample archives as a compact tree") {
    withTempDir() { warehouse =>
      val root = warehouse / "repository" / "download" / "textus" / "tutorial" / "textus-tutorial"
      val files = Seq(
        root / "0.1.0" / "textus-tutorial-0.1.0.zip",
        root / "0.1.0" / "01-minimal" / "01-minimal-0.1.0.zip"
      )

      assert(CozyPlugin.sampleArchiveTreeLines(warehouse, root, files) == Seq(
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
      val savedir = dir / "out"
      val (cwd, resolved) = CozySbtBridge.resolveForTest(
        basedir = dir,
        delegateprojectdir = None,
        delegatecommand = Seq("cozy"),
        action = "generate",
        arguments = Vector("modeler-scala", source.getAbsolutePath, "--save", savedir.getAbsolutePath)
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assertBridgeRequestArgument(resolved)
    }
  }

  test("bridge request keeps explicit generation version override settings") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "generate",
      arguments = Vector("modeler-scala", "/tmp/model.cml", "--save", "/tmp/out"),
      settings = Map("generation.versions.cncf" -> "0.4.11")
    )

    assert(json.contains(""""settings": {"generation.versions.cncf": "0.4.11"}"""))
  }

  test("generate bridge request includes sbt project directory with explicit overrides") {
    withTempDir("sbt-cozy-delegate") { dir =>
      val source = write(dir / "src" / "model.cml", "package app\nentity User\n")
      val savedir = dir / "out"
      val execution = CozySbtBridge.resolveGenerate(
        basedir = dir,
        explicitProjectDir = None,
        delegatecommand = Seq("cozy"),
        source = source,
        savedir = savedir,
        settings = Map(
          "generation.versions.cncf" -> "0.4.11",
          "sbt.project_dir" -> "/tmp/wrong-project"
        )
      )
      val json = IO.read(file(execution.command.last))

      assert(json.contains(""""generation.versions.cncf": "0.4.11""""))
      assert(json.contains(s""""sbt.project_dir": "${dir.getAbsoluteFile.toPath.normalize.toString}""""))
      assert(!json.contains("/tmp/wrong-project"))
    }
  }

  test("bridge can use explicit coursier version through cozy launcher during development") {
    withTempDir("sbt-cozy-delegate") { dir =>
      val command = CozySbtBridge.coursierCommand("0.2.17-SNAPSHOT")
      val (cwd, resolved) = CozySbtBridge.resolveForTest(
        basedir = dir,
        delegateprojectdir = None,
        delegatecommand = command,
        action = "generate",
        arguments = Vector("modeler-scala", "/tmp/model.cml", "--save", "/tmp/out")
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.take(2) == Seq("cs", "launch"))
      assert(resolved.contains("--channel"))
      assert(resolved.contains("https://www.simplemodeling.org/repository/cozy/coursier-channel.json"))
      assert(resolved.contains("cozy"))
      assert(resolved.contains("--runtime"))
      assert(resolved.contains("0.2.17-SNAPSHOT"))
      assert(resolved.contains("--"))
      assert(resolved.takeRight(4).take(3) == Seq("sbt-bridge", "v1", "--request"))
      assertBridgeRequestArgument(resolved)
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
        arguments = Vector(dir.getAbsolutePath, "--save", out.getAbsolutePath, "--kind", "sample-single")
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assertBridgeRequestArgument(resolved)
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
        arguments = Vector("--save", "/tmp/sample.sar")
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

    val result = CozyManifestMetadata.from(metadata, "sample", "0.1.0")

    assert(result.component == "sample-component")
    assert(result.config.isEmpty)
    assert(result.extensions.get("boundedContext").contains("default"))
    assert(result.extensions.get("componentlets").isEmpty)
    val descriptor = result.extensions.getOrElse("componentDescriptorJson", fail("missing descriptor JSON"))
    assert(descriptor.contains("\"component\":{\"name\":\"sample-component\",\"version\":\"0.1.0\",\"boundedContext\":\"default\"}"))
    assert(descriptor.contains("\"componentlets\":["))
    assert(descriptor.contains("\"name\":\"notice-admin\""))
    assert(descriptor.contains("\"name\":\"public-notice\""))
    assert(descriptor.contains("\"kind\":\"componentlet\""))
    assert(descriptor.contains("\"isPrimary\":\"false\""))
  }

  test("component descriptor metadata includes the build version") {
    val metadata = CozyManifestMetadata.from(
      Map("component" -> "textus-ai-runtime"),
      defaultcomponent = "default-component",
      version = "0.2.0-SNAPSHOT"
    )

    val descriptor = metadata.extensions("componentDescriptorJson")
    assert(descriptor.contains("\"name\":\"textus-ai-runtime\""))
    assert(descriptor.contains("\"version\":\"0.2.0-SNAPSHOT\""))
  }

  test("component descriptor metadata does not accept version override from passthrough metadata") {
    val metadata = CozyManifestMetadata.from(
      Map(
        "component" -> "textus-ai-runtime",
        "version" -> "0.1.0"
      ),
      defaultcomponent = "default-component",
      version = "0.2.0-SNAPSHOT"
    )

    val descriptor = metadata.extensions("componentDescriptorJson")
    assert(descriptor.contains("\"version\":\"0.2.0-SNAPSHOT\""))
    assert(!descriptor.contains("\"version\":\"0.1.0\""))
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
          "--save",
          archive.getAbsolutePath,
          "--source-dir",
          (dir / "src").getAbsolutePath,
          "--source-files",
          "subsystem-descriptor.yaml"
        )
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assertBridgeRequestArgument(resolved)
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
          "--warehouse",
          warehouse.getAbsolutePath,
          "--name",
          "sample-component",
          "--version",
          "0.1.0",
          "--car",
          archive.getAbsolutePath
        )
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assertBridgeRequestArgument(resolved)
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
          "--warehouse",
          warehouse.getAbsolutePath,
          "--name",
          "sample-subsystem",
          "--version",
          "0.1.0",
          "--sar",
          archive.getAbsolutePath
        )
      )
      assert(cwd.getAbsolutePath == dir.getAbsolutePath)
      assert(resolved.head == "cozy")
      assert(resolved.drop(1).take(2) == Seq("sbt-bridge", "v1"))
      assertBridgeRequestArgument(resolved)
    }
  }
}
