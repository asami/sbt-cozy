package org.goldenport.cozy

import java.nio.file.Files
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._

/*
 * @since   Jul. 12, 2026
 * @version Jul. 12, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyDelegatedGeneratorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Cozy delegated generation" should {
    "install the component API descriptor as a target side output" in {
      Given("a delegated generator work directory containing a component API descriptor")
      _with_temp_dir("sbt-cozy-component-api-descriptor") { dir =>
        val workdir = dir / "work"
        val targetdir = dir / "target"
        val source = _write(
          workdir / "run-0" / "target" / "cozy" / "component-api-descriptor.json",
          "{\"schemaVersion\":\"cncf.component-api.v1\"}\n"
        )

        When("the delegated output is installed")
        CozyDelegatedGenerator.installComponentApiDescriptor(workdir, targetdir)

        Then("the descriptor is copied without modification")
        val installed = targetdir / "cozy" / "component-api-descriptor.json"
        installed.isFile shouldBe true
        IO.read(installed) shouldBe IO.read(source)
      }
    }

    "remove a stale component API descriptor when the model no longer publishes one" in {
      Given("a stale target descriptor and delegated output without a descriptor")
      _with_temp_dir("sbt-cozy-stale-component-api-descriptor") { dir =>
        val workdir = dir / "work"
        val targetdir = dir / "target"
        val stale = _write(targetdir / "cozy" / "component-api-descriptor.json", "{}\n")

        When("the delegated output is installed")
        CozyDelegatedGenerator.installComponentApiDescriptor(workdir, targetdir)

        Then("the stale descriptor is removed")
        stale.exists() shouldBe false
      }
    }

    "use the direct Cozy command by default" in {
      Given("a generation request without a development project override")
      _with_temp_dir("sbt-cozy-delegate") { dir =>
        val source = _write(dir / "src" / "model.cml", "package app\nentity User\n")
        val savedir = dir / "out"

        When("the bridge command is resolved")
        val (cwd, resolved) = CozySbtBridge.resolveForTest(
          basedir = dir,
          delegateprojectdir = None,
          delegatecommand = Seq("cozy"),
          action = "generate",
          arguments = Vector("modeler-scala", source.getAbsolutePath, "--save", savedir.getAbsolutePath)
        )

        Then("the direct command delegates through the v1 bridge")
        cwd.getAbsolutePath shouldBe dir.getAbsolutePath
        resolved.head shouldBe "cozy"
        resolved.drop(1).take(2) shouldBe Seq("sbt-bridge", "v1")
        _assert_bridge_request_argument(resolved)
      }
    }

    "keep explicit generation version override settings in the bridge request" in {
      Given("an explicit CNCF generation version")
      val settings = Map("generation.versions.cncf" -> "0.4.11")

      When("the request JSON is rendered")
      val json = CozySbtBridge.renderRequestJsonForTest(
        action = "generate",
        arguments = Vector("modeler-scala", "/tmp/model.cml", "--save", "/tmp/out"),
        settings = settings
      )

      Then("the override remains in the machine-readable request")
      json should include(""""settings": {"generation.versions.cncf": "0.4.11"}""")
    }

    "include the sbt project directory with explicit generation overrides" in {
      Given("a generation request whose supplied project setting is stale")
      _with_temp_dir("sbt-cozy-delegate") { dir =>
        val source = _write(dir / "src" / "model.cml", "package app\nentity User\n")
        val savedir = dir / "out"

        When("the bridge request is resolved from the actual project")
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

        Then("the actual project directory replaces the stale setting")
        json should include(""""generation.versions.cncf": "0.4.11"""")
        json should include(s""""sbt.project_dir": "${dir.getAbsoluteFile.toPath.normalize.toString}"""")
        json should not include "/tmp/wrong-project"
      }
    }

    "use an explicit Cozy runtime through the launcher during development" in {
      Given("a development runtime version")
      _with_temp_dir("sbt-cozy-delegate") { dir =>
        val command = CozySbtBridge.coursierCommand("0.2.17-SNAPSHOT")

        When("the bridge command is resolved")
        val (cwd, resolved) = CozySbtBridge.resolveForTest(
          basedir = dir,
          delegateprojectdir = None,
          delegatecommand = command,
          action = "generate",
          arguments = Vector("modeler-scala", "/tmp/model.cml", "--save", "/tmp/out")
        )

        Then("the launcher command keeps the runtime and bridge arguments")
        cwd.getAbsolutePath shouldBe dir.getAbsolutePath
        resolved.take(2) shouldBe Seq("cs", "launch")
        resolved should contain allOf ("--channel", "cozy", "--runtime", "0.2.17-SNAPSHOT", "--")
        resolved should contain("https://www.simplemodeling.org/repository/cozy/coursier-channel.json")
        resolved.takeRight(4).take(3) shouldBe Seq("sbt-bridge", "v1", "--request")
        _assert_bridge_request_argument(resolved)
      }
    }

    "delegate publish-project requests through the bridge" in {
      Given("a project publication request")
      _with_temp_dir("sbt-cozy-publish-project-delegate") { dir =>
        val out = dir / "publish.d"

        When("the bridge command is resolved")
        val (cwd, resolved) = CozySbtBridge.resolveForTest(
          basedir = dir,
          delegateprojectdir = None,
          delegatecommand = Seq("cozy"),
          action = "publish-project",
          arguments = Vector(dir.getAbsolutePath, "--save", out.getAbsolutePath, "--kind", "sample-single")
        )

        Then("the request uses the direct v1 bridge")
        cwd.getAbsolutePath shouldBe dir.getAbsolutePath
        resolved.head shouldBe "cozy"
        resolved.drop(1).take(2) shouldBe Seq("sbt-bridge", "v1")
        _assert_bridge_request_argument(resolved)
      }
    }

    "use an explicit Cozy project during development" in {
      Given("a local Cozy project override")
      _with_temp_dir("sbt-cozy-delegate") { dir =>
        val cozydir = dir / "cozy"
        _write(cozydir / "build.sbt", """name := "cozy"""")

        When("the bridge command is resolved")
        val (cwd, resolved) = CozySbtBridge.resolveForTest(
          basedir = dir,
          delegateprojectdir = Some(cozydir),
          delegatecommand = Seq("cozy"),
          action = "package-sar",
          arguments = Vector("--save", "/tmp/sample.sar")
        )

        Then("sbt runs the local Cozy main class")
        cwd.getAbsolutePath shouldBe cozydir.getAbsolutePath
        resolved.take(4) shouldBe Seq("sbt", "--batch", "-Dsbt.server.autostart=false", "-Dsbt.supershell=false")
        resolved.last should startWith("runMain cozy.Cozy ")
      }
    }

    "delegate component API JAR packaging through the machine bridge" in {
      Given("a generated component JAR and API descriptor")
      _with_temp_dir("sbt-cozy-component-api-jar-delegate") { dir =>
        val output = dir / "target" / "example-api.jar"
        val mainjar = _write(dir / "target" / "example.jar", "jar")
        val descriptor = _write(dir / "target" / "component-api-descriptor.json", "{}")

        When("the bridge execution is resolved")
        val (_, resolved) = CozySbtBridge.resolveForTest(
          basedir = dir,
          delegateprojectdir = None,
          delegatecommand = Seq("cozy"),
          action = "component-api-jar",
          arguments = Vector(
            "--save", output.getAbsolutePath,
            "--main-jar", mainjar.getAbsolutePath,
            "--descriptor", descriptor.getAbsolutePath
          )
        )
        val request = IO.read(file(resolved.last))

        Then("the structured request keeps the action and all artifact paths")
        request should include("\"action\": \"component-api-jar\"")
        request should include(output.getAbsolutePath)
        request should include(mainjar.getAbsolutePath)
        request should include(descriptor.getAbsolutePath)
      }
    }

    "delegate component API dependency matching through the machine bridge" in {
      Given("a consumer descriptor and one resolved dependency CAR")
      _with_temp_dir("sbt-cozy-component-api-dependency-delegate") { dir =>
        val consumer = _write(dir / "consumer.json", "{}")
        val archive = _write(dir / "provider.car", "car")
        val outputdir = dir / "resolved"

        When("the structured bridge request is rendered")
        val (_, resolved) = CozySbtBridge.resolveForTest(
          basedir = dir,
          delegateprojectdir = None,
          delegatecommand = Seq("cozy"),
          action = "resolve-component-api-dependencies",
          arguments = Vector(
            "--consumer-descriptor", consumer.getAbsolutePath,
            "--output-dir", outputdir.getAbsolutePath,
            "--dependency", s"provider\t0.1.0\t${archive.getAbsolutePath}"
          )
        )
        val request = IO.read(file(resolved.last))

        Then("the request keeps the consumer, output, and exact CAR coordinate")
        request should include("\"action\": \"resolve-component-api-dependencies\"")
        request should include(consumer.getAbsolutePath)
        request should include(outputdir.getAbsolutePath)
        request should include("provider\\t0.1.0\\t")
      }
    }
  }

  private def _with_temp_dir[A](prefix: String)(f: File => A): A = {
    val dir = Files.createTempDirectory(prefix).toFile
    try f(dir)
    finally IO.delete(dir)
  }

  private def _write(path: File, content: String): File = {
    IO.createDirectory(path.getParentFile)
    IO.write(path, content)
    path
  }

  private def _assert_bridge_request_argument(args: Seq[String]): Unit = {
    args.takeRight(2).head shouldBe "--request"
    args.last should endWith(".json")
  }
}
