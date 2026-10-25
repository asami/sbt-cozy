package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import sbt._
import sbt.Keys.baseDirectory
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import CozyPlugin.autoImport._

/** Shared project configuration with local overrides.
  * @since   Oct. 23, 2026
  * @version Oct. 25, 2026
  */
final class CozyProjectConfigLayersSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Project configuration layers" should {
    "load shared settings through the actual plugin settings and overlay local defaults" in {
      _with_project { root =>
        Given("shared defaults and local overrides for one scalar and one list")
        val shared = _write(root.resolve("conf/cozy/config.yaml"), "generation:\n  backend: shared\n  source_dir: src/shared\nrepositories:\n  car:\n    - shared-a\n    - shared-b\n")
        val local = _write(root.resolve(".cozy/config.yaml"), "generation:\n  backend: local\nrepositories:\n  car:\n    - local-only\n")
        When("the plugin's production config settings are evaluated")
        val (config, sources) = _settings(root)
        Then("local values replace matching shared defaults and diagnostics list both sources in order")
        config.value("generation.backend") shouldBe Some("local")
        config.value("generation.source_dir") shouldBe Some("src/shared")
        config.list("repositories.car") shouldBe Seq("local-only")
        sources shouldBe Seq(shared, local)
      }
    }

    "support shared-only, local-only and absent settings" in {
      _with_project { root =>
        Given("three projects with only shared, only local, or no config files")
        val shared = root.resolve("shared")
        val local = root.resolve("local")
        val absent = root.resolve("absent")
        _write(shared.resolve("conf/cozy/config.yaml"), "generation:\n  backend: shared\n")
        _write(local.resolve(".cozy/config.yaml"), "generation:\n  backend: local\n")
        Files.createDirectories(absent)
        _write(absent.resolve("config.yaml"), "generation:\n  backend: unrelated\n")
        Vector(shared -> Some("shared"), local -> Some("local"), absent -> None).foreach { case (path, expected) =>
          When("the project's production settings are evaluated")
          val (config, sources) = _settings(path)
          Then("existing local-only behavior remains and either missing layer is optional")
          config.value("generation.backend") shouldBe expected
          sources.size shouldBe expected.size
        }
      }
    }

    "retain a customized local file selector" in {
      _with_project { root =>
        Given("a shared config, a default local config, and an explicitly selected local config")
        val shared = _write(root.resolve("conf/cozy/config.yaml"), "generation:\n  backend: shared\n  source_dir: retained\n")
        _write(root.resolve(".cozy/config.yaml"), "generation:\n  backend: ignored\n")
        val selected = _write(root.resolve("private/settings.yaml"), "generation:\n  backend: selected\n")
        When("cozyConfigFile selects the custom local path")
        val (config, sources) = _settings(root, Some(selected))
        Then("the selection stays strongest while shared defaults are inherited")
        config.value("generation.backend") shouldBe Some("selected")
        config.value("generation.source_dir") shouldBe Some("retained")
        sources shouldBe Seq(shared, selected)
      }
    }

    "replace a list with an explicitly empty list" in {
      Given("shared repositories and a local empty list")
      val shared = CozyProjectConfig.parse(Seq("repositories:", "  car:", "    - shared"))
      val local = CozyProjectConfig.parse(Seq("repositories:", "  car: []"))
      When("the local layer overlays the shared layer")
      val config = shared.merge(local)
      Then("the shared list is cleared and the empty selection remains authored")
      config.list("repositories.car") shouldBe empty
      config.isAuthored("repositories.car") shouldBe true
    }

    "inherit shared siblings when a local map retains an authored marker" in {
      Given("shared runtime update options and one local option in an authored map")
      val shared = CozyProjectConfig.parse(Seq("publication:", "  local_application_update:",
        "    enabled: true", "    registry: shared-registry"))
      val local = CozyProjectConfig.parse(Seq("publication:", "  local_application_update:",
        "    registry: local-registry"))
      When("the local map overlays the shared map")
      val config = shared.merge(local)
      Then("the local option wins while its unspecified shared sibling survives")
      config.boolean("publication.local_application_update.enabled") shouldBe Some(true)
      config.value("publication.local_application_update.registry") shouldBe Some("local-registry")
    }

    "retain authored empty identity overrides for existing validation" in {
      Given("a shared project ID and an explicitly empty local ID")
      val shared = CozyProjectConfig.parse(Seq("project:", "  id: shared"))
      val local = CozyProjectConfig.parse(Seq("project:", "  id:"))
      When("local settings override shared settings")
      val config = shared.merge(local)
      Then("the empty override does not silently restore the shared ID")
      config.value("project.id") shouldBe None
      config.isAuthored("project.id") shouldBe true
    }
  }

  private def _settings(root: Path, local: Option[File] = None): (CozyProjectConfig, Seq[File]) = {
    val keys: Set[Def.ScopedKey[_]] = Set(cozySharedConfigFile.scopedKey, cozyConfigFile.scopedKey,
      cozyConfigSources.scopedKey, cozyProjectConfig.scopedKey)
    val production = CozyPlugin.projectSettings.filter(setting => keys.contains(setting.key))
    production.size shouldBe keys.size
    val overrides: Seq[Def.Setting[_]] = local.toSeq.map(file => cozyConfigFile := file)
    val settings: Seq[Def.Setting[_]] = production ++ Seq(baseDirectory := root.toFile) ++ overrides
    val initialized = Def.makeWithCompiledMap(settings)(scope => Seq(scope), _ => Nil, Def.showFullKey)._2
    val configkey = cozyProjectConfig.scopedKey
    val sourceskey = cozyConfigSources.scopedKey
    (initialized.get(configkey.scope, configkey.key).get,
      initialized.get(sourceskey.scope, sourceskey.key).get)
  }

  private def _write(path: Path, content: String): File = {
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8)).toFile
  }

  private def _with_project(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("sbt-cozy-config-layers-")
    try body(root)
    finally {
      val stream = Files.walk(root)
      try stream.iterator.asScala.toVector.reverse.foreach(Files.delete(_))
      finally stream.close()
    }
  }
}
