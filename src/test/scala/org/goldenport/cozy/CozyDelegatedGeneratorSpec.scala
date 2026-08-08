package org.goldenport.cozy

import java.nio.file.Files
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._

/*
 * @since   Jul. 12, 2026
 * @version Aug.  8, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyDelegatedGeneratorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Cozy delegated generation" should {
    "project canonical component generation settings without module fallback" in {
      Given("an admitted canonical CAR project identity")
      val metadata = CozyProjectConfig.parse(Seq(
        "project:",
        "  namespace: org.alpha.textus",
        "  id: Shared",
        "  component:",
        "    displayName: Shared Presentation",
        "    version: 0.6.0-SNAPSHOT"
      ))

      When("delegated generation settings are projected")
      val settings = CarPublicationCoordinate._generation_settings(metadata, "3")

      Then("the bridge receives canonical namespace, ID, and release only")
      settings shouldBe Map(
        "component.namespace" -> "org.alpha.textus",
        "component.id" -> "Shared",
        "component.display-name" -> "Shared Presentation",
        "component.version" -> "0.6.0-SNAPSHOT"
      )
      settings should not contain key("component.module")
    }

    "bind project-owned generation versions" which {
      "derive the exact Cozy generator and CNCF target from project metadata" in {
        Given("project metadata with one exact generator and one CNCF compile dependency")
        val metadata = CozyProjectConfig.parse(Seq(
          "build:",
          "  cozyVersion: 0.3.1-SNAPSHOT",
          "  dependencies:",
          "    compile:",
          "      - org.goldenport::goldenport-cncf:0.5.1"
        ))

        When("sbt-cozy derives generation bridge settings")
        val versions = CozyGenerationVersionContract.projectVersions(metadata)

        Then("both exact versions are represented in generation state")
        versions shouldBe Map(
          "generation.versions.cncf" -> "0.5.1",
          "generation.versions.cozy" -> "0.3.1-SNAPSHOT"
        )
      }

      "reject missing exact generation authorities for a CAR project" in {
        Given("CAR metadata without a Cozy generator or CNCF compile target")
        val metadata = CozyProjectConfig.parse(Seq(
          "project:",
          "  kind: car",
          "packaging:",
          "  kind: car"
        ))

        When("sbt-cozy resolves generation versions and the delegate")
        val versionerror = intercept[RuntimeException] {
          CozyGenerationVersionContract.projectVersions(metadata)
        }
        val delegateerror = intercept[RuntimeException] {
          CozyGenerationVersionContract.delegateVersion(
            metadata,
            Some("0.3.1-SNAPSHOT")
          )
        }

        Then("ambient delegate configuration cannot replace project-owned authority")
        versionerror.getMessage should include(
          "CAR/SAR generation requires project.yaml build.cozyVersion"
        )
        delegateerror.getMessage should include(
          "CAR/SAR delegated generation requires project.yaml build.cozyVersion"
        )
      }

      "reject duplicate CNCF compile dependencies even when their versions agree" in {
        Given("CAR metadata with duplicate exact CNCF compile dependencies")
        val metadata = CozyProjectConfig.parse(Seq(
          "project:",
          "  kind: car",
          "build:",
          "  cozyVersion: 0.3.1-SNAPSHOT",
          "  dependencies:",
          "    compile:",
          "      - org.goldenport::goldenport-cncf:0.5.1",
          "      - org.goldenport:goldenport-cncf_3:0.5.1"
        ))

        When("sbt-cozy derives the project-owned generation pair")
        val error = intercept[RuntimeException] {
          CozyGenerationVersionContract.projectVersions(metadata)
        }

        Then("the duplicate authorities are rejected instead of collapsed by version")
        error.getMessage should include(
          "project.yaml declares multiple CNCF compile dependencies"
        )
        error.getMessage should include(
          "org.goldenport::goldenport-cncf:0.5.1"
        )
        error.getMessage should include(
          "org.goldenport:goldenport-cncf_3:0.5.1"
        )
      }

      "retain explicit delegate configuration for non-CAR library projects" in {
        Given("a Maven library project without CAR generation metadata")
        val metadata = CozyProjectConfig.parse(Seq(
          "project:",
          "  kind: library",
          "packaging:",
          "  kind: maven"
        ))

        When("the non-CAR project resolves optional generation settings")
        val versions = CozyGenerationVersionContract.projectVersions(metadata)
        val delegate = CozyGenerationVersionContract.delegateVersion(
          metadata,
          Some("0.3.1-SNAPSHOT")
        )

        Then("the CAR authority requirement does not change library behavior")
        versions shouldBe empty
        delegate shouldBe Some("0.3.1-SNAPSHOT")
      }

      "reject contradictory project and owning-build bridge versions" in {
        Given("a project-owned generator version and a stale bridge override")
        val metadata = CozyProjectConfig.parse(Seq(
          "build:",
          "  cozyVersion: 0.3.1-SNAPSHOT"
        ))

        When("the generation settings are merged")
        val error = intercept[RuntimeException] {
          CozyGenerationVersionContract.merge(
            metadata,
            Map("generation.versions.cozy" -> "0.3.0")
          )
        }

        Then("sbt-cozy rejects the disagreement before generation or cache reuse")
        error.getMessage should include("exact generation version sources disagree")
        error.getMessage should include("project=0.3.1-SNAPSHOT")
        error.getMessage should include("owning-build-bridge=0.3.0")
      }

      "use the project-owned Cozy version for delegated execution" in {
        Given("a project generator coordinate and a matching or absent local override")
        val metadata = CozyProjectConfig.parse(Seq(
          "build:",
          "  cozyVersion: 0.3.1-SNAPSHOT"
        ))

        When("the delegate version is selected")
        val selected =
          CozyGenerationVersionContract.delegateVersion(metadata, None)
        val matching =
          CozyGenerationVersionContract.delegateVersion(
            metadata,
            Some("0.3.1-SNAPSHOT")
          )

        Then("the exact project coordinate controls delegated execution")
        selected shouldBe Some("0.3.1-SNAPSHOT")
        matching shouldBe selected
      }

      "reject a delegated Cozy version that contradicts project metadata" in {
        Given("a project-owned Cozy version and a different configured delegate")
        val metadata = CozyProjectConfig.parse(Seq(
          "build:",
          "  cozyVersion: 0.3.1-SNAPSHOT"
        ))

        When("sbt-cozy resolves delegated execution")
        val error = intercept[RuntimeException] {
          CozyGenerationVersionContract.delegateVersion(
            metadata,
            Some("0.3.0")
          )
        }

        Then("the stale or substituted generator is rejected before execution")
        error.getMessage should include(
          "Cozy delegate version disagrees with project.yaml"
        )
        error.getMessage should include("project=0.3.1-SNAPSHOT")
        error.getMessage should include("delegate=0.3.0")
      }

      "invalidate generated output reuse when the exact generator changes" in {
        Given("cached generation state for one project-owned Cozy coordinate")
        _with_temp_dir("sbt-cozy-generator-version-state") { dir =>
          val sourcedir = dir / "src"
          val targetdir = dir / "target"
          val statefile = dir / "generation-state.properties"
          val source =
            _write(sourcedir / "model.cml", "package app\nentity User\n")
          val output =
            _write(targetdir / "User.scala", "object User\n")
          val initialsettings =
            Map("generation.versions.cozy" -> "0.3.1-SNAPSHOT")
          val changedsettings =
            Map("generation.versions.cozy" -> "0.3.2-SNAPSHOT")
          val initial = CozyGenerationState.capture(
            sourcedir,
            Seq(source),
            "cozy",
            CozyConfig.default,
            initialsettings
          )
          CozyGenerationState.write(statefile, initial)

          When("the current project selects another exact generator coordinate")
          val changed = CozyGenerationState.capture(
            sourcedir,
            Seq(source),
            "cozy",
            CozyConfig.default,
            changedsettings
          )

          Then("the stale generated output is not reusable")
          CozyGenerationState.isUpToDate(
            statefile,
            initial,
            Seq(output)
          ) shouldBe true
          CozyGenerationState.isUpToDate(
            statefile,
            changed,
            Seq(output)
          ) shouldBe false
        }
      }

      "report the exact unavailable generator and recovery action" in {
        Given("a failed delegated launch for one project-owned Cozy coordinate")
        val source = file("target/sbt-cozy-test/work/cozy-delegated-generator-failure/model.cml")
        val cwd = file("target/sbt-cozy-test/work/cozy-delegated-generator-failure/project")
        val command = Seq(
          "cs", "launch", "cozy", "--", "--runtime", "0.3.1-SNAPSHOT"
        )

        When("sbt-cozy renders the delegated failure")
        val message = CozyDelegatedGenerator.delegateFailureMessage(
          source,
          command,
          cwd,
          1,
          "artifact not found",
          Map(
            CozyGenerationVersionContract.COZY_VERSION_KEY ->
              "0.3.1-SNAPSHOT"
          )
        )

        Then("the diagnostic identifies the exact artifact and manual recovery")
        message should include(
          "generator: org.simplemodeling:cozy_2.12:0.3.1-SNAPSHOT"
        )
        message should include(
          "recovery: publish-or-select-the-exact-project-owned-Cozy-generator-coordinate"
        )
        message should include(command.mkString(" "))
      }
    }

    "manage generated descriptors and model metadata" which {
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

      "install CML model metadata as a CAR packaging side output" in {
        Given("a delegated generator work directory containing model metadata")
        _with_temp_dir("sbt-cozy-model-metadata") { dir =>
          val workdir = dir / "work"
          val targetdir = dir / "target"
          val source = _write(
            workdir / "run-0" / "target" / "cozy" / "model-metadata.json",
            "{\"schema\":\"cozy.cml.model-metadata.v1\"}\n"
          )

          When("the delegated output is installed")
          CozyDelegatedGenerator.installModelMetadata(workdir, targetdir)

          Then("the metadata is copied without modification")
          val installed = targetdir / "cozy" / "model-metadata.json"
          installed.isFile shouldBe true
          IO.read(installed) shouldBe IO.read(source)
        }
      }

      "remove stale CML model metadata when generation no longer publishes it" in {
        Given("a stale target metadata file and delegated output without metadata")
        _with_temp_dir("sbt-cozy-stale-model-metadata") { dir =>
          val workdir = dir / "work"
          val targetdir = dir / "target"
          val stale = _write(targetdir / "cozy" / "model-metadata.json", "{}\n")

          When("the delegated output is installed")
          CozyDelegatedGenerator.installModelMetadata(workdir, targetdir)

          Then("the stale metadata is removed")
          stale.exists() shouldBe false
        }
      }

      "retain multiple generated CML metadata files without selecting one arbitrarily" in {
        Given("two CML generation runs that each publish model metadata")
        _with_temp_dir("sbt-cozy-multiple-model-metadata") { dir =>
          val workdir = dir / "work"
          val targetdir = dir / "target"
          val first = _write(workdir / "run-0" / "target" / "cozy" / "model-metadata.json", "{\"source\":\"first\"}\n")
          val second = _write(workdir / "run-1" / "target" / "cozy" / "model-metadata.json", "{\"source\":\"second\"}\n")

          When("the delegated outputs are installed")
          CozyDelegatedGenerator.installModelMetadata(workdir, targetdir)

          Then("both metadata documents are retained in deterministic order")
          val installed = ((targetdir / "cozy" / "model-metadata") ** "*.json").get.sortBy(_.getName)
          installed.map(x => IO.read(x)) shouldBe Seq(IO.read(first), IO.read(second))
          (targetdir / "cozy" / "model-metadata.json").exists() shouldBe false
        }
      }

      "require the complete model metadata side output before reusing generated Scala" in {
        Given("target output for one CML source and for two CML sources")
        _with_temp_dir("sbt-cozy-model-metadata-reuse") { dir =>
          val targetdir = dir / "target"

          When("the expected metadata output is absent, partial, or complete")
          val absent = CozyDelegatedGenerator.hasModelMetadata(targetdir, 1)
          _write(targetdir / "cozy" / "model-metadata" / "model-001.json", "{}\n")
          val partial = CozyDelegatedGenerator.hasModelMetadata(targetdir, 2)
          _write(targetdir / "cozy" / "model-metadata" / "model-002.json", "{}\n")
          val complete = CozyDelegatedGenerator.hasModelMetadata(targetdir, 2)

          Then("only the complete side-output set is reusable")
          absent shouldBe false
          partial shouldBe false
          complete shouldBe true
        }
      }
    }

    "bind final generation provenance" which {
      "install delegated provenance through the Cozy bridge into the final project target" in {
        Given("one delegated provenance document and an executable bridge delegate")
        _with_temp_dir("sbt-cozy-generation-provenance") { dir =>
          val workdir = dir / "target" / "sbt-cozy" / "delegate-work"
          val targetdir = dir / "target"
          val source = _write(
            workdir / "run-0" / "target" / "cozy" / "generation-provenance.json",
            "{\"schemaVersion\":\"cozy.generation-provenance.v1\"}\n"
          )
          val capturedrequest = dir / "captured-request.json"
          val installed = targetdir / "cozy" / "generation-provenance.json"
          val delegate = _write(
            dir / "fake-cozy",
            s"""#!/bin/sh
               |cp "$$4" "${capturedrequest.getAbsolutePath}"
               |mkdir -p "${installed.getParentFile.getAbsolutePath}"
               |cp "${source.getAbsolutePath}" "${installed.getAbsolutePath}"
               |""".stripMargin
          )
          delegate.setExecutable(true) shouldBe true
          val expectedprovenance = IO.read(source)

          When("sbt-cozy installs final-output side effects")
          CozyDelegatedGenerator.installGenerationProvenance(
            workdir = workdir,
            targetbasedir = targetdir,
            basedir = dir,
            delegateprojectdir = None,
            delegatecommand = Seq(delegate.getAbsolutePath),
            log = sbt.util.Logger.Null
          )

          Then("the bridge receives rebinding roots and the final target owns the result")
          installed.isFile shouldBe true
          IO.read(installed) shouldBe expectedprovenance
          val request = IO.read(capturedrequest)
          request should include("\"action\": \"rebind-generation-provenance\"")
          request should include(source.getAbsolutePath)
          request should include((workdir / "run-0").getAbsolutePath)
          request should include(dir.getAbsolutePath)
          CozyDelegatedGenerator.hasGenerationProvenance(targetdir) shouldBe true
        }
      }

      "reject multiple delegated provenance documents instead of selecting one" in {
        Given("two CNCF-aware CML generation runs for one CAR")
        _with_temp_dir("sbt-cozy-multiple-generation-provenance") { dir =>
          val workdir = dir / "target" / "sbt-cozy" / "delegate-work"
          val targetdir = dir / "target"
          Vector(0, 1).foreach { index =>
            _write(
              workdir / s"run-$index" / "target" / "cozy" / "generation-provenance.json",
              s"""{"run":$index}"""
            )
          }

          When("sbt-cozy attempts to install singular v1 provenance")
          val error = intercept[RuntimeException] {
            CozyDelegatedGenerator.installGenerationProvenance(
              workdir = workdir,
              targetbasedir = targetdir,
              basedir = dir,
              delegateprojectdir = None,
              delegatecommand = Seq("unused"),
              log = sbt.util.Logger.Null
            )
          }

          Then("the source-of-truth ambiguity fails explicitly")
          error.getMessage should include(
            "cozy.generation-provenance.v1 supports one CML source per CAR"
          )
          CozyDelegatedGenerator.hasGenerationProvenance(targetdir) shouldBe false
        }
      }

      "carry provenance through the complete delegated generation lifecycle" in {
        Given("one CML source and a bridge delegate that emits isolated Cozy output")
        _with_temp_dir("sbt-cozy-generation-provenance-lifecycle") { dir =>
          val source = _write(dir / "src" / "main" / "cozy" / "model.cml", "package demo\nentity Model\n")
          val targetbasedir = dir / "target"
          val targetdir = targetbasedir / "scala-2.12" / "src_managed" / "main"
          val workdir = targetbasedir / "sbt-cozy" / "delegate-work"
          val delegatedscala =
            workdir / "run-0" / "target" / "scala-2.12" / "src_managed" / "main" / "scala" / "demo" / "Generated.scala"
          val delegatedprovenance =
            workdir / "run-0" / "target" / "cozy" / "generation-provenance.json"
          val installedprovenance = targetbasedir / "cozy" / "generation-provenance.json"
          val delegate = _write(
            dir / "fake-cozy",
            s"""#!/bin/sh
               |if grep -q '"action": "generate"' "$$4"; then
               |  mkdir -p "${delegatedscala.getParentFile.getAbsolutePath}"
               |  printf '%s\n' 'package demo' 'object Generated' > "${delegatedscala.getAbsolutePath}"
               |  mkdir -p "${delegatedprovenance.getParentFile.getAbsolutePath}"
               |  printf '%s\n' '{"schemaVersion":"cozy.generation-provenance.v1"}' > "${delegatedprovenance.getAbsolutePath}"
               |else
               |  mkdir -p "${installedprovenance.getParentFile.getAbsolutePath}"
               |  cp "${delegatedprovenance.getAbsolutePath}" "${installedprovenance.getAbsolutePath}"
               |fi
               |""".stripMargin
          )
          delegate.setExecutable(true) shouldBe true

          When("the complete sbt-cozy delegated generator runs")
          val generated = CozyDelegatedGenerator.generate(
            sourcedir = source.getParentFile,
            cozyfiles = Seq(source),
            targetdir = targetdir,
            targetbasedir = targetbasedir,
            basedir = dir,
            delegateprojectdir = None,
            delegatecommand = Seq(delegate.getAbsolutePath),
            settings = Map.empty,
            log = sbt.util.Logger.Null
          )

          Then("managed Scala and final provenance remain while delegate work is removed")
          generated should have size 1
          generated.head.isFile shouldBe true
          IO.read(generated.head) should include("object Generated")
          installedprovenance.isFile shouldBe true
          CozyDelegatedGenerator.hasGenerationProvenance(targetbasedir) shouldBe true
          workdir.exists() shouldBe false
        }
      }
    }

    "resolve and execute bridge commands" which {
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
          arguments = Vector("modeler-scala", "target/sbt-cozy-test/work/cozy-delegated-generator/model.cml", "--save", "target/sbt-cozy-test/work/cozy-delegated-generator/out"),
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
              "sbt.project_dir" -> "target/sbt-cozy-test/work/cozy-delegated-generator/wrong-project"
            )
          )
          val json = IO.read(file(execution.command.last))

          Then("the actual project directory replaces the stale setting")
          json should include(""""generation.versions.cncf": "0.4.11"""")
          json should include(s""""sbt.project_dir": "${dir.getAbsoluteFile.toPath.normalize.toString}"""")
          json should not include "target/sbt-cozy-test/work/cozy-delegated-generator/wrong-project"
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
            arguments = Vector("modeler-scala", "target/sbt-cozy-test/work/cozy-delegated-generator/model.cml", "--save", "target/sbt-cozy-test/work/cozy-delegated-generator/out")
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
            arguments = Vector("--save", "target/sbt-cozy-test/work/cozy-delegated-generator/sample.sar")
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
              "--dependency", s"org.example.component\tProvider\t0.1.0\t${archive.getAbsolutePath}"
            )
          )
          val request = IO.read(file(resolved.last))

          Then("the request keeps the consumer, output, and exact CAR coordinate")
          request should include("\"action\": \"resolve-component-api-dependencies\"")
          request should include(consumer.getAbsolutePath)
          request should include(outputdir.getAbsolutePath)
          request should include("org.example.component\\tProvider\\t0.1.0\\t")
        }
      }
    }
  }

  private def _with_temp_dir[A](prefix: String)(f: File => A): A = {
    val parent = file("target/sbt-cozy-test/work/cozy-delegated-generator")
    IO.createDirectory(parent)
    val directory = Files.createTempDirectory(parent.toPath, s"${prefix}-").toFile
    try f(directory)
    finally IO.delete(directory)
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
