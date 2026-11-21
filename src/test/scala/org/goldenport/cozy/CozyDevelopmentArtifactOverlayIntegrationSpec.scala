package org.goldenport.cozy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit
import java.util.jar.{JarEntry, JarOutputStream}
import scala.collection.JavaConverters._
import io.circe.Json
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Nov. 12, 2026
 * @version Nov. 19, 2026
 */
final class CozyDevelopmentArtifactOverlayIntegrationSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private def _write(path: Path, value: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, value.getBytes(StandardCharsets.UTF_8))
  }
  private def _quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def _snapshot(path: Path): Map[String, String] = {
    if (!Files.exists(path)) Map.empty
    else {
      val stream = Files.walk(path)
      try stream.iterator().asScala.filter(Files.isRegularFile(_)).map { file =>
        path.relativize(file).toString -> java.util.Base64.getEncoder.encodeToString(Files.readAllBytes(file))
      }.toMap finally stream.close()
    }
  }
  private def _setup(root: Path, body: String): Unit = {
    Files.createDirectories(root.resolve("project"))
    val classpath = sys.props.getOrElse("cozy.overlay.test.classpath", fail("compiled plugin classpath was not supplied"))
    val lib = Files.createDirectories(root.resolve("project/lib"))
    classpath.split(java.io.File.pathSeparator).toVector.map(Paths.get(_)).zipWithIndex.foreach {
      case (entry, index) if Files.isDirectory(entry) =>
        val jar = new JarOutputStream(Files.newOutputStream(lib.resolve(s"classpath-$index.jar")))
        val stream = Files.walk(entry)
        try stream.iterator().asScala.filter(Files.isRegularFile(_)).foreach { file =>
          val name = entry.relativize(file).iterator().asScala.map(_.toString).mkString("/")
          jar.putNextEntry(new JarEntry(name))
          Files.copy(file, jar)
          jar.closeEntry()
        } finally {
          stream.close()
          jar.close()
        }
      case (entry, index) if Files.isRegularFile(entry) && entry.toString.endsWith(".jar") =>
        Files.copy(entry, lib.resolve(s"classpath-$index.jar"))
      case (entry, _) => fail("unsupported plugin classpath entry: " + entry)
    }
    _write(root.resolve("project/build.properties"), "sbt.version=1.11.7\n")
    _write(root.resolve("build.sbt"), body)
  }
  private def _start(root: Path, log: Path, selection: Option[Path], task: String): Process = {
    val arguments = Vector("sbt", "-batch") ++ selection.toVector.map(path => "-Dsm.artifact.selection=" + path) ++ Vector(task)
    new ProcessBuilder(arguments: _*).directory(root.toFile).redirectErrorStream(true).redirectOutput(log.toFile).start()
  }
  private def _await(process: Process, log: Path): Int = {
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly()
      process.waitFor()
      fail("fixture sbt timed out; log: " + log)
    }
    process.exitValue()
  }
  private def _run(root: Path, log: Path, selection: Option[Path], task: String): Int =
    _await(_start(root, log, selection, task), log)

  private def _runpair(firstroot: Path, firstlog: Path, firstinput: Path,
    secondroot: Path, secondlog: Path, secondinput: Path, task: String): (Int, Int) = {
    val first = _start(firstroot, firstlog, Some(firstinput), task)
    var second: Option[Process] = None
    try {
      val process = _start(secondroot, secondlog, Some(secondinput), task)
      second = Some(process)
      (_await(first, firstlog), _await(process, secondlog))
    } finally {
      (Vector(first) ++ second.toVector).foreach { process =>
        if (process.isAlive) {
          process.destroyForcibly()
          process.waitFor()
        }
      }
    }
  }
  private def _coordinate(version: String, module: String = "api_2.12"): Json = Json.obj("organization" -> Json.fromString("example.overlay"),
    "module" -> Json.fromString(module), "version" -> Json.fromString(version))
  private def _input(build: Path, owner: Path, kind: String, scope: String, publication: String,
    mappings: Vector[Json]): Json = {
    val repository = owner.resolve("repo")
    val devversion = "1.0.0-SNAPSHOT-smdev-" + scope.length + "-" + scope + "-" + publication.length + "-" + publication
    val base = repository.resolve("example.overlay/api_2.12").resolve(devversion)
    val producer = _coordinate(devversion).deepMerge(Json.obj("jar" -> Json.fromString(base.resolve("jars/api_2.12.jar").toString),
      "ivy" -> Json.fromString(base.resolve("ivys/ivy.xml").toString)))
    val adapter = Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact.v1"),
      "component" -> Json.fromString("example"), "buildWorkspace" -> Json.fromString("build"),
      "ownerWorkspace" -> Json.fromString("owner"), "scope" -> Json.fromString(scope), "selection" -> Json.fromString("selection-1"),
      "buildRoot" -> Json.fromString(build.toString), "ownerRoot" -> Json.fromString(owner.toString),
      "repositoryRoot" -> Json.fromString(repository.toString), "resultFile" -> Json.fromString(owner.resolve(kind + "-result.json").toString),
      "mappings" -> Json.arr(mappings: _*))
    val envelope = Json.obj("schema" -> Json.fromString("cncf.scoped-sbt-input.v1"), "kind" -> Json.fromString(kind),
      "component" -> Json.fromString("example"), "buildWorkspace" -> Json.fromString("build"),
      "ownerWorkspace" -> Json.fromString("owner"), "scope" -> Json.fromString(scope),
      "selection" -> Json.fromString("selection-1"), "cozyArtifact" -> (if (kind == "producer") adapter.deepMerge(Json.obj("producer" -> producer)) else adapter))
    if (kind == "producer") envelope.deepMerge(Json.obj("publication" -> Json.fromString(publication),
      "repository" -> Json.fromString("repo"), "staging" -> Json.fromString("stage"))) else envelope
  }
  private def _mapping(owner: Path, scope: String, publication: String, module: String = "api_2.12"): Json = {
    val devversion = "1.0.0-SNAPSHOT-smdev-" + scope.length + "-" + scope + "-" + publication.length + "-" + publication
    val base = owner.resolve("repo/example.overlay").resolve(module).resolve(devversion)
    Json.obj("requested" -> _coordinate("1.0.0-SNAPSHOT", module), "selected" -> _coordinate(devversion, module),
      "publication" -> Json.fromString(publication), "publicationSelection" -> Json.fromString("selection-1"),
      "jar" -> Json.fromString(base.resolve("jars").resolve(module + ".jar").toString),
      "ivy" -> Json.fromString(base.resolve("ivys/ivy.xml").toString))
  }

  "The enabled plugin" should {
    "generate and compile against real selected generator and CNCF publications across fresh selections" in {
      Given("actual published Cozy, SimpleModeler and CNCF roots and a private two-batch producer")
      val owner = Files.createTempDirectory("cozy-overlay-real-generation").toRealPath()
      val producer = Files.createDirectory(owner.resolve("producer"))
      val consumer = Files.createDirectory(owner.resolve("consumer"))
      Files.createDirectory(owner.resolve("repo"))
      Files.createDirectory(owner.resolve("stage"))
      val local = Paths.get(sys.props("user.home"), ".ivy2", "local")
      val roots = Vector(
        ("cozy", "org.simplemodeling", "cozy_2.12", "cozy", "0.3.3-SNAPSHOT", "2.12.20"),
        ("modeler", "org.simplemodeling", "simplemodeler_2.12", "simplemodeler", "1.1.26-SNAPSHOT", "2.12.20"),
        ("cncf", "org.goldenport", "goldenport-cncf_3", "goldenport-cncf", "0.5.6-SNAPSHOT", "3.3.8"))
      def _published_(organization: String, module: String, version: String, part: String): Path = {
        val base = local.resolve(organization).resolve(module).resolve(version)
        if (part == "jar") base.resolve("jars").resolve(module + ".jar") else base.resolve("ivys/ivy.xml")
      }
      roots.foreach { case (_, organization, module, _, version, _) =>
        Files.isRegularFile(_published_(organization, module, version, "jar")) shouldBe true
        Files.isRegularFile(_published_(organization, module, version, "ivy")) shouldBe true
      }
      val build = roots.map { case (project, organization, module, name, version, scala) =>
        val jar = _quoted(_published_(organization, module, version, "jar").toString)
        val ivy = _quoted(_published_(organization, module, version, "ivy").toString)
        val base = local.resolve(organization).resolve(module).resolve(version)
        val sources = _quoted(base.resolve("srcs").resolve(module + "-sources.jar").toString)
        val pom = _quoted(base.resolve("poms").resolve(module + ".pom").toString)
        s"""lazy val $project = project.in(file("$project")).settings(organization := "$organization", name := "$name", version := "$version", scalaVersion := "$scala", Compile / packageBin := file($jar), makeIvyXml := file($ivy), packagedArtifacts := Map(Artifact("$module") -> file($jar), Artifact("$module").withType("src").withClassifier(Some("sources")) -> file($sources), Artifact("$module").withType("pom").withExtension("pom") -> file($pom)))"""
      }.mkString("\n")
      _setup(producer, "import org.goldenport.cozy.CozyDevelopmentArtifactsPlugin\n" + build +
        "\nlazy val root = project.in(file(\".\")).enablePlugins(CozyDevelopmentArtifactsPlugin)\n")
      val scope = "real-generation"
      def _coordinate_(organization: String, module: String, version: String): Json = Json.obj(
        "organization" -> Json.fromString(organization), "module" -> Json.fromString(module), "version" -> Json.fromString(version))
      def _version_(base: String, publication: String): String =
        base + "-smdev-" + scope.length + "-" + scope + "-" + publication.length + "-" + publication
      def _paths_(organization: String, module: String, version: String, publication: String): (Path, Path) = {
        val base = owner.resolve("repo").resolve(organization).resolve(module).resolve(_version_(version, publication))
        (base.resolve("jars").resolve(module + ".jar"), base.resolve("ivys/ivy.xml"))
      }
      def _producer_(publication: String): Json = {
        val members = roots.map { case (project, organization, module, _, version, _) =>
          val (jar, ivy) = _paths_(organization, module, version, publication)
          _coordinate_(organization, module, _version_(version, publication)).deepMerge(Json.obj(
            "project" -> Json.fromString(project), "jar" -> Json.fromString(jar.toString), "ivy" -> Json.fromString(ivy.toString)))
        }
        _input(producer, owner, "producer", scope, publication, Vector.empty).deepMerge(Json.obj(
          "cozyArtifact" -> Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact.v2"),
            "producers" -> Json.arr(members: _*))))
      }
      def _mapping_(organization: String, module: String, version: String, publication: String): Json = {
        val (jar, ivy) = _paths_(organization, module, version, publication)
        Json.obj("requested" -> _coordinate_(organization, module, version),
          "selected" -> _coordinate_(organization, module, _version_(version, publication)),
          "publication" -> Json.fromString(publication), "publicationSelection" -> Json.fromString("selection-1"),
          "jar" -> Json.fromString(jar.toString), "ivy" -> Json.fromString(ivy.toString))
      }
      val sharedcozy = local.resolve("org.simplemodeling/cozy_2.12/0.3.3-SNAPSHOT")
      val sharedmodeler = local.resolve("org.simplemodeling/simplemodeler_2.12/1.1.26-SNAPSHOT")
      val sharedcncf = local.resolve("org.goldenport/goldenport-cncf_3/0.5.6-SNAPSHOT")
      val sharedbefore = Vector(sharedcozy, sharedmodeler, sharedcncf).map(_snapshot)
      Vector("a", "b").foreach { publication =>
        val file = owner.resolve("producer-" + publication + ".json")
        _write(file, _producer_(publication).noSpaces)
        _run(producer, owner.resolve("producer-" + publication + ".log"), Some(file), "smDevelopmentArtifactPublish") shouldBe 0
      }
      _setup(consumer, """import org.goldenport.cozy.{CozyPlugin, CozyDevelopmentArtifactsPlugin}
enablePlugins(CozyPlugin, CozyDevelopmentArtifactsPlugin)
scalaVersion := "3.3.8"
resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven"
cozyGenerationTarget := "cncf"
cozyDelegateCoursierVersion := Some("0.3.3-SNAPSHOT")
cozyDevelopmentModules := Seq("org.goldenport" %% "goldenport-cncf" % "0.5.6-SNAPSHOT")
libraryDependencies += cozyDevelopmentDependency("org.goldenport" %% "goldenport-cncf" % "0.5.6-SNAPSHOT").value
""")
      _write(consumer.resolve("project.yaml"), """project:
  namespace: com.example
  id: Sample
  kind: car
  component:
    version: 0.0.1-SNAPSHOT
build:
  scalaVersion: 3.3.8
  cozyVersion: 0.3.3-SNAPSHOT
  dependencies:
    compile:
      - org.goldenport::goldenport-cncf:0.5.6-SNAPSHOT
packaging:
  kind: car
  car:
    runtime:
      cncf:
        minimum: 0.5.6-SNAPSHOT
        excluded: []
        tested:
          - 0.5.6-SNAPSHOT
""")
      _write(consumer.resolve("src/main/cozy/derived-attributes.cml"),
        """# Entity
          |
          |## Notice
          |
          |### FEATURES
          |
          |extends = ["SimpleEntity"]
          |
          |### ATTRIBUTE
          |
          || name          | type   | multiplicity | label      | derived |
          ||---------------+--------+--------------+------------+---------|
          || senderName    | string | 1            | Sender     |         |
          || recipientName | string | ?            | Recipient  | title   |
          || body          | string | 1            | Body       |         |
          || authorId      | string | ?            | Author     | ownerId |
          |""".stripMargin)
      _write(consumer.resolve("src/main/car/abi-manifest.json"), """{"format":"cozy.car.abi-manifest.v2","component":{"namespace":"com.example","id":"Sample","version":"0.0.1-SNAPSHOT"},"abi":{"version":1,"exports":{"components":[{"namespace":"com.example","id":"Sample"}],"operations":[],"entities":[]},"dependencies":[]}}""")
      def _selection_(publication: String, root: Path, name: String): Path = {
        val selected = _input(root, owner, "consumer", scope, publication,
          Vector(_mapping_("org.goldenport", "goldenport-cncf_3", "0.5.6-SNAPSHOT", publication))).deepMerge(Json.obj(
          "selection" -> Json.fromString("selection-" + publication), "cozyArtifact" -> Json.obj(
            "schema" -> Json.fromString("sbt-cozy.development-artifact.v2"), "selection" -> Json.fromString("selection-" + publication),
            "resultFile" -> Json.fromString(owner.resolve(name + "-result.json").toString),
            "generator" -> Json.obj("cozyVersion" -> Json.fromString("0.3.3-SNAPSHOT"),
              "modelerVersion" -> Json.fromString("1.1.26-SNAPSHOT"), "batch" -> Json.fromString(publication),
              "mappings" -> Json.arr(_mapping_("org.simplemodeling", "cozy_2.12", "0.3.3-SNAPSHOT", publication),
                _mapping_("org.simplemodeling", "simplemodeler_2.12", "1.1.26-SNAPSHOT", publication))))))
        val file = owner.resolve(name + "-" + publication + ".json")
        _write(file, selected.noSpaces)
        file
      }
      val a = _selection_("a", consumer, "consumer")
      val b = _selection_("b", consumer, "consumer")
      val bad = owner.resolve("consumer-incompatible.json")
      val badjson = io.circe.parser.parse(new String(Files.readAllBytes(a), StandardCharsets.UTF_8)).fold(throw _, identity).
        deepMerge(Json.obj("cozyArtifact" -> Json.obj("generator" -> Json.obj("cozyVersion" -> Json.fromString("0.3.2")))))
      _write(bad, badjson.noSpaces)
      _run(consumer, owner.resolve("real-consumer-rejected.log"), Some(bad), "compile") should not be 0
      new String(Files.readAllBytes(owner.resolve("real-consumer-rejected.log")), StandardCharsets.UTF_8) should
        include ("generator root base version disagrees with mapping")
      Files.exists(consumer.resolve("target/sbt-cozy/development-results")) shouldBe false
      When("fresh SBT invocations select A, B, then the exact completed A publication again")
      Vector(a, b, a).zipWithIndex.foreach { case (selection, index) =>
        val log = owner.resolve(s"real-consumer-$index.log")
        val exit = _run(consumer, log, Some(selection), "compile")
        withClue("Child sbt log " + log + ":\n" + new String(Files.readAllBytes(log), StandardCharsets.UTF_8) + "\n") {
          exit shouldBe 0
        }
        val result = new String(Files.readAllBytes(owner.resolve("consumer-result.json")), StandardCharsets.UTF_8)
        val resultJson = io.circe.parser.parse(result).fold(throw _, identity).hcursor
        resultJson.downField("generation").get[String]("outcome").fold(throw _, identity) shouldBe "generated"
        val observations = resultJson.downField("generation").get[Vector[Json]]("observations").fold(throw _, identity)
        observations.nonEmpty shouldBe true
        observations.foreach { value =>
          val cursor = value.hcursor
          cursor.get[String]("outcome").fold(throw _, identity) shouldBe "generated"
          val resolution = cursor.downField("producerResolution")
          val rows = resolution.get[Vector[Json]]("reportRows").fold(throw _, identity)
          rows.nonEmpty shouldBe true
          val artifacts = resolution.get[Vector[Json]]("artifacts").fold(throw _, identity)
          artifacts.exists { artifact =>
            val key = artifact.hcursor.downField("key")
            key.get[String]("module").toOption.exists(_.startsWith("org.openjfx:javafx-")) &&
              key.get[Option[String]]("classifier").toOption.flatten.nonEmpty
          } shouldBe true
          val observation = cursor.downField("observation")
          observation.get[String]("cozyVersion").fold(throw _, identity) shouldBe "0.3.3-SNAPSHOT"
          observation.get[String]("simpleModelerVersion").fold(throw _, identity) shouldBe "1.1.26-SNAPSHOT"
        }
        val generated = _snapshot(consumer.resolve("target")).keys.filter(_.endsWith(".scala")).toVector
        generated.nonEmpty shouldBe true
      }
      When("the compiler is invoked with stricter warnings against the genuine generated sources")
      val failurelog = owner.resolve("real-consumer-compiler-failure.log")
      val failure = new ProcessBuilder("sbt", "-batch", "-Dsm.artifact.selection=" + a,
        "set Compile / scalacOptions ++= Seq(\"-Wunused:all\", \"-Werror\")", "compile").
        directory(consumer.toFile).redirectErrorStream(true).redirectOutput(failurelog.toFile).start()
      _await(failure, failurelog) should not be 0
      val diagnostics = new String(Files.readAllBytes(failurelog), StandardCharsets.UTF_8)
      diagnostics should include (".scala")
      diagnostics should include ("src_managed")
      diagnostics.toLowerCase should include ("error")
      val compilerresult = new String(Files.readAllBytes(owner.resolve("consumer-result.json")), StandardCharsets.UTF_8)
      io.circe.parser.parse(compilerresult).fold(throw _, identity).hcursor.
        downField("generation").get[String]("outcome").fold(throw _, identity) shouldBe "generated"
      val consumer2 = Files.createDirectory(owner.resolve("consumer2"))
      _setup(consumer2, new String(Files.readAllBytes(consumer.resolve("build.sbt")), StandardCharsets.UTF_8))
      Vector("project.yaml", "src/main/cozy/derived-attributes.cml", "src/main/car/abi-manifest.json").foreach { relative =>
        _write(consumer2.resolve(relative), new String(Files.readAllBytes(consumer.resolve(relative)), StandardCharsets.UTF_8))
      }
      val second = _selection_("b", consumer2, "consumer2")
      val simultaneous = _runpair(consumer, owner.resolve("real-consumer-a-parallel.log"), a,
        consumer2, owner.resolve("real-consumer-b-parallel.log"), second, "compile")
      simultaneous shouldBe ((0, 0))
      new String(Files.readAllBytes(owner.resolve("consumer-result.json")), StandardCharsets.UTF_8) should include ("selection-a")
      new String(Files.readAllBytes(owner.resolve("consumer2-result.json")), StandardCharsets.UTF_8) should include ("selection-b")
      Then("the same checked-in build also detaches selection on a fresh ordinary invocation")
      _run(consumer, owner.resolve("real-consumer-detached.log"), None, "compile") shouldBe 0
      Vector(sharedcozy, sharedmodeler, sharedcncf).map(_snapshot) shouldBe sharedbefore
    }

    "publish declared project members only after the entire batch builds" in {
      Given("a two-project producer with a private repository and an initially broken second project")
      val owner = Files.createTempDirectory("cozy-overlay-multiproject").toRealPath()
      val producer = Files.createDirectory(owner.resolve("producer"))
      Files.createDirectory(owner.resolve("repo"))
      Files.createDirectory(owner.resolve("stage"))
      val shared = Paths.get(sys.props("user.home"), ".ivy2", "local", "example.overlay")
      val sharedbefore = _snapshot(shared)
      _setup(producer, """import org.goldenport.cozy.CozyDevelopmentArtifactsPlugin
lazy val a = project.in(file("a")).settings(organization := "example.overlay", name := "alpha", version := "1.0.0-SNAPSHOT", scalaVersion := "2.12.20")
lazy val b = project.in(file("b")).dependsOn(a).settings(organization := "example.overlay", name := "beta", version := "1.0.0-SNAPSHOT", scalaVersion := "2.12.20")
lazy val root = project.in(file(".")).enablePlugins(CozyDevelopmentArtifactsPlugin)
""")
      _write(producer.resolve("a/src/main/scala/Alpha.scala"), "package example; object Alpha { val value = 1 }\n")
      val betasource = producer.resolve("b/src/main/scala/Beta.scala")
      _write(betasource, "package example; object Beta { val value: Int = missing }\n")
      val scope = "batch-scope"
      val publication = "batch-pub"
      val version = "1.0.0-SNAPSHOT-smdev-" + scope.length + "-" + scope + "-" + publication.length + "-" + publication
      def _producer_(project: String, module: String): Json = {
        val base = owner.resolve("repo/example.overlay").resolve(module).resolve(version)
        _coordinate(version, module).deepMerge(Json.obj("project" -> Json.fromString(project),
          "jar" -> Json.fromString(base.resolve("jars").resolve(module + ".jar").toString),
          "ivy" -> Json.fromString(base.resolve("ivys/ivy.xml").toString)))
      }
      val input = owner.resolve("batch-input.json")
      val declaration = _input(producer, owner, "producer", scope, publication, Vector.empty).deepMerge(Json.obj(
        "cozyArtifact" -> Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact.v2"),
          "producers" -> Json.arr(_producer_("a", "alpha_2.12"), _producer_("b", "beta_2.12")))))
      _write(input, declaration.noSpaces)
      val marker = owner.resolve("repo/.batches").resolve(scope).resolve(publication).resolve("completed.json")
      When("one declared project fails to compile")
      _run(producer, owner.resolve("batch-failed.log"), Some(input), "smDevelopmentArtifactPublish") should not be 0
      Then("the batch is unavailable and shared Ivy is unchanged")
      Files.exists(marker) shouldBe false
      _snapshot(shared) shouldBe sharedbefore
      When("the broken project is repaired and the same explicit declaration is invoked again")
      _write(betasource, "package example; object Beta { val value: Int = Alpha.value }\n")
      _run(producer, owner.resolve("batch-success.log"), Some(input), "smDevelopmentArtifactPublish") shouldBe 0
      Then("the common completion record and both members exist")
      Files.isRegularFile(marker) shouldBe true
      Vector("alpha_2.12", "beta_2.12").foreach { module =>
        Files.isRegularFile(owner.resolve("repo/example.overlay").resolve(module).resolve(version).
          resolve("jars").resolve(module + ".jar")) shouldBe true
      }
      val consumer = Files.createDirectory(owner.resolve("consumer"))
      _setup(consumer, """import org.goldenport.cozy.CozyDevelopmentArtifactsPlugin
enablePlugins(CozyDevelopmentArtifactsPlugin)
scalaVersion := "2.12.20"
cozyDevelopmentModules := Seq("example.overlay" %% "alpha" % "1.0.0-SNAPSHOT", "example.overlay" %% "beta" % "1.0.0-SNAPSHOT")
libraryDependencies += cozyDevelopmentDependency("example.overlay" %% "beta" % "1.0.0-SNAPSHOT").value
""")
      _write(consumer.resolve("src/main/scala/Use.scala"), "object Use { val value: Int = example.Beta.value }\n")
      val consumerinput = owner.resolve("consumer-input.json")
      _write(consumerinput, _input(consumer, owner, "consumer", scope, publication,
        Vector(_mapping(owner, scope, publication, "alpha_2.12"), _mapping(owner, scope, publication, "beta_2.12"))).noSpaces)
      _run(consumer, owner.resolve("batch-consumer.log"), Some(consumerinput), "compile") shouldBe 0
      _snapshot(shared) shouldBe sharedbefore
    }

    "publish a real private artifact and resolve it from explicitly opted-in builds" in {
      Given("a producer, ordinary consumer and private owner workspace")
      val target = Paths.get("target").toAbsolutePath.normalize
      Files.createDirectories(target)
      val owner = Files.createTempDirectory(target, "overlay-integration-").toRealPath()
      val producer = Files.createDirectory(owner.resolve("producer"))
      val consumer = Files.createDirectory(owner.resolve("consumer"))
      val repository = Files.createDirectory(owner.resolve("repo"))
      Files.createDirectory(owner.resolve("stage"))
      val ordinary = Files.createDirectory(owner.resolve("ordinary"))
      _setup(producer, "enablePlugins(org.goldenport.cozy.CozyDevelopmentArtifactsPlugin)\norganization := \"example.overlay\"\nname := \"api\"\nversion := \"1.0.0-SNAPSHOT\"\nscalaVersion := \"2.12.20\"\n")
      _write(producer.resolve("src/main/scala/Api.scala"), "package example; object Api { def value: Int = 42 }\n")
      val publisher = owner.resolve("producer.json")
      _write(publisher, _input(producer, owner, "producer", "scope-1", "pub-1", Vector.empty).noSpaces)
      val selectedbase = repository.resolve("example.overlay/api_2.12/1.0.0-SNAPSHOT-smdev-7-scope-1-5-pub-1")
      val shared = Paths.get(System.getProperty("user.home"), ".ivy2/local/example.overlay/api_2.12")
      val sharedbefore = _snapshot(shared)
      When("the scoped task publishes the producer")
      _run(producer, owner.resolve("producer.log"), Some(publisher), "smDevelopmentArtifactPublish") shouldBe 0
      Then("JAR, Ivy descriptor and completion evidence exist only in the private repository")
      Files.isRegularFile(selectedbase.resolve("jars/api_2.12.jar")) shouldBe true
      Files.isRegularFile(selectedbase.resolve("ivys/ivy.xml")) shouldBe true
      Files.isRegularFile(selectedbase.resolve("completed.json")) shouldBe true
      Files.isRegularFile(owner.resolve("producer-result.json")) shouldBe true
      _snapshot(shared) shouldBe sharedbefore
      _run(producer, owner.resolve("duplicate.log"), Some(publisher), "smDevelopmentArtifactPublish") should not be 0
      val duplicateone = Files.createDirectory(owner.resolve("duplicate-one"))
      val duplicatetwo = Files.createDirectory(owner.resolve("duplicate-two"))
      val duplicatebuild = "enablePlugins(org.goldenport.cozy.CozyDevelopmentArtifactsPlugin)\norganization := \"example.overlay\"\nname := \"api\"\nversion := \"1.0.0-SNAPSHOT\"\nscalaVersion := \"2.12.20\"\n"
      Vector(duplicateone, duplicatetwo).foreach { root =>
        _setup(root, duplicatebuild)
        _write(root.resolve("src/main/scala/Api.scala"), "package example; object Api { def value: Int = 17 }\n")
      }
      val duplicateinputone = owner.resolve("duplicate-one.json")
      val duplicateinputtwo = owner.resolve("duplicate-two.json")
      _write(duplicateinputone, _input(duplicateone, owner, "producer", "dup", "pub-1", Vector.empty).
        deepMerge(Json.obj("cozyArtifact" -> Json.obj("resultFile" -> Json.fromString(owner.resolve("duplicate-one-result.json").toString)))).noSpaces)
      _write(duplicateinputtwo, _input(duplicatetwo, owner, "producer", "dup", "pub-1", Vector.empty).
        deepMerge(Json.obj("cozyArtifact" -> Json.obj("resultFile" -> Json.fromString(owner.resolve("duplicate-two-result.json").toString)))).noSpaces)
      val duplicateresults = _runpair(duplicateone, owner.resolve("duplicate-one.log"), duplicateinputone,
        duplicatetwo, owner.resolve("duplicate-two.log"), duplicateinputtwo, "smDevelopmentArtifactPublish")
      Vector(duplicateresults._1, duplicateresults._2).count(_ == 0) shouldBe 1
      val duplicatebase = repository.resolve("example.overlay/api_2.12/1.0.0-SNAPSHOT-smdev-3-dup-5-pub-1")
      Files.isRegularFile(duplicatebase.resolve("completed.json")) shouldBe true
      val duplicatebefore = _snapshot(duplicatebase)
      _run(duplicateone, owner.resolve("duplicate-again.log"), Some(duplicateinputone), "smDevelopmentArtifactPublish") should not be 0
      _snapshot(duplicatebase) shouldBe duplicatebefore
      val direct = Files.createDirectory(owner.resolve("direct"))
      _setup(direct, "enablePlugins(org.goldenport.cozy.CozyDevelopmentArtifactsPlugin)\nscalaVersion := \"2.12.20\"\n" +
        "libraryDependencies += cozyDevelopmentDependency(\"example.overlay\" %% \"api\" % \"1.0.0-SNAPSHOT\").value\n" +
        "cozyDevelopmentModules += (\"example.overlay\" %% \"api\" % \"1.0.0-SNAPSHOT\")\n" +
        "libraryDependencies += \"org.scalatest\" %% \"scalatest\" % \"3.2.19\" % Test\n")
      _write(direct.resolve("src/main/scala/Use.scala"), "object Use { val n: Int = example.Api.value }\n")
      _write(direct.resolve("src/test/scala/UseSpec.scala"), "class UseSpec extends org.scalatest.funsuite.AnyFunSuite { test(\"development value\") { assert(example.Api.value == 42) } }\n")
      val directinput = owner.resolve("direct.json")
      _write(directinput, _input(direct, owner, "consumer", "scope-1", "pub-1", Vector(_mapping(owner, "scope-1", "pub-1"))).noSpaces)
      // No ordinary repository contains this coordinate at this point.
      _run(direct, owner.resolve("direct.log"), Some(directinput), "compile") shouldBe 0
      _run(direct, owner.resolve("direct-test.log"), Some(directinput), "test") shouldBe 0
      val testonlylog = owner.resolve("direct-test-only.log")
      _run(direct, testonlylog, Some(directinput), "testOnly UseSpec") shouldBe 0
      new String(Files.readAllBytes(testonlylog), StandardCharsets.UTF_8) should include ("development value")
      def _test_success_(path: Path): Boolean = {
        val json = io.circe.parser.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).
          fold(error => fail(error.message), identity)
        val entries = json.hcursor.get[Vector[Json]]("results").fold(error => fail(error.message), identity)
        entries.exists { entry =>
          val cursor = entry.hcursor
          cursor.get[String]("configuration").toOption.contains("Test") &&
            cursor.get[String]("outcome").toOption.contains("success")
        }
      }
      _test_success_(owner.resolve("consumer-result.json")) shouldBe true
      val directbuild = new String(Files.readAllBytes(direct.resolve("build.sbt")), StandardCharsets.UTF_8)
      _write(direct.resolve("build.sbt"), directbuild + "Test / resolvers := Seq.empty\n")
      val testonlyinvalidlog = owner.resolve("direct-test-only-missing-resolver.log")
      _run(direct, testonlyinvalidlog, Some(directinput), "testOnly UseSpec") should not be 0
      new String(Files.readAllBytes(testonlyinvalidlog), StandardCharsets.UTF_8) should include ("private resolver is missing")
      val failedresult = owner.resolve("consumer-result.json")
      if (Files.exists(failedresult)) {
        _test_success_(failedresult) shouldBe false
      }
      _write(direct.resolve("build.sbt"), directbuild)
      // Simulate an interrupted publisher leaving incomplete staging and a claimed partial destination.
      val interruptedstage = owner.resolve("stage/interrupted/pub-1")
      Files.createDirectories(interruptedstage.resolve("jars"))
      Files.copy(selectedbase.resolve("jars/api_2.12.jar"), interruptedstage.resolve("jars/api_2.12.jar"))
      val interruptedinput = owner.resolve("interrupted.json")
      _write(interruptedinput, _input(direct, owner, "consumer", "interrupted", "pub-1", Vector(_mapping(owner, "interrupted", "pub-1"))).noSpaces)
      _run(direct, owner.resolve("interrupted-stage-consumer.log"), Some(interruptedinput), "compile") should not be 0
      val interruptedbase = repository.resolve("example.overlay/api_2.12/1.0.0-SNAPSHOT-smdev-11-interrupted-5-pub-1")
      Files.createDirectories(interruptedbase.resolve("jars"))
      Files.copy(selectedbase.resolve("jars/api_2.12.jar"), interruptedbase.resolve("jars/api_2.12.jar"))
      Files.createFile(interruptedbase.resolveSibling(interruptedbase.getFileName.toString + ".claim"))
      _run(direct, owner.resolve("interrupted-consumer.log"), Some(interruptedinput), "compile") should not be 0
      val interruptedproducer = owner.resolve("interrupted-producer.json")
      _write(interruptedproducer, _input(producer, owner, "producer", "interrupted", "pub-1", Vector.empty).noSpaces)
      _run(producer, owner.resolve("interrupted-producer.log"), Some(interruptedproducer), "smDevelopmentArtifactPublish") should not be 0
      Files.exists(interruptedbase.resolve("completed.json")) shouldBe false
      Files.exists(interruptedbase.resolve("ivys/ivy.xml")) shouldBe false
      val secondpublisher = owner.resolve("producer-second.json")
      _write(producer.resolve("src/main/scala/Api.scala"), "package example; object Api { def value: Int = 99 }\n")
      _write(secondpublisher, _input(producer, owner, "producer", "scope-2", "pub-1", Vector.empty).noSpaces)
      _run(producer, owner.resolve("producer-second.log"), Some(secondpublisher), "smDevelopmentArtifactPublish") shouldBe 0
      val second = Files.createDirectory(owner.resolve("direct-second"))
      _setup(second, "enablePlugins(org.goldenport.cozy.CozyDevelopmentArtifactsPlugin)\nscalaVersion := \"2.12.20\"\n" +
        "libraryDependencies += cozyDevelopmentDependency(\"example.overlay\" %% \"api\" % \"1.0.0-SNAPSHOT\").value\n" +
        "cozyDevelopmentModules += (\"example.overlay\" %% \"api\" % \"1.0.0-SNAPSHOT\")\n" +
        "libraryDependencies += \"org.scalatest\" %% \"scalatest\" % \"3.2.19\" % Test\n")
      _write(second.resolve("src/test/scala/UseSpec.scala"), "class UseSpec extends org.scalatest.funsuite.AnyFunSuite { test(\"second scope\") { assert(example.Api.value == 99) } }\n")
      val secondinput = owner.resolve("direct-second.json")
      _write(secondinput, _input(second, owner, "consumer", "scope-2", "pub-1", Vector(_mapping(owner, "scope-2", "pub-1"))).noSpaces)
      // Give concurrent consumers distinct result paths as well as distinct scopes.
      _write(directinput, _input(direct, owner, "consumer", "scope-1", "pub-1", Vector(_mapping(owner, "scope-1", "pub-1"))).
        deepMerge(Json.obj("cozyArtifact" -> Json.obj("resultFile" -> Json.fromString(owner.resolve("direct-result.json").toString)))).noSpaces)
      _write(secondinput, _input(second, owner, "consumer", "scope-2", "pub-1", Vector(_mapping(owner, "scope-2", "pub-1"))).
        deepMerge(Json.obj("cozyArtifact" -> Json.obj("resultFile" -> Json.fromString(owner.resolve("direct-second-result.json").toString)))).noSpaces)
      val consumerresults = _runpair(direct, owner.resolve("direct-warm.log"), directinput,
        second, owner.resolve("direct-second.log"), secondinput, "test")
      consumerresults shouldBe ((0, 0))
      val firstresult = new String(Files.readAllBytes(owner.resolve("direct-result.json")), StandardCharsets.UTF_8)
      val secondresult = new String(Files.readAllBytes(owner.resolve("direct-second-result.json")), StandardCharsets.UTF_8)
      firstresult should include ("scope-1")
      secondresult should include ("scope-2")
      val ordinarybase = ordinary.resolve("example.overlay/api_2.12/1.0.0-SNAPSHOT")
      Files.createDirectories(ordinarybase.resolve("jars"))
      Files.createDirectories(ordinarybase.resolve("ivys"))
      Files.copy(selectedbase.resolve("jars/api_2.12.jar"), ordinarybase.resolve("jars/api_2.12.jar"), StandardCopyOption.REPLACE_EXISTING)
      _write(ordinarybase.resolve("ivys/ivy.xml"), "<ivy-module version=\"2.0\"><info organisation=\"example.overlay\" module=\"api_2.12\" revision=\"1.0.0-SNAPSHOT\"/><configurations><conf name=\"default\"/></configurations><publications><artifact name=\"api_2.12\" type=\"jar\" ext=\"jar\" conf=\"default\"/></publications></ivy-module>")
      val bridge = Files.createDirectory(owner.resolve("bridge"))
      val bridgedev = "1.0.0-SNAPSHOT-smdev-7-scope-1-5-pub-2"
      val bridgebase = repository.resolve("example.overlay/bridge_2.12").resolve(bridgedev)
      val bridgebuild = "enablePlugins(org.goldenport.cozy.CozyDevelopmentArtifactsPlugin)\norganization := \"example.overlay\"\nname := \"bridge\"\nversion := \"1.0.0-SNAPSHOT\"\nscalaVersion := \"2.12.20\"\nuseCoursier := false\n" +
        "resolvers += Resolver.file(\"ordinary\", file(" + _quoted(ordinary.toString) + "))(Resolver.ivyStylePatterns)\n" +
        "libraryDependencies += cozyDevelopmentDependency(\"example.overlay\" %% \"api\" % \"1.0.0-SNAPSHOT\").value\n" +
        "cozyDevelopmentModules += (\"example.overlay\" %% \"api\" % \"1.0.0-SNAPSHOT\")\n"
      _setup(bridge, bridgebuild)
      _write(bridge.resolve("src/main/scala/Bridge.scala"), "package example; object Bridge { def value: Int = Api.value }\n")
      val bridgeproducer = _coordinate(bridgedev, "bridge_2.12").deepMerge(Json.obj(
        "jar" -> Json.fromString(bridgebase.resolve("jars/bridge_2.12.jar").toString),
        "ivy" -> Json.fromString(bridgebase.resolve("ivys/ivy.xml").toString)))
      val bridgeinput = owner.resolve("bridge.json")
      val bridgejson = _input(bridge, owner, "producer", "scope-1", "pub-2", Vector(_mapping(owner, "scope-1", "pub-1"))).
        deepMerge(Json.obj("cozyArtifact" -> Json.obj("producer" -> bridgeproducer)))
      _write(bridgeinput, bridgejson.noSpaces)
      _run(bridge, owner.resolve("bridge.log"), Some(bridgeinput), "smDevelopmentArtifactPublish") shouldBe 0
      new String(Files.readAllBytes(bridgebase.resolve("ivys/ivy.xml")), StandardCharsets.UTF_8) should include ("1.0.0-SNAPSHOT-smdev-7-scope-1-5-pub-1")
      val ordinarybridge = ordinary.resolve("example.overlay/bridge_2.12/1.0.0-SNAPSHOT")
      Files.createDirectories(ordinarybridge.resolve("jars"))
      Files.createDirectories(ordinarybridge.resolve("ivys"))
      Files.copy(bridgebase.resolve("jars/bridge_2.12.jar"), ordinarybridge.resolve("jars/bridge_2.12.jar"))
      _write(ordinarybridge.resolve("ivys/ivy.xml"), "<ivy-module version=\"2.0\"><info organisation=\"example.overlay\" module=\"bridge_2.12\" revision=\"1.0.0-SNAPSHOT\"/><configurations><conf name=\"default\"/></configurations><publications><artifact name=\"bridge_2.12\" type=\"jar\" ext=\"jar\" conf=\"default\"/></publications><dependencies><dependency org=\"example.overlay\" name=\"api_2.12\" rev=\"1.0.0-SNAPSHOT\" conf=\"default->default\"/></dependencies></ivy-module>")
      val build = "enablePlugins(org.goldenport.cozy.CozyDevelopmentArtifactsPlugin)\nscalaVersion := \"2.12.20\"\nuseCoursier := false\n" +
        "resolvers += Resolver.file(\"ordinary\", file(" + _quoted(ordinary.toString) + "))(Resolver.ivyStylePatterns)\n" +
        "libraryDependencies += cozyDevelopmentDependency(\"example.overlay\" %% \"bridge\" % \"1.0.0-SNAPSHOT\").value\n" +
        "cozyDevelopmentModules ++= Seq(\"example.overlay\" %% \"api\" % \"1.0.0-SNAPSHOT\", \"example.overlay\" %% \"bridge\" % \"1.0.0-SNAPSHOT\")\n"
      _setup(consumer, build)
      _write(consumer.resolve("src/main/scala/Use.scala"), "object Use { val n: Int = example.Bridge.value }\n")
      val consumerinput = owner.resolve("consumer.json")
      val mappings = Vector(_mapping(owner, "scope-1", "pub-1"), _mapping(owner, "scope-1", "pub-2", "bridge_2.12"))
      _write(consumerinput, _input(consumer, owner, "consumer", "scope-1", "pub-1", mappings).noSpaces)
      When("the same consumer compiles ordinarily and with the explicit selection")
      _run(consumer, owner.resolve("ordinary.log"), None, "compile") shouldBe 0
      _run(consumer, owner.resolve("selected.log"), Some(consumerinput), "compile") shouldBe 0
      Then("the actual selected resolution report identifies the development coordinate")
      Files.readAllBytes(consumer.resolve("build.sbt")) shouldBe build.getBytes(StandardCharsets.UTF_8)
      val result = new String(Files.readAllBytes(owner.resolve("consumer-result.json")), StandardCharsets.UTF_8)
      result should include ("1.0.0-SNAPSHOT-smdev-7-scope-1-5-pub-1")
      result should include ("1.0.0-SNAPSHOT-smdev-7-scope-1-5-pub-2")
      val invalid = owner.resolve("invalid.json")
      _write(invalid, _input(consumer, owner, "consumer", "foreign", "pub-1", Vector(_mapping(owner, "scope-1", "pub-1"))).noSpaces)
      _run(consumer, owner.resolve("invalid.log"), Some(invalid), "compile") should not be 0
      val consumer2 = Files.createDirectory(owner.resolve("consumer2"))
      _setup(consumer2, build)
      _write(consumer2.resolve("src/main/scala/Use.scala"), "object Use { val n: Int = example.Bridge.value }\n")
      _run(consumer2, owner.resolve("foreign-build.log"), Some(consumerinput), "compile") should not be 0
      val consumer2input = owner.resolve("consumer2.json")
      _write(consumer2input, _input(consumer2, owner, "consumer", "scope-1", "pub-1", mappings).noSpaces)
      val marker = selectedbase.resolve("completed.json")
      val suspended = selectedbase.resolve("suspended.json")
      Files.move(marker, suspended)
      _run(consumer2, owner.resolve("incomplete.log"), Some(consumer2input), "compile") should not be 0
      Files.move(suspended, marker)
      _run(consumer2, owner.resolve("consumer2.log"), Some(consumer2input), "compile") shouldBe 0
      // Keep selected coordinates resolvable even after the build replaces the resolver list,
      // so the failure specifically exercises validation of the missing private resolver.
      Vector(selectedbase, bridgebase).foreach { source =>
        val destination = ordinary.resolve(repository.relativize(source))
        Files.createDirectories(destination.resolve("jars"))
        Files.createDirectories(destination.resolve("ivys"))
        val module = source.getParent.getFileName.toString
        Files.copy(source.resolve("jars").resolve(module + ".jar"), destination.resolve("jars").resolve(module + ".jar"))
        Files.copy(source.resolve("ivys/ivy.xml"), destination.resolve("ivys/ivy.xml"))
      }
      val missingresolver = Files.createDirectory(owner.resolve("missing-resolver"))
      val resetresolver = "resolvers := Seq(Resolver.file(\"ordinary\", file(" + _quoted(ordinary.toString) + "))(Resolver.ivyStylePatterns))\n"
      _setup(missingresolver, build + resetresolver)
      _write(missingresolver.resolve("src/main/scala/Use.scala"), "object Use { val n: Int = example.Bridge.value }\n")
      val missingresolverinput = owner.resolve("missing-resolver.json")
      _write(missingresolverinput, _input(missingresolver, owner, "consumer", "scope-1", "pub-1", mappings).noSpaces)
      val missingresolverlog = owner.resolve("missing-resolver.log")
      _run(missingresolver, missingresolverlog, Some(missingresolverinput), "compile") should not be 0
      new String(Files.readAllBytes(missingresolverlog), StandardCharsets.UTF_8) should include ("private resolver is missing")
      val conflict = Files.createDirectory(owner.resolve("conflict"))
      _setup(conflict, build + "dependencyOverrides += \"example.overlay\" %% \"api\" % \"other\"\n")
      _write(conflict.resolve("src/main/scala/Use.scala"), "object Use { val n: Int = example.Bridge.value }\n")
      val conflictinput = owner.resolve("conflict.json")
      _write(conflictinput, _input(conflict, owner, "consumer", "scope-1", "pub-1", mappings).noSpaces)
      _run(conflict, owner.resolve("conflict.log"), Some(conflictinput), "compile") should not be 0
      _snapshot(shared) shouldBe sharedbefore
    }
  }
}
