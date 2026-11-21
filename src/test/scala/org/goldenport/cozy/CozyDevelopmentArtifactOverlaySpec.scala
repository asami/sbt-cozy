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
 * @version Nov. 19, 2026
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
    "coalesce only generator report rows with the same revision and canonical JARs" in {
      Given("repeated generator coordinates and two distinct resolved JARs")
      val root = Files.createTempDirectory("cozy-generator-rows").toRealPath()
      val first = Files.write(root.resolve("first.jar"), Array[Byte](1)).toFile
      val second = Files.write(root.resolve("second.jar"), Array[Byte](2)).toFile
      val coordinate = CozyDevelopmentArtifactOverlay.Coordinate("example", "generator", "1.0")
      val other = CozyDevelopmentArtifactOverlay.Coordinate("example", "other", "1.0")
      val alias = root.resolve(".").resolve("first.jar").toFile
      When("identical rows repeat around another selected module")
      val rows = Vector(coordinate -> Vector(first), other -> Vector(second), coordinate -> Vector(alias))
      Then("the first encounter order is preserved")
      CozyDevelopmentArtifactOverlay.normalizeGeneratorRows(rows) shouldBe Vector(0, 1)
      When("a repeated name has a different revision or JAR")
      val revision = intercept[RuntimeException] {
        CozyDevelopmentArtifactOverlay.normalizeGeneratorRows(Vector(rows.head, coordinate.copy(version = "2.0") -> Vector(first)))
      }.getMessage
      val artifact = intercept[RuntimeException] {
        CozyDevelopmentArtifactOverlay.normalizeGeneratorRows(Vector(rows.head, coordinate -> Vector(second)))
      }.getMessage
      Then("both conflicts identify the module and their actual revisions and paths")
      revision should include ("example:generator")
      revision should include ("1.0")
      revision should include ("2.0")
      artifact should include (first.getCanonicalPath)
      artifact should include (second.getCanonicalPath)
    }

    "require unique explicit producer projects and coordinates in a v2 batch" in {
      Given("a canonical producer input with a private staging directory")
      val root = Files.createTempDirectory("cozy-overlay-batch-input").toRealPath()
      Files.createDirectory(root.resolve("repo"))
      Files.createDirectory(root.resolve("stage"))
      val file = root.resolve("input.json")
      val coordinate = root.resolve("repo/example/api_2.12/1.0-smdev-1-s-1-p")
      val member = Json.obj("project" -> Json.fromString("api"), "organization" -> Json.fromString("example"),
        "module" -> Json.fromString("api_2.12"), "version" -> Json.fromString("1.0-smdev-1-s-1-p"),
        "jar" -> Json.fromString(coordinate.resolve("jars/api_2.12.jar").toString),
        "ivy" -> Json.fromString(coordinate.resolve("ivys/ivy.xml").toString))
      def _batch_(members: Vector[Json]): Json = _input(root, "s").deepMerge(Json.obj(
        "kind" -> Json.fromString("producer"), "publication" -> Json.fromString("p"),
        "staging" -> Json.fromString("stage"), "repository" -> Json.fromString("repo"),
        "cozyArtifact" -> Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact.v2"),
          "producers" -> Json.arr(members: _*))))
      When("the declaration is empty or repeats a project or coordinate")
      Then("the reader refuses it, while a single explicit project is preserved")
      Vector(Vector.empty, Vector(member, member),
        Vector(member, member.deepMerge(Json.obj("project" -> Json.fromString("other"))))).foreach { members =>
        _write(file, _batch_(members))
        intercept[RuntimeException](CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile))
      }
      _write(file, _batch_(Vector(member)))
      CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile).producers.map(_.project) shouldBe Vector("api")
    }

    "require the complete batch before making any member selectable" in {
      Given("two declared batch members and one consumer mapping")
      val root = Files.createTempDirectory("cozy-overlay-batch").toRealPath()
      val repository = Files.createDirectory(root.resolve("repo"))
      val publication = "p"
      val selectedversion = "1.0-smdev-1-s-1-p"
      def _coordinate_(module: String, version: String): Json = Json.obj(
        "organization" -> Json.fromString("example"), "module" -> Json.fromString(module),
        "version" -> Json.fromString(version))
      def _member_(module: String): (Path, Path, Json) = {
        val base = repository.resolve("example").resolve(module).resolve(selectedversion)
        val jar = base.resolve("jars").resolve(module + ".jar")
        val ivy = base.resolve("ivys").resolve("ivy.xml")
        Files.createDirectories(jar.getParent)
        Files.createDirectories(ivy.getParent)
        Files.write(jar, Array[Byte](1))
        Files.write(ivy, s"<ivy-module version='2.0'><info organisation='example' module='$module' revision='$selectedversion'/><dependencies/></ivy-module>".getBytes(StandardCharsets.UTF_8))
        val entry = _coordinate_(module, selectedversion).deepMerge(Json.obj("dependencies" -> Json.arr(),
          "name" -> Json.fromString(module), "requestedVersion" -> Json.fromString("1.0"),
          "selectedVersion" -> Json.fromString(selectedversion), "baseVersion" -> Json.fromString("1.0"),
          "artifact" -> Json.fromString(jar.toString), "development" -> Json.fromBoolean(true),
          "publication" -> Json.fromString(jar.toString), "control" -> Json.fromString(base.resolve("completed.json").toString)))
        _write(base.resolve("completed.json"), entry.deepMerge(Json.obj(
          "schema" -> Json.fromString("sbt-cozy.development-artifact-publication.v2"),
          "scope" -> Json.fromString("s"), "selection" -> Json.fromString("producer-selection"),
          "publication" -> Json.fromString(publication), "owner" -> Json.fromString("owner"),
          "batch" -> Json.fromString(publication), "producerSelectionId" -> Json.fromString("producer-selection"),
          "complete" -> Json.fromBoolean(true))))
        (jar, ivy, entry)
      }
      val first = _member_("one_2.12")
      val second = _member_("two_2.12")
      val file = root.resolve("input.json")
      val mapping = Json.obj("requested" -> _coordinate_("one_2.12", "1.0"),
        "selected" -> _coordinate_("one_2.12", selectedversion),
        "jar" -> Json.fromString(first._1.toString), "ivy" -> Json.fromString(first._2.toString),
        "publication" -> Json.fromString(publication), "publicationSelection" -> Json.fromString("producer-selection"))
      _write(file, _input(root, "s").deepMerge(Json.obj("cozyArtifact" -> Json.obj("mappings" -> Json.arr(mapping)))))
      When("the common completion record is absent or a different member is missing")
      Then("the consumer rejects the first member even though its own files and marker exist")
      intercept[RuntimeException](CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile))
      val marker = repository.resolve(".batches").resolve("s").resolve(publication).resolve("completed.json")
      Files.createDirectories(marker.getParent)
      _write(marker, Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact-batch.v2"),
        "scope" -> Json.fromString("s"), "selection" -> Json.fromString("producer-selection"),
        "publication" -> Json.fromString(publication), "owner" -> Json.fromString("owner"),
        "batch" -> Json.fromString(publication), "producerSelectionId" -> Json.fromString("producer-selection"),
        "complete" -> Json.fromBoolean(true), "members" -> Json.arr(first._3, second._3)))
      Files.delete(second._1)
      intercept[RuntimeException](CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile))
      Files.write(second._1, Array[Byte](1))
      CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile).mappings.size shouldBe 1
      When("a fixture v3 batch also declares default and classified artifacts")
      def _v3member_(module: String, pair: (Path, Path, Json)): Json = {
        val base = pair._2.getParent.getParent
        val classifier = base.resolve("jars").resolve(module + "-native.jar")
        Files.write(classifier, Array[Byte](2))
        Files.write(pair._2, s"""<ivy-module version='2.0' xmlns:m='http://ant.apache.org/ivy/extra'><info organisation='example' module='$module' revision='$selectedversion'/><publications><artifact name='$module' type='jar' ext='jar'/><artifact name='$module' type='jar' ext='jar' m:classifier='native'/></publications><dependencies/></ivy-module>""".getBytes(StandardCharsets.UTF_8))
        val binding = Json.obj("organization" -> Json.fromString("example"), "name" -> Json.fromString(module),
          "selectedVersion" -> Json.fromString(selectedversion), "baseVersion" -> Json.fromString("1.0"),
          "descriptor" -> Json.fromString(pair._2.toString),
          "control" -> Json.fromString(base.resolve("completed-v3.json").toString))
        def artifact(path: Path, suffix: Option[String]): Json = Json.obj(
          "key" -> Json.obj("module" -> Json.fromString("example:" + module + ":" + selectedversion),
            "name" -> Json.fromString(module), "artifactType" -> Json.fromString("jar"),
            "extension" -> Json.fromString("jar"), "classifier" -> suffix.map(Json.fromString).getOrElse(Json.Null),
            "extraAttributes" -> Json.obj()).dropNullValues, "publicationFile" -> Json.fromString(path.toString))
        val artifacts = Json.arr(artifact(pair._1, None), artifact(classifier, Some("native")))
        _write(base.resolve("completed-v3.json"), Json.obj("scope" -> Json.fromString("s"),
          "owner" -> Json.fromString("owner"), "batch" -> Json.fromString(publication),
          "producerSelectionId" -> Json.fromString("producer-selection"), "complete" -> Json.fromBoolean(true),
          "module" -> binding, "artifacts" -> artifacts))
        Json.obj("module" -> binding, "artifacts" -> artifacts)
      }
      val v3first = _v3member_("one_2.12", first)
      val v3second = _v3member_("two_2.12", second)
      _write(marker.resolveSibling("completed-v3.json"), Json.obj("scope" -> Json.fromString("s"),
        "owner" -> Json.fromString("owner"), "batch" -> Json.fromString(publication),
        "producerSelectionId" -> Json.fromString("producer-selection"), "complete" -> Json.fromBoolean(true),
        "members" -> Json.arr(v3first, v3second)))
      val fixtureInput = CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile)
      fixtureInput.mappings.size shouldBe 1
      And("fixture-only public UpdateReport rows contain classified and empty/evicted records")
      val one = ModuleID("example", "one_2.12", selectedversion)
      val two = ModuleID("example", "two_2.12", "1.0")
      val defaultArtifact = Artifact("one_2.12")
      val nativeArtifact = Artifact("one_2.12").withClassifier(Some("native"))
      val fixtureOrdinary = Files.write(root.resolve("ordinary.jar"), Array[Byte](3)).toFile
      val self = sbt.librarymanagement.Caller(one, Vector(sbt.librarymanagement.ConfigRef("runtime")),
        Map.empty, false, false, true, false)
      val defaultRow = sbt.librarymanagement.ModuleReport(one, Vector(defaultArtifact -> first._1.toFile), Vector.empty).
        withCallers(Vector(self))
      val nativeRow = sbt.librarymanagement.ModuleReport(one,
        Vector(nativeArtifact -> first._1.resolveSibling("one_2.12-native.jar").toFile), Vector.empty)
      val emptyRow = sbt.librarymanagement.ModuleReport(one, Vector.empty, Vector.empty)
      val evictedRow = sbt.librarymanagement.ModuleReport(one.withRevision("0.9"), Vector.empty, Vector.empty).
        withEvicted(true)
      val otherRow = sbt.librarymanagement.ModuleReport(two, Vector(Artifact("two_2.12") -> fixtureOrdinary), Vector.empty)
      def report(rows: Vector[sbt.librarymanagement.ModuleReport]): UpdateReport = UpdateReport(
        first._2.toFile, Vector(sbt.librarymanagement.ConfigurationReport(sbt.librarymanagement.ConfigRef("runtime"),
          rows, Vector.empty)), sbt.librarymanagement.UpdateStats(0L, 0L, 0L, false), Map.empty)
      val fixtureGenerator = CozyDevelopmentArtifactOverlay.GeneratorSelection("1.0", "1.0", publication, fixtureInput.mappings)
      val selectedInput = fixtureInput.copy(generator = Some(fixtureGenerator))
      def project(rows: Vector[sbt.librarymanagement.ModuleReport]): CozyDevelopmentArtifactOverlay.GeneratorResolution =
        CozyDevelopmentArtifactOverlay.projectGeneratorReport(selectedInput, report(rows), Vector(one, two), Vector(one))
      val projected = project(Vector(defaultRow, nativeRow, emptyRow, evictedRow, otherRow))
      val projection = projected.resolutionV3.hcursor
      val artifactKeys = projection.get[Vector[Json]]("runtimeClasspath").fold(throw _, identity)
      artifactKeys.size shouldBe 3
      artifactKeys.exists(_.hcursor.get[Option[String]]("classifier").toOption.flatten.contains("native")) shouldBe true
      artifactKeys.find(_.hcursor.get[Option[String]]("classifier").toOption.flatten.isEmpty).get.hcursor.downField("classifier").focus shouldBe None
      val reported = projection.get[Vector[Json]]("reportRows").fold(throw _, identity)
      reported.size shouldBe 5
      reported.exists(_.hcursor.downField("eviction").get[Boolean]("evicted").toOption.contains(true)) shouldBe true
      val normal = reported.find(!_.hcursor.downField("eviction").get[Boolean]("evicted").getOrElse(false)).get.hcursor
      normal.downField("eviction").downField("selectedRevision").focus shouldBe None
      normal.downField("problem").focus shouldBe None
      normal.downField("targetModule").downField("configurations").focus shouldBe None
      val evicted = reported.find(_.hcursor.downField("eviction").get[Boolean]("evicted").getOrElse(false)).get.hcursor
      evicted.downField("eviction").get[String]("selectedRevision").toOption shouldBe Some(selectedversion)
      val ordinaryModule = projection.get[Vector[Json]]("modules").fold(throw _, identity).
        find(_.hcursor.get[String]("name").toOption.contains("two_2.12")).get.hcursor
      ordinaryModule.downField("descriptor").focus shouldBe None
      ordinaryModule.downField("control").focus shouldBe None
      val selfEdges = projection.get[Vector[Json]]("edges").fold(throw _, identity)
      selfEdges.exists(edge => edge.hcursor.get[String]("from").toOption == edge.hcursor.get[String]("to").toOption) shouldBe true
      Then("actual missing artifacts, reported problems and conflicting winners or files fail closed")
      intercept[RuntimeException](project(Vector(defaultRow.withMissingArtifacts(Vector(defaultArtifact)), otherRow)))
      intercept[RuntimeException](project(Vector(defaultRow.withProblem(Some("fixture problem")), otherRow)))
      intercept[RuntimeException](project(Vector(defaultRow, defaultRow.withModule(one.withRevision("2.0")), otherRow)))
      intercept[RuntimeException](project(Vector(defaultRow,
        defaultRow.withArtifacts(Vector(defaultArtifact -> fixtureOrdinary)), otherRow)))
      intercept[RuntimeException](project(Vector(defaultRow, nativeRow)))
      Then("an unavailable classified artifact in the unselected member rejects the whole batch")
      val classified = second._1.resolveSibling("two_2.12-native.jar")
      Files.delete(classified)
      intercept[RuntimeException](CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile))
      Files.write(classified, Array[Byte](2))
      Files.write(second._2, s"""<ivy-module version='2.0'><info organisation='example' module='two_2.12' revision='$selectedversion'/><publications><artifact name='two_2.12' type='jar' ext='jar'/></publications><dependencies/></ivy-module>""".getBytes(StandardCharsets.UTF_8))
      intercept[RuntimeException](CozyDevelopmentArtifactOverlay.read(file.toFile, root.toFile))
    }

    "reject synthetic child result changes against retained in-memory producer facts" in {
      Given("fixture-only resolution, observation and output inventory values")
      val root = Files.createTempDirectory("cozy-reconcile-fixture").toRealPath()
      val output = Files.createDirectory(root.resolve("output"))
      Files.write(output.resolve("Fixture.scala"), Array[Byte](1))
      val cozy = Files.write(root.resolve("cozy.jar"), Array[Byte](1)).toFile
      val modeler = Files.write(root.resolve("modeler.jar"), Array[Byte](2)).toFile
      val other = Files.write(root.resolve("other.jar"), Array[Byte](3)).toFile
      val modules = Vector("cozy_2.12", "simplemodeler_2.12").map { name =>
        Json.obj("organization" -> Json.fromString("org.simplemodeling"), "name" -> Json.fromString(name),
          "selectedVersion" -> Json.fromString("1.0"))
      }
      def artifact(name: String, file: java.io.File): Json = Json.obj(
        "key" -> Json.obj("module" -> Json.fromString("org.simplemodeling:" + name + ":1.0")),
        "runtime" -> Json.fromBoolean(true), "launchFile" -> Json.fromString(file.getAbsolutePath))
      val artifacts = Vector(artifact("cozy_2.12", cozy), artifact("simplemodeler_2.12", modeler))
      val edge = Json.obj("from" -> Json.fromString("cozy"), "to" -> Json.fromString("modeler"))
      val resolution = Json.obj("modules" -> Json.arr(modules: _*), "edges" -> Json.arr(edge),
        "artifacts" -> Json.arr(artifacts: _*))
      val retained = CozyDevelopmentArtifactOverlay.GeneratorResolution(modules, Vector(edge), Vector(cozy, modeler), artifacts, resolution)
      val selection = Json.obj("scope" -> Json.fromString("fixture"))
      val generator = CozyDevelopmentArtifactOverlay.GeneratorSelection("1.0", "1.0", "fixture", Vector.empty)
      def origin(file: java.io.File): Json = Json.obj("className" -> Json.fromString("Fixture"),
        "resource" -> Json.fromString("fixture"), "codeSource" -> Json.fromString(file.getAbsolutePath))
      val observation = Json.obj("classpath" -> Json.arr(Json.fromString(cozy.getAbsolutePath), Json.fromString(modeler.getAbsolutePath)),
        "loaderUrls" -> Json.arr(), "osName" -> Json.fromString("fixture"), "osArch" -> Json.fromString("fixture"),
        "javaVersion" -> Json.fromString("fixture"), "javaVendor" -> Json.fromString("fixture"),
        "cozyVersion" -> Json.fromString("1.0"), "requiredSimpleModelerVersion" -> Json.fromString("1.0"),
        "simpleModelerVersion" -> Json.fromString("1.0"), "cozy" -> origin(cozy), "simpleModeler" -> origin(modeler))
      val result = Json.obj("version" -> Json.fromString("v3"), "outcome" -> Json.fromString("generated"),
        "selection" -> selection, "receivedResolution" -> resolution, "observation" -> observation,
        "generatedFiles" -> Json.arr(Json.fromString("Fixture.scala")), "metadataFiles" -> Json.arr(), "diagnostics" -> Json.arr())
      def reconcile(value: Json): Vector[java.io.File] =
        CozyDevelopmentArtifactOverlay.reconcileGeneratorResult(retained, generator, selection, output, value.hcursor)
      reconcile(result).map(_.getName) shouldBe Vector("Fixture.scala")
      When("the fixture result drops producer facts or changes observed facts")
      val mutations = Vector(
        Json.obj("receivedResolution" -> Json.obj("modules" -> Json.arr(modules.head))),
        Json.obj("receivedResolution" -> Json.obj("edges" -> Json.arr())),
        Json.obj("receivedResolution" -> Json.obj("artifacts" -> Json.arr(artifacts.head))),
        Json.obj("selection" -> Json.obj("scope" -> Json.fromString("other"))),
        Json.obj("observation" -> Json.obj("classpath" -> Json.arr(Json.fromString(modeler.getAbsolutePath), Json.fromString(cozy.getAbsolutePath)))),
        Json.obj("observation" -> Json.obj("cozy" -> origin(other))),
        Json.obj("observation" -> Json.obj("requiredSimpleModelerVersion" -> Json.fromString("other"))),
        Json.obj("generatedFiles" -> Json.arr(Json.fromString("missing.scala"))))
      Then("reconciliation rejects each mutation; no child process is represented by this fixture")
      mutations.foreach(change => intercept[RuntimeException](reconcile(result.deepMerge(change))))
    }

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
