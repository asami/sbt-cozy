package fixture

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.concurrent.{Callable, Executors}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

import scala.collection.JavaConverters._

import cozy.Cozy

/*
 * RSC09-AC-01: package parent, Documentation, SourceCode, and an independently
 * described external-platform child through the real Cozy release seam.
 * RSC09-AC-02: exercise deterministic rejection before output for restricted,
 * missing, incompatible, duplicate, unsafe, stale, and corrupt inputs.
 * RSC09-AC-03: publish the same identities to the ordinary CAR repository and
 * leave resolver/cache evidence for the sbt-cozy online/offline task seam.
 *
 * @since   Aug. 22, 2026
 * @version Aug. 22, 2026
 * @author  ASAMI, Tomoharu
 */
object ComponentSubcomponentEndToEndProbe {
  private val _namespace = "org.goldenport.cncf.phase58"
  private val _version = "0.1.0-SNAPSHOT"
  private val _published_at = "2026-08-22T00:00:00Z"
  private val _schema = "cncf.component-subcomponent-composition.v1"
  private val _parent_id = s"${_namespace}.RscParent"
  private val _documentation_id = s"${_namespace}.RscDocumentation"
  private val _source_code_id = s"${_namespace}.RscSourceCode"
  private val _web_presentation_id = s"${_namespace}.RscWebPresentation"
  private val _parent_signature = "cGFyZW50LXNpZw=="
  private val _documentation_signature = "ZG9jdW1lbnRhdGlvbi1zaWc="
  private val _source_code_signature = "c291cmNlLXNpZw=="
  private val _web_presentation_signature = "d2ViLXNpZw=="
  private final case class Fixture(
    parentproject: Path,
    parentcar: Path,
    documentationcar: Path,
    sourcecodecar: Path,
    webpresentationcar: Path,
    composition: Path,
    incompatiblecar: Path,
    corruptcar: Path
  ) {
    def childcars: Vector[Path] = Vector(documentationcar, sourcecodecar, webpresentationcar)
  }

  def main(args: Array[String]): Unit = {
    val root = Paths.get(
      sys.props.getOrElse("rsc09.fixture.root", sys.error("rsc09.fixture.root is required"))
    ).toAbsolutePath.normalize()
    Files.createDirectories(root)
    args.toVector match {
      case Vector() => _run_online(root)
      case other => sys.error(s"RSC09 unsupported probe arguments: ${other.mkString(" ")}")
    }
  }

  private def _run_online(root: Path): Unit = {
    // Given a fresh deterministic fixture with one parent and three child CARs,
    // prepare the release and repository output locations.
    val fixture = _fixture(root)
    val release = root.resolve("release/rsc-parent.release.car")
    val integrity = root.resolve("release/rsc-parent.release.integrity")
    val releasewarehouse = root.resolve("release-warehouse")
    val warehouse = root.resolve("warehouse")

    // When Cozy packages the parent composition through the release seam,
    // produce the primary release and its integrity evidence.
    Cozy.main(_package_arguments(fixture, release, integrity, fixture.childcars))
    // Then the package and integrity outputs exist for the observable checks.
    _require(Files.isRegularFile(release), "RSC09 package CAR is missing")
    _require(Files.isRegularFile(integrity), "RSC09 package integrity evidence is missing")

    // When the same package action is repeated with the same inputs,
    // produce a second release and integrity pair for determinism observation.
    val repeatedrelease = root.resolve("release/rsc-parent.repeated.car")
    val repeatedintegrity = root.resolve("release/rsc-parent.repeated.integrity")
    Cozy.main(_package_arguments(fixture, repeatedrelease, repeatedintegrity, fixture.childcars))
    // Then repeated package bytes, archive contents, provenance, and all four
    // CAR digests match the frozen release contract.
    _require(Files.readAllBytes(release).sameElements(Files.readAllBytes(repeatedrelease)), "repeated package CAR bytes disagree")
    _require(Files.readAllBytes(integrity).sameElements(Files.readAllBytes(repeatedintegrity)), "repeated package integrity bytes disagree")
    _assert_packaged_release(release, integrity, fixture)
    _assert_development_provenance(fixture)

    // RSC09-AC-02: every hostile input is rejected before a usable output path
    // becomes visible. Each case owns a fresh output pair.
    // Given those hostile inputs and fresh output pairs, when Cozy attempts each
    // package action, then every rejection leaves no usable release artifacts.
    _assert_rejection_matrix(root, fixture)

    // Given the verified release and integrity artifacts, when Cozy publishes
    // them through the release seam, preserve the release bytes and admission evidence.
    Cozy.main(_publish_release_arguments(fixture, release, integrity, releasewarehouse))
    // Then the published release remains byte-identical and admitted by Cozy.
    _assert_published_release(releasewarehouse, release, integrity)

    // RSC09-AC-03 ordinary CAR publication feeds the sbt-cozy resolver task;
    // the build-scoped task then removes this repository and reuses its cache.
    // Given the release fixture, when each child and the parent CAR are published
    // to the ordinary warehouse, populate all four resolver identities.
    fixture.childcars.zip(Vector("RscDocumentation", "RscSourceCode", "RscWebPresentation")).foreach {
      case (car, id) => _publish_car(fixture, car, id, warehouse)
    }
    _publish_car(fixture, fixture.parentcar, "RscParent", warehouse)
    // Then repository membership exposes all four CAR archives without changing bytes.
    _assert_repository_membership(warehouse, fixture)

    // The build-scoped offline probe later admits only the primary CAR;
    // no deployment fallback marker may be created by this fixture.
    // Then offline resolution and concurrent reads observe explicit external
    // deployment semantics without activation, fallback, or mutation.
    _require(!Files.exists(root.resolve("cncf-deployment-fallback.marker")), "external deployment fell back to CNCF")

    // A shared immutable release remains deterministic under concurrent reads;
    // no read creates activation, operation, MCP, or disclosure authority.
    _assert_concurrent_release_reads(release)
    _write(root.resolve("rsc09-probe-evidence.txt"), "RSC09-AC-01\nRSC09-AC-02\nRSC09-AC-03\n")
  }

  private def _fixture(root: Path): Fixture = {
    val parentproject = _project(root.resolve("development/parent"), "RscParent", "rsc-parent")
    val documentationproject = _project(root.resolve("development/documentation"), "RscDocumentation", "rsc-documentation")
    val sourcecodeproject = _project(root.resolve("development/source-code"), "RscSourceCode", "rsc-source-code")
    val webpresentationproject = _project(root.resolve("development/web-presentation"), "RscWebPresentation", "rsc-web-presentation")

    val parentcar = _car(
      root.resolve("inputs/rsc-parent.car"),
      "RscParent",
      "parent-primary-payload"
    )
    val documentationcar = _car(
      root.resolve("inputs/rsc-documentation.car"),
      "RscDocumentation",
      "documentation-payload"
    )
    val sourcecodecar = _car(
      root.resolve("inputs/rsc-source-code.car"),
      "RscSourceCode",
      "source-code-payload"
    )
    val webpresentationcar = _car(
      root.resolve("inputs/rsc-web-presentation.car"),
      "RscWebPresentation",
      "external-platform-payload"
    )
    val incompatiblecar = _car(
      root.resolve("inputs/rsc-incompatible.car"),
      "RscIncompatible",
      "incompatible-payload"
    )
    val corruptcar = _write(root.resolve("inputs/rsc-corrupt.car"), "not-a-canonical-car\n")
    val children = Vector(documentationcar, sourcecodecar, webpresentationcar)
    val composition = _write(
      root.resolve("inputs/composition.json"),
      _composition(parentcar, children)
    )
    Fixture(
      parentproject,
      parentcar,
      documentationcar,
      sourcecodecar,
      webpresentationcar,
      composition,
      incompatiblecar,
      corruptcar
    )
  }

  private def _project(root: Path, id: String, name: String): Path = {
    _write(
      root.resolve("project.yaml"),
      s"""project:
         |  namespace: ${_namespace}
         |  id: $id
         |  name: $name
         |  component:
         |    version: ${_version}
         |cml:
         |  source: "src/main/cozy/$name.cml"
         |""".stripMargin
    )
    _write(root.resolve(s"src/main/cozy/$name.cml"), s"# RSC09 development-directory source for $id\n")
    root
  }

  private def _car(path: Path, id: String, marker: String): Path =
    _archive(
      path,
      Vector(
        "component-descriptor.json" -> _descriptor(id),
        "abi-manifest.json" -> _abi(id),
        "payload/marker.txt" -> marker
      )
    )

  private def _package_arguments(
    fixture: Fixture,
    output: Path,
    integrity: Path,
    childcars: Vector[Path]
  ): Array[String] =
    _package_arguments(fixture, output, integrity, childcars, fixture.composition)

  private def _package_arguments(
    fixture: Fixture,
    output: Path,
    integrity: Path,
    childcars: Vector[Path],
    composition: Path
  ): Array[String] = {
    (Vector(
      "package-subcomponent-release",
      "--project-dir", fixture.parentproject.toString,
      "--parent-car", fixture.parentcar.toString,
      "--composition", composition.toString,
      "--save", output.toString,
      "--integrity", integrity.toString,
      "--published-at", _published_at,
      "--required-child", _documentation_id,
      "--required-child", _source_code_id
    ) ++ childcars.flatMap(car => Vector("--child-car", car.toString))).toArray
  }

  private def _publish_release_arguments(
    fixture: Fixture,
    release: Path,
    integrity: Path,
    warehouse: Path
  ): Array[String] = Array(
    "publish-subcomponent-release",
    "--project-dir", fixture.parentproject.toString,
    "--warehouse", warehouse.toString,
    "--release", release.toString,
    "--integrity", integrity.toString,
    "--published-at", _published_at
  )

  private def _publish_car(fixture: Fixture, car: Path, id: String, warehouse: Path): Unit = {
    val project = id match {
      case "RscParent" => fixture.parentproject
      case "RscDocumentation" => fixture.parentproject.resolveSibling("documentation")
      case "RscSourceCode" => fixture.parentproject.resolveSibling("source-code")
      case "RscWebPresentation" => fixture.parentproject.resolveSibling("web-presentation")
      case other => sys.error(s"RSC09 unknown publication identity: $other")
    }
    Cozy.main(Array(
      "publish-car",
      project.toString,
      "--warehouse", warehouse.toString,
      "--name", _maven_artifact(id),
      "--version", _version,
      "--car", car.toString,
      "--published-at", _published_at
    ))
  }

  private def _composition(parentcar: Path, childcars: Vector[Path]): String = {
    val documentation = childcars(0)
    val sourcecode = childcars(1)
    val webpresentation = childcars(2)
    s"""{"schema":"${_schema}","membershipKind":"parent-child","parent":${_parent_composition(parentcar)},"members":[${_member_composition("RscDocumentation", true, "Documentation", "documentation", "documentation/README.md", documentation, _documentation_signature)},${_member_composition("RscSourceCode", true, "SourceCode", "source-code", "source-code/release-inputs.txt", sourcecode, _source_code_signature)},${_member_composition("RscWebPresentation", false, "presentation", "external-platform", "external/platform.json", webpresentation, _web_presentation_signature)}],"fixtureExtension":{"profileIds":["embedded-primary","parent-documentation-source","parent-external-platform","restricted-source","development-override","repository-online-offline","production-primary-only","failure-matrix","lifecycle-concurrency"],"production":{"primaryOnly":true,"externalPlatform":"explicit-only","cncfFallback":false}}}"""
  }

  private def _parent_composition(parentcar: Path): String =
    s"""{"componentId":"${_parent_id}","logicalRelease":"${_version}","primaryCar":${_car_composition("primary", "RscParent", parentcar, _parent_signature)}}"""

  private def _member_composition(
    id: String,
    required: Boolean,
    role: String,
    resource: String,
    logicalpath: String,
    car: Path,
    signature: String
  ): String =
    s"""{"componentId":"${_namespace}.${id}","logicalRelease":"${_version}","required":${required},"role":"${role}","implementationTechnology":"CAR","logicalResource":"https://example.test/phase58/${resource}","logicalPath":"${logicalpath}","subcomponentCar":${_car_composition("subcomponent", id, car, signature)},"payload":{"authoritative":false,"executable":false},"authorization":{"state":"not-granted"},"integrity":{"state":"verified"},"availability":{"state":"available"},"deployment":{"platform":"external-platform","mode":"external","requiresExplicitPlatformAction":true,"authority":{"activation":false,"operation":false,"mcp":false,"disclosure":false,"deployment":false}},"access":{"visibility":"public"},"disclosure":{"mode":"metadata-only"},"license":{"spdx":"Apache-2.0"},"media":{"type":"application/vnd.cozy.car"},"profile":{"id":"phase58"}}"""

  private def _car_composition(classification: String, id: String, car: Path, signature: String): String =
    s"""{"classification":"${classification}","artifact":{"coordinate":"org.example:${_physical_artifact(id)}-car:${_version}","sha256":"${_sha256(car)}","signature":"${signature}","repository":"https://repository.example.test/releases","physicalPath":"inputs/${_physical_artifact(id)}.car"},"provenance":{"logicalSource":"git:phase58/rsc09","physicalSource":"development-directory:phase58/rsc09","physicalPath":"src/main/cozy/${_physical_artifact(id)}.cml"}}"""

  private def _assert_packaged_release(release: Path, integrity: Path, fixture: Fixture): Unit = {
    val entries = _zip_texts(release)
    val text = entries.mkString("\n")
    Vector(
      "release-manifest.json",
      "composition.json",
      "payload/parent.car",
      s"payload/children/${_documentation_id}.car",
      s"payload/children/${_source_code_id}.car",
      s"payload/children/${_web_presentation_id}.car"
    ).foreach { entry =>
      _require(_zip_entry_names(release).contains(entry), s"release is missing $entry")
    }
    Vector(_parent_id, _documentation_id, _source_code_id, _web_presentation_id, _schema).foreach { value =>
      _require(text.contains(value), s"release evidence lacks $value")
    }
    Vector(
      "Documentation",
      "SourceCode",
      "presentation",
      "development-directory",
      "requiresExplicitPlatformAction",
      "\"deployment\":false",
      "primaryOnly",
      "cncfFallback"
    ).foreach(value => _require(text.contains(value), s"release evidence lacks composition contract $value"))
    _require(!text.contains(System.getProperty("user.home", "__no-home__")), "release evidence embeds developer home")
    val integritytext = Files.readString(integrity, StandardCharsets.UTF_8)
    Vector(_sha256(fixture.parentcar), _sha256(fixture.documentationcar), _sha256(fixture.sourcecodecar), _sha256(fixture.webpresentationcar)).foreach { digest =>
      _require(integritytext.contains(digest), s"integrity evidence lacks child digest $digest")
    }
  }

  private def _assert_development_provenance(fixture: Fixture): Unit = {
    val text = Files.readString(fixture.composition, StandardCharsets.UTF_8)
    _require(text.contains("\"physicalSource\":\"development-directory:phase58/rsc09\""), "development provenance is missing")
    _require(text.contains("\"physicalPath\":\"src/main/cozy/"), "development provenance path is not relative")
    _require(!text.contains(System.getProperty("user.home", "__no-home__")), "composition embeds developer home")
    _require(text.contains("org.example:rsc-documentation-car"), "logical identity collapsed into physical artifact")
  }

  private def _assert_rejection_matrix(root: Path, fixture: Fixture): Unit = {
    val valid = Files.readString(fixture.composition, StandardCharsets.UTF_8)
    val cases = Vector(
      ("restricted", Some(_write(root.resolve("inputs/restricted.json"), valid.replace("\"state\":\"not-granted\"", "\"state\":\"restricted\""))), fixture.childcars),
      ("missing", Some(fixture.composition), Vector(fixture.documentationcar, fixture.webpresentationcar)),
      ("unsafe", Some(_write(root.resolve("inputs/unsafe.json"), valid.replace("\"logicalPath\":\"source-code/release-inputs.txt\"", "\"logicalPath\":\"../escape\""))), fixture.childcars),
      ("stale", Some(_write(root.resolve("inputs/stale.json"), valid.replace(_sha256(fixture.sourcecodecar), "0" * 64))), fixture.childcars),
      ("duplicate", Some(fixture.composition), fixture.childcars :+ fixture.documentationcar),
      ("incompatible", Some(fixture.composition), Vector(fixture.documentationcar, fixture.incompatiblecar, fixture.webpresentationcar)),
      ("corrupt", Some(fixture.composition), Vector(fixture.documentationcar, fixture.sourcecodecar, fixture.corruptcar))
    )
    cases.foreach { case (label, composition, children) =>
      val output = root.resolve(s"rejections/$label.car")
      val integrity = root.resolve(s"rejections/$label.integrity")
      Files.deleteIfExists(output)
      Files.deleteIfExists(integrity)
      val rejected = try {
        Cozy.main(_package_arguments(fixture, output, integrity, children, composition.get))
        false
      } catch {
        case _: Throwable => true
      }
      _require(rejected, s"RSC09 $label input was admitted")
      _require(!Files.exists(output) && !Files.exists(integrity), s"RSC09 $label wrote usable output")
    }
  }

  private def _assert_published_release(warehouse: Path, release: Path, integrity: Path): Unit = {
    val root = warehouse.resolve("repository/subcomponent-release/org/goldenport/cncf/phase58/phase58-rsc-parent/0.1.0-SNAPSHOT")
    val archive = root.resolve("phase58-rsc-parent-0.1.0-SNAPSHOT.release.car")
    val evidence = root.resolve("phase58-rsc-parent-0.1.0-SNAPSHOT.release.integrity")
    val admission = root.resolve("admission.json")
    Vector(archive, evidence, admission).foreach(path => _require(Files.isRegularFile(path), s"published release is missing $path"))
    _require(Files.readAllBytes(archive).sameElements(Files.readAllBytes(release)), "published release bytes changed")
    _require(Files.readAllBytes(evidence).sameElements(Files.readAllBytes(integrity)), "published integrity bytes changed")
    _require(Files.readString(admission, StandardCharsets.UTF_8).contains("cozy.subcomponent-release-admission.v1"), "release admission marker is invalid")
  }

  private def _assert_repository_membership(warehouse: Path, fixture: Fixture): Unit = {
    val repository = warehouse.resolve("repository/car")
    Vector(
      "RscParent" -> fixture.parentcar,
      "RscDocumentation" -> fixture.documentationcar,
      "RscSourceCode" -> fixture.sourcecodecar,
      "RscWebPresentation" -> fixture.webpresentationcar
    ).foreach { case (id, source) =>
      val path = repository.resolve("org/goldenport/cncf/phase58").resolve(_maven_artifact(id)).resolve(_version).resolve(s"${_maven_artifact(id)}-${_version}.car")
      _require(Files.isRegularFile(path), s"ordinary repository is missing ${id}")
      _require(Files.readAllBytes(path).sameElements(Files.readAllBytes(source)), s"ordinary repository changed ${id} bytes")
    }
  }

  private def _assert_concurrent_release_reads(release: Path): Unit = {
    val executor = Executors.newFixedThreadPool(4)
    try {
      val tasks = (0 until 8).map(_ => new Callable[Vector[Byte]] {
        override def call(): Vector[Byte] = Files.readAllBytes(release).toVector
      })
      val values = tasks.map(executor.submit(_)).map(_.get()).toVector
      _require(values.distinct.size == 1, "concurrent release reads disagreed")
    } finally executor.shutdown()
  }

  private def _require(condition: Boolean, message: String): Unit =
    if (!condition) sys.error(message)

  private def _physical_artifact(id: String): String =
    id.foldLeft("") { (result, character) =>
      if (character.isUpper && result.nonEmpty) s"$result-${character.toLower}"
      else s"$result${character.toLower}"
    }

  private def _maven_artifact(id: String): String = s"phase58-${_physical_artifact(id)}"

  private def _descriptor(id: String): String =
    s"""{"schemaVersion":3,"component":{"namespace":"${_namespace}","id":"$id","version":"${_version}"}}"""

  private def _abi(id: String): String =
    s"""{"format":"cozy.car.abi-manifest.v2","component":{"namespace":"${_namespace}","id":"$id","version":"${_version}"},"abi":{"version":1,"exports":{"components":[{"namespace":"${_namespace}","id":"$id"}],"services":[],"operations":[],"types":[],"entities":[]},"dependencies":[]}}"""

  private def _archive(path: Path, entries: Vector[(String, String)]): Path = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    val output = new ZipOutputStream(Files.newOutputStream(path))
    try entries.sortBy(_._1).foreach { case (name, value) =>
      val entry = new ZipEntry(name)
      entry.setTime(0L)
      output.putNextEntry(entry)
      output.write(value.getBytes(StandardCharsets.UTF_8))
      output.closeEntry()
    } finally output.close()
    path
  }

  private def _zip_entry_names(path: Path): Vector[String] = {
    val input = new ZipInputStream(Files.newInputStream(path))
    val names = scala.collection.mutable.ArrayBuffer.empty[String]
    try {
      var entry = input.getNextEntry
      while (entry != null) {
        names += entry.getName
        entry = input.getNextEntry
      }
    } finally input.close()
    names.toVector
  }

  private def _zip_texts(path: Path): Vector[String] = {
    val input = new ZipInputStream(Files.newInputStream(path))
    val texts = scala.collection.mutable.ArrayBuffer.empty[String]
    try {
      var entry = input.getNextEntry
      while (entry != null) {
        texts += new String(input.readAllBytes(), StandardCharsets.UTF_8)
        entry = input.getNextEntry
      }
    } finally input.close()
    texts.toVector
  }

  private def _sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).map(byte => f"${byte & 0xff}%02x").mkString

  private def _write(path: Path, content: String): Path = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    path
  }
}
