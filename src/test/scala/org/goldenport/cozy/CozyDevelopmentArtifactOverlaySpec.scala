package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import io.circe.Json
import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._

/*
 * @since   Nov. 12, 2026
 * @version Nov. 12, 2026
 */
final class CozyDevelopmentArtifactOverlaySpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private def _write(path: Path, json: Json): Unit =
    Files.write(path, json.noSpaces.getBytes(StandardCharsets.UTF_8))

  private def _input(root: Path, scope: String): Json = Json.obj(
    "schema" -> Json.fromString("cncf.scoped-sbt-input.v1"), "kind" -> Json.fromString("consumer"),
    "component" -> Json.fromString("example"), "buildWorkspace" -> Json.fromString("build"),
    "ownerWorkspace" -> Json.fromString("owner"), "scope" -> Json.fromString(scope),
    "selection" -> Json.fromString("selection"),
    "cozyArtifact" -> Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact.v1"),
      "component" -> Json.fromString("example"), "buildWorkspace" -> Json.fromString("build"),
      "ownerWorkspace" -> Json.fromString("owner"), "scope" -> Json.fromString(scope), "selection" -> Json.fromString("selection"),
      "buildRoot" -> Json.fromString(root.toString), "ownerRoot" -> Json.fromString(root.toString),
      "repositoryRoot" -> Json.fromString(root.resolve("repo").toString),
      "resultFile" -> Json.fromString(root.resolve("result.json").toString), "mappings" -> Json.arr()))

  "The explicit artifact input" should {
    "reject a direct dependency with the wrong requested revision" in {
      Given("a mapping for one exact cross-versioned coordinate")
      val requested = CozyDevelopmentArtifactOverlay.Coordinate("example", "api_2.12", "1.0")
      val selected = requested.copy(version = "1.0-smdev-scope-pub")
      val mapping = CozyDevelopmentArtifactOverlay.Mapping(requested, selected, java.nio.file.Paths.get("/jar"), java.nio.file.Paths.get("/ivy"))
      When("a direct dependency asks for another revision")
      val dependency = ModuleID("example", "api", "2.0").withCrossVersion(CrossVersion.binary)
      Then("selection fails instead of silently rewriting that revision")
      intercept[RuntimeException] {
        CozyDevelopmentArtifactOverlay.selectDependency(dependency, Seq(mapping), "2.12.20", "2.12")
      }.getMessage should include ("revision")
    }
    "reject effective overrides that conflict with the selected coordinate" in {
      Given("a declaration and a private selection for an exact cross module")
      val root = Files.createTempDirectory("cozy-overlay-settings").toRealPath()
      val requested = CozyDevelopmentArtifactOverlay.Coordinate("example", "api_2.12", "1.0")
      val selected = requested.copy(version = "1.0-smdev-1-s-1-p")
      val mapping = CozyDevelopmentArtifactOverlay.Mapping(requested, selected, root.resolve("api.jar"), root.resolve("ivy.xml"))
      val input = CozyDevelopmentArtifactOverlay.Input("consumer", "s", "selection", None,
        root, root, root.resolve("repo"), None, root.resolve("result.json"), Vector(mapping), None)
      val expected = Seq(ModuleID("example", "api", "1.0").withCrossVersion(CrossVersion.binary))
      val privateresolver = Resolver.file("sm-artifact-s", root.resolve("repo").toFile)(Resolver.ivyStylePatterns)
      val selectedoverride = ModuleID("example", "api_2.12", selected.version)
      When("an author supplies a conflicting override or removes the private resolver")
      Then("both cases fail instead of silently changing the author's settings")
      intercept[RuntimeException] {
        CozyDevelopmentArtifactOverlay.validateSettings(input, expected,
          Seq(selectedoverride, ModuleID("example", "api_2.12", "other")), Seq(privateresolver), "2.12.20", "2.12")
      }
      intercept[RuntimeException] {
        CozyDevelopmentArtifactOverlay.validateSettings(input, expected, Seq(selectedoverride), Seq.empty, "2.12.20", "2.12")
      }
    }

    "preserve all valid generated scope identities" in {
      Given("a canonical owner, build and private repository")
      val root = Files.createTempDirectory("cozy-overlay-schema").toRealPath()
      Files.createDirectory(root.resolve("repo"))
      val file = root.resolve("input.json")
      val generator = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString.take(64))
      When("valid scope values are projected through the JSON contract")
      val property = Prop.forAll(generator) { value =>
        _write(file, _input(root, value))
        CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile).scope == value
      }
      Then("the parser preserves each exact scope")
      Test.check(Test.Parameters.default.withMinSuccessfulTests(100), property).passed shouldBe true
    }

    "reject malformed, foreign and unavailable explicit input" in {
      Given("a valid input and an owner root")
      val root = Files.createTempDirectory("cozy-overlay-invalid").toRealPath()
      Files.createDirectory(root.resolve("repo"))
      val file = root.resolve("input.json")
      When("scope, build root or repository are invalid")
      val inputs = Vector(
        _input(root, "../foreign"),
        _input(root, "scope").mapObject(_.remove("cozyArtifact")),
        _input(root, "scope").deepMerge(Json.obj("cozyArtifact" -> Json.obj("buildRoot" -> Json.fromString(root.resolve("elsewhere").toString)))),
        _input(root, "scope").deepMerge(Json.obj("cozyArtifact" -> Json.obj("repositoryRoot" -> Json.fromString(root.resolve("missing").toString))))
      )
      Then("each input fails without an ordinary-build fallback")
      inputs.foreach { json =>
        _write(file, json)
        intercept[RuntimeException](CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile))
      }
    }
  }
}
