package fixture

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

import scala.collection.JavaConverters._

import cozy.Cozy

/*
 * @since   Aug. 20, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */
object SubcomponentReleasePublicationProbe {
  private val _namespace = "org.goldenport.cncf.phase58"
  private val _version = "0.1.0-SNAPSHOT"
  private val _parent_id = s"${_namespace}.RscParent"
  private val _documentation_id = s"${_namespace}.RscDocumentation"
  private val _source_code_id = s"${_namespace}.RscSourceCode"
  private val _web_presentation_id = s"${_namespace}.RscWebPresentation"
  private val _documentation_payload_marker = "rsc03-documentation-payload"
  private val _source_code_payload_marker = "rsc03-source-code-payload"
  private val _web_presentation_payload_marker = "rsc03-external-platform-presentation"
  private val _composition_schema = "cncf.component-subcomponent-composition.v1"
  private val _documentation_role = "Documentation"
  private val _source_code_role = "SourceCode"
  private val _web_presentation_role = "ExternalPlatformPresentation"
  private val _parent_signature = "cGFyZW50LXNpZw=="
  private val _documentation_signature = "ZG9jdW1lbnRhdGlvbi1zaWc="
  private val _source_code_signature = "c291cmNlLXNpZw=="
  private val _web_presentation_signature = "d2ViLXNpZw=="
  private val _published_at = "2026-08-20T00:00:00Z"

  private final case class Fixture(
    parentproject: Path,
    parentcar: Path,
    childcars: Vector[Path],
    composition: Path
  )

  def main(args: Array[String]): Unit = {
    val root = Paths.get(
      sys.props.getOrElse("phase58.fixture.root", sys.error("phase58.fixture.root is required"))
    ).toAbsolutePath.normalize()
    Files.createDirectories(root)
    val fixture = _fixture(root)
    val first = root.resolve("package-first.car")
    val firstintegrity = root.resolve("package-first.integrity")
    val second = root.resolve("package-second.car")
    val secondintegrity = root.resolve("package-second.integrity")
    val localwarehouse = root.resolve("local-warehouse")
    val remotewarehouse = root.resolve("remote-warehouse")

    Cozy.main(_package_arguments(fixture, first, firstintegrity, childcars = fixture.childcars))
    Cozy.main(_package_arguments(fixture, second, secondintegrity, childcars = fixture.childcars))
    _require(Files.isRegularFile(first), s"first package CAR is missing: $first")
    _require(Files.isRegularFile(second), s"second package CAR is missing: $second")
    _require(Files.isRegularFile(firstintegrity), s"first integrity evidence is missing: $firstintegrity")
    _require(Files.isRegularFile(secondintegrity), s"second integrity evidence is missing: $secondintegrity")
    val firstbytes = Files.readAllBytes(first).toVector
    val secondbytes = Files.readAllBytes(second).toVector
    _require(firstbytes == secondbytes, "repeated package CAR bytes disagree")
    val firstevidence = Files.readString(firstintegrity, StandardCharsets.UTF_8)
    val secondevidence = Files.readString(secondintegrity, StandardCharsets.UTF_8)
    _require(firstevidence == secondevidence, "repeated package integrity evidence disagrees")
    Vector(_parent_id, _documentation_id, _source_code_id, _web_presentation_id).foreach { identity =>
      _require(firstevidence.contains(identity), s"package integrity evidence lacks $identity")
    }
    Vector(
      _composition_schema,
      _sha256(fixture.composition),
      _parent_signature,
      _documentation_signature,
      _source_code_signature,
      _web_presentation_signature
    ).foreach { evidence =>
      _require(firstevidence.contains(evidence), s"package integrity evidence lacks composition binding $evidence")
    }
    (fixture.parentcar +: fixture.childcars).map(_sha256).foreach { digest =>
      _require(firstevidence.contains(digest), s"package integrity evidence lacks digest $digest")
    }
    val packagedtexts = _zip_texts(first).mkString("\n")
    Vector(
      _documentation_payload_marker,
      _source_code_payload_marker,
      _web_presentation_payload_marker
    ).foreach { marker =>
      _require(packagedtexts.contains(marker), s"package CAR lacks payload evidence $marker")
    }
    Vector(_composition_schema, _documentation_role, _source_code_role, _web_presentation_role).foreach { value =>
      _require(packagedtexts.contains(value), s"package CAR lacks composition metadata $value")
    }
    _require(packagedtexts.contains("\"fixtureExtension\":{\"accepted\":true}"), "package CAR changed allowed composition extension")

    val incomplete = root.resolve("incomplete.car")
    val incompleteintegrity = root.resolve("incomplete.integrity")
    val incompleterejected = try {
      Cozy.main(_package_arguments(fixture, incomplete, incompleteintegrity, childcars = Vector(fixture.childcars.head)))
      false
    } catch {
      case _: Throwable => true
    }
    _require(incompleterejected, "missing required composition member was admitted")
    _require(!Files.exists(incomplete) && !Files.exists(incompleteintegrity), "rejected member set wrote output")

    _hostile_compositions(root, fixture).foreach { case (label, compositionpath) =>
      val output = root.resolve(s"$label.car")
      val integrity = root.resolve(s"$label.integrity")
      val rejected = try {
        Cozy.main(
          _package_arguments(
            fixture,
            output,
            integrity,
            childcars = fixture.childcars,
            compositionpath = Some(compositionpath)
          )
        )
        false
      } catch {
        case _: Throwable => true
      }
      _require(rejected, s"hostile composition was admitted: $label")
      _require(!Files.exists(output) && !Files.exists(integrity), s"hostile composition wrote output: $label")
    }

    val optional = root.resolve("optional-omitted.car")
    val optionalintegrity = root.resolve("optional-omitted.integrity")
    Cozy.main(_package_arguments(fixture, optional, optionalintegrity, childcars = fixture.childcars.take(2)))
    val optionaltexts = _zip_texts(optional).mkString("\n")
    _require(optionaltexts.contains(_web_presentation_role), "optional metadata was not copied")
    _require(!optionaltexts.contains(_web_presentation_payload_marker), "omitted optional payload was packaged")

    Cozy.main(_publish_arguments(fixture, first, firstintegrity, localwarehouse))
    Cozy.main(_publish_arguments(fixture, second, secondintegrity, remotewarehouse))
    val localbytes = _tree_bytes(localwarehouse)
    val remotebytes = _tree_bytes(remotewarehouse)
    _require(localbytes.nonEmpty, "local warehouse has no published evidence")
    _require(localbytes == remotebytes, "local and remote warehouse evidence disagree")
    _require(localbytes.values.exists(_ == firstbytes), "published evidence lacks the package CAR bytes")
  }

  private def _require(condition: Boolean, message: String): Unit =
    if (!condition) sys.error(message)

  private def _fixture(root: Path): Fixture = {
    val parentproject = root.resolve("parent-project")
    _write(
      parentproject.resolve("project.yaml"),
      s"""project:
         |  namespace: ${_namespace}
         |  id: RscParent
         |  name: rsc-parent
         |  component:
         |    version: ${_version}
         |""".stripMargin
    )
    _write(parentproject.resolve("src/main/cozy/rsc-parent.cml"), "# RSC03 parent fixture\n")
    val parentcar = root.resolve("input/rsc-parent.car")
    _archive(
      parentcar,
      Vector(
        "component-descriptor.json" -> _descriptor("RscParent"),
        "abi-manifest.json" -> _abi("RscParent"),
        "release-membership.txt" -> Vector(
          _documentation_id,
          _source_code_id,
          _web_presentation_id
        ).mkString("\n")
      )
    )
    val documentationcar = root.resolve("input/rsc-documentation.car")
    _archive(
      documentationcar,
      Vector(
        "component-descriptor.json" -> _descriptor("RscDocumentation"),
        "abi-manifest.json" -> _abi("RscDocumentation"),
        "payload/documentation/README.md" -> _documentation_payload_marker
      )
    )
    val sourcecodecar = root.resolve("input/rsc-source-code.car")
    _archive(
      sourcecodecar,
      Vector(
        "component-descriptor.json" -> _descriptor("RscSourceCode"),
        "abi-manifest.json" -> _abi("RscSourceCode"),
        "payload/source-code/release-inputs.txt" -> _source_code_payload_marker
      )
    )
    val webpresentationcar = root.resolve("input/rsc-web-presentation.car")
    _archive(
      webpresentationcar,
      Vector(
        "component-descriptor.json" -> _descriptor("RscWebPresentation"),
        "abi-manifest.json" -> _abi("RscWebPresentation"),
        "payload/web-presentation/platform.json" ->
          s"""{"platform":"external","marker":"${_web_presentation_payload_marker}"}"""
      )
    )
    val childcars = Vector(documentationcar, sourcecodecar, webpresentationcar)
    val composition = _write(root.resolve("input/composition.json"), _composition(parentcar, childcars))
    Fixture(parentproject, parentcar, childcars, composition)
  }

  private def _package_arguments(
    fixture: Fixture,
    output: Path,
    integrity: Path,
    childcars: Vector[Path],
    compositionpath: Option[Path] = None
  ): Array[String] = {
    val selectedcomposition = compositionpath.getOrElse(fixture.composition)
    (Vector(
      "package-subcomponent-release",
      "--project-dir", fixture.parentproject.toString,
      "--parent-car", fixture.parentcar.toString,
      "--composition", selectedcomposition.toString,
      "--save", output.toString,
      "--integrity", integrity.toString,
      "--published-at", _published_at,
      "--required-child", _documentation_id,
      "--required-child", _source_code_id
    ) ++ childcars.flatMap(child => Vector("--child-car", child.toString))).toArray
  }

  private def _publish_arguments(
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

  private def _composition(parentcar: Path, childcars: Vector[Path]): String =
    s"""{"schema":"${_composition_schema}","membershipKind":"parent-child","parent":${_parent_composition(parentcar)},"members":[${_member_composition("RscDocumentation", true, _documentation_role, "documentation", "documentation/README.md", childcars(0), _documentation_signature)},${_member_composition("RscSourceCode", true, _source_code_role, "source-code", "source-code/release-inputs.txt", childcars(1), _source_code_signature)},${_member_composition("RscWebPresentation", false, _web_presentation_role, "external-platform-presentation", "web-presentation/platform.json", childcars(2), _web_presentation_signature)}],"fixtureExtension":{"accepted":true}}"""

  private def _hostile_compositions(root: Path, fixture: Fixture): Vector[(String, Path)] = {
    val valid = Files.readString(fixture.composition, StandardCharsets.UTF_8)
    val duplicateresource = valid.replace(
      "\"logicalResource\":\"https://example.test/phase58/source-code\"",
      "\"logicalResource\":\"https://example.test/phase58/documentation\""
    )
    val schemaprefix = "{\"schema\":\"" + _composition_schema + "\",\"membershipKind\":\"parent-child\""
    val duplicateschema = valid.replace(
      schemaprefix,
      "{\"schema\":\"" + _composition_schema + "\",\"schema\":\"" + _composition_schema + "\",\"membershipKind\":\"parent-child\""
    )
    Vector(
      "duplicate-logical-resource" -> _write(root.resolve("input/duplicate-logical-resource.json"), duplicateresource),
      "duplicate-schema-key" -> _write(root.resolve("input/duplicate-schema-key.json"), duplicateschema)
    )
  }

  private def _parent_composition(parentcar: Path): String =
    s"""{"componentId":"${_parent_id}","logicalRelease":"${_version}","primaryCar":${_car_composition("primary", "RscParent", parentcar, _parent_signature)}}"""

  private def _member_composition(
    id: String,
    required: Boolean,
    role: String,
    resource: String,
    path: String,
    car: Path,
    signature: String
  ): String =
    s"""{"componentId":"${_namespace}.${id}","logicalRelease":"${_version}","required":${required},"role":"${role}","implementationTechnology":"CAR","logicalResource":"https://example.test/phase58/${resource}","logicalPath":"${path}","subcomponentCar":${_car_composition("subcomponent", id, car, signature)},"payload":{"authoritative":false,"executable":false},"authorization":{"state":"not-granted"},"integrity":{"state":"verified"},"availability":{"state":"available"},"deployment":{"platform":"external-platform","mode":"external","requiresExplicitPlatformAction":true,"authority":{"activation":false,"operation":false,"mcp":false,"disclosure":false,"deployment":false}},"access":{"visibility":"public"},"disclosure":{"mode":"metadata-only"},"license":{"spdx":"Apache-2.0"},"media":{"type":"application/vnd.cozy.car"},"profile":{"id":"phase58"}}"""

  private def _car_composition(classification: String, id: String, car: Path, signature: String): String =
    s"""{"classification":"${classification}","artifact":{"coordinate":"org.example:${_physical_artifact(id)}-car:0.1.0","sha256":"${_sha256(car)}","signature":"${signature}","repository":"https://repository.example.test/releases","physicalPath":"input/${id}.car"},"provenance":{"logicalSource":"git:phase58","physicalSource":"build:phase58","physicalPath":"src/main/cozy/${id}.cml"}}"""

  private def _physical_artifact(id: String): String =
    id.foldLeft("") { (result, character) =>
      if (character.isUpper && result.nonEmpty) s"$result-${character.toLower}"
      else s"$result${character.toLower}"
    }

  private def _descriptor(id: String): String =
    s"""{"schemaVersion":3,"component":{"namespace":"${_namespace}","id":"$id","version":"${_version}"}}"""

  private def _abi(id: String): String =
    s"""{"format":"cozy.car.abi-manifest.v2","component":{"namespace":"${_namespace}","id":"$id","version":"${_version}"},"abi":{"version":1,"exports":{"components":[{"namespace":"${_namespace}","id":"$id"}],"services":[],"operations":[],"types":[],"entities":[]},"dependencies":[]}}"""

  private def _archive(archive: Path, entries: Vector[(String, String)]): Path = {
    Option(archive.getParent).foreach(Files.createDirectories(_))
    val output = new ZipOutputStream(Files.newOutputStream(archive))
    try entries.foreach { case (name, value) =>
      output.putNextEntry(new ZipEntry(name))
      output.write(value.getBytes(StandardCharsets.UTF_8))
      output.closeEntry()
    } finally output.close()
    archive
  }

  private def _zip_texts(path: Path): Vector[String] = _zip_texts(Files.readAllBytes(path))

  private def _zip_texts(bytes: Array[Byte]): Vector[String] = {
    val input = new ZipInputStream(new ByteArrayInputStream(bytes))
    val texts = scala.collection.mutable.ArrayBuffer.empty[String]
    try {
      var entry = input.getNextEntry
      while (entry != null) {
        val entrybytes = input.readAllBytes()
        texts += new String(entrybytes, StandardCharsets.UTF_8)
        if (entrybytes.length >= 4 && entrybytes(0) == 'P'.toByte && entrybytes(1) == 'K'.toByte && entrybytes(2) == 3 && entrybytes(3) == 4)
          texts ++= _zip_texts(entrybytes)
        entry = input.getNextEntry
      }
    } finally input.close()
    texts.toVector
  }

  private def _sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).map(byte => f"${byte & 0xff}%02x").mkString

  private def _tree_bytes(root: Path): Map[String, Vector[Byte]] = {
    if (!Files.isDirectory(root)) Map.empty
    else {
      val stream = Files.walk(root)
      try stream.iterator().asScala.collect {
        case path if Files.isRegularFile(path) =>
          root.relativize(path).toString -> Files.readAllBytes(path).toVector
      }.toMap
      finally stream.close()
    }
  }

  private def _write(path: Path, content: String): Path = {
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    path
  }
}
