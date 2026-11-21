package org.goldenport.cozy

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption}
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import sbt._
import sbt.Keys._

/*
 * @since   Nov. 12, 2026
 * @version Nov. 19, 2026
 */
object CozyDevelopmentArtifactOverlay {
  final case class Coordinate(organization: String, module: String, version: String)
  final case class Mapping(requested: Coordinate, selected: Coordinate, jar: Path, ivy: Path)
  final case class Producer(coordinate: Coordinate, jar: Path, ivy: Path)
  final case class BatchProducer(project: String, producer: Producer)
  final case class BatchMember(producer: Producer, jar: File, ivy: File, organization: String,
    module: String, version: String, report: UpdateReport,
    artifacts: Vector[(Artifact, File)], crossArtifactName: Option[String => String])
  final case class GeneratorSelection(cozyVersion: String, modelerVersion: String, batch: String,
    mappings: Vector[Mapping])
  final case class GeneratorResolution(modules: Vector[Json], edges: Vector[Json], classpath: Vector[File],
    resolvedArtifacts: Vector[Json], resolutionV3: Json = Json.Null)
  final case class Input(kind: String, scope: String, selection: String, publication: Option[String],
    buildroot: Path, ownerroot: Path, repository: Path, staging: Option[Path], result: Path,
    mappings: Vector[Mapping], producer: Option[Producer], owner: String = "",
    producers: Vector[BatchProducer] = Vector.empty, generator: Option[GeneratorSelection] = None)

  private val _id = "[A-Za-z0-9._-]{1,128}".r
  private val _segment = "[A-Za-z0-9][A-Za-z0-9._-]{0,255}".r
  private def _fail(message: String): Nothing = sys.error("[sbt-cozy artifact] " + message)
  private def _string(cursor: HCursor, key: String): String =
    cursor.get[String](key).fold(error => _fail(error.message), identity)
  private def _object(cursor: HCursor, key: String): HCursor =
    cursor.downField(key).success.getOrElse(_fail("missing object " + key))
  private def _identity(value: String): String = {
    if (!_id.pattern.matcher(value).matches || value == "." || value == "..") _fail("invalid identity: " + value)
    value
  }
  private def _coordinate(cursor: HCursor): Coordinate = {
    def _part_(key: String): String = {
      val value = _string(cursor, key)
      if (!_segment.pattern.matcher(value).matches || value == "." || value == "..")
        _fail("invalid coordinate " + key)
      value
    }
    Coordinate(_part_("organization"), _part_("module"), _part_("version"))
  }
  private def _absolute(value: String): Path = {
    val path = Paths.get(value)
    if (!path.isAbsolute || path.normalize != path) _fail("expected normalized absolute path: " + value)
    path
  }
  private def _within(root: Path, path: Path): Path = {
    if (!path.startsWith(root) || path == root) _fail("path outside owner root: " + path)
    var current = root
    val iterator = root.relativize(path).iterator()
    while (iterator.hasNext) {
      current = current.resolve(iterator.next())
      if (Files.isSymbolicLink(current)) _fail("symbolic link in path: " + current)
    }
    path
  }
  private def _relative(root: Path, value: String): Path = {
    val path = Paths.get(value)
    if (path.isAbsolute || path.normalize != path || path.toString.isEmpty) _fail("invalid relative path: " + value)
    _within(root, root.resolve(path).normalize)
  }
  private def _layout(repository: Path, coordinate: Coordinate): (Path, Path) = {
    val base = repository.resolve(coordinate.organization).resolve(coordinate.module).resolve(coordinate.version)
    (base.resolve("jars").resolve(coordinate.module + ".jar"), base.resolve("ivys").resolve("ivy.xml"))
  }
  private def _marker(ivy: Path): Path = ivy.getParent.getParent.resolve("completed.json")
  private def _batch_marker(repository: Path, scope: String, publication: String): Path =
    repository.resolve(".batches").resolve(scope).resolve(publication).resolve("completed.json")
  private def _suffix(scope: String, publication: String): String =
    "-smdev-" + scope.length + "-" + scope + "-" + publication.length + "-" + publication
  private def _descriptor(path: Path): (Coordinate, Vector[Coordinate]) = {
    val factory = javax.xml.parsers.SAXParserFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    val loader = new scala.xml.factory.XMLLoader[scala.xml.Elem] {
      override def parser: javax.xml.parsers.SAXParser = factory.newSAXParser()
    }
    val xml = loader.loadFile(path.toFile)
    val info = (xml \ "info").headOption.getOrElse(_fail("Ivy descriptor lacks info"))
    def _attribute_(node: scala.xml.Node, name: String): String =
      node.attribute(name).map(_.text).filter(_.nonEmpty).getOrElse(_fail("Ivy descriptor lacks " + name))
    val coordinate = Coordinate(_attribute_(info, "organisation"), _attribute_(info, "module"), _attribute_(info, "revision"))
    val dependencies = (xml \ "dependencies" \ "dependency").map(node =>
      Coordinate(_attribute_(node, "org"), _attribute_(node, "name"), _attribute_(node, "rev"))).toVector
    coordinate -> dependencies
  }

  private def _verify_batch(repository: Path, scope: String, owner: String, publication: String,
    selection: String, coordinate: Coordinate): Unit = {
    val path = _within(repository, _batch_marker(repository, scope, publication))
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) _fail("completed batch is unavailable")
    val cursor = parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).
      fold(error => _fail(error.message), identity).hcursor
    if (_string(cursor, "schema") != "sbt-cozy.development-artifact-batch.v2" ||
      _string(cursor, "scope") != scope || _string(cursor, "publication") != publication ||
      _string(cursor, "selection") != selection || _string(cursor, "owner") != owner ||
      _string(cursor, "batch") != publication || _string(cursor, "producerSelectionId") != selection ||
      !cursor.get[Boolean]("complete").getOrElse(false)) _fail("foreign or contradictory batch")
    val members = cursor.get[Vector[Json]]("members").fold(error => _fail(error.message), identity)
    val coordinates = members.map(value => _coordinate(value.hcursor))
    if (coordinates.isEmpty || coordinates.map(value => (value.organization, value.module)).distinct.size != coordinates.size ||
      !coordinates.contains(coordinate))
      _fail("invalid batch membership")
    members.foreach { value =>
      val entry = value.hcursor
      val member = _coordinate(entry)
      val (jar, ivy) = _layout(repository, member)
      _within(repository, jar)
      _within(repository, ivy)
      if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isRegularFile(ivy, LinkOption.NOFOLLOW_LINKS)) _fail("incomplete batch member")
      val controlpath = _within(repository, _marker(ivy))
      if (!Files.isRegularFile(controlpath, LinkOption.NOFOLLOW_LINKS)) _fail("batch member control is unavailable")
      val control = parse(new String(Files.readAllBytes(controlpath), StandardCharsets.UTF_8)).
        fold(error => _fail(error.message), identity).hcursor
      if (_string(control, "scope") != scope || _string(control, "owner") != owner ||
        _string(control, "batch") != publication || _string(control, "producerSelectionId") != selection ||
        !control.get[Boolean]("complete").getOrElse(false)) _fail("foreign batch member control")
      Vector("organization", "name", "requestedVersion", "selectedVersion", "baseVersion", "artifact").foreach { key =>
        if (_string(entry, key) != _string(control, key)) _fail("batch member control contradicts " + key)
      }
      if (_string(entry, "name") != member.module || _string(entry, "selectedVersion") != member.version ||
        member.version != _string(entry, "baseVersion") + _suffix(scope, publication) ||
        _string(entry, "artifact") != jar.toString || _string(entry, "publication") != jar.toString ||
        _string(entry, "control") != controlpath.toString || !entry.get[Boolean]("development").getOrElse(false))
        _fail("batch member paths or coordinate disagree")
      val dependencies = entry.get[Vector[Json]]("dependencies").fold(error => _fail(error.message), identity).
        map(value => _coordinate(value.hcursor))
      if (_descriptor(ivy) != (member -> dependencies)) _fail("batch descriptor contradicts completion record")
    }
    val v3path = path.resolveSibling("completed-v3.json")
    if (Files.exists(v3path, LinkOption.NOFOLLOW_LINKS))
      _verify_v3_batch(repository, scope, owner, publication, selection, coordinates, v3path)
  }

  private def _verify_v3_batch(repository: Path, scope: String, owner: String, publication: String,
    selection: String, coordinates: Vector[Coordinate], path: Path): Unit = {
    _within(repository, path)
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) _fail("v3 batch is unavailable")
    val batch = parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).
      fold(error => _fail(error.message), identity).hcursor
    if (_string(batch, "scope") != scope || _string(batch, "owner") != owner ||
      _string(batch, "batch") != publication || _string(batch, "producerSelectionId") != selection ||
      !batch.get[Boolean]("complete").getOrElse(false)) _fail("foreign v3 batch")
    val members = batch.get[Vector[Json]]("members").fold(error => _fail(error.message), identity)
    val actual = members.map { item =>
      val module = _object(item.hcursor, "module")
      Coordinate(_string(module, "organization"), _string(module, "name"), _string(module, "selectedVersion"))
    }
    if (actual.distinct.size != actual.size || actual.toSet != coordinates.toSet)
      _fail("v3 batch membership differs from completed publication")
    members.zip(actual).foreach { case (item, coordinate) =>
      val cursor = item.hcursor
      val module = _object(cursor, "module")
      val (jar, ivy) = _layout(repository, coordinate)
      val controlpath = ivy.getParent.getParent.resolve("completed-v3.json")
      if (_string(module, "descriptor") != ivy.toString || _string(module, "control") != controlpath.toString ||
        coordinate.version != _string(module, "baseVersion") + _suffix(scope, publication))
        _fail("v3 module binding differs from publication")
      _within(repository, controlpath)
      if (!Files.isRegularFile(controlpath, LinkOption.NOFOLLOW_LINKS)) _fail("v3 control unavailable")
      val control = parse(new String(Files.readAllBytes(controlpath), StandardCharsets.UTF_8)).
        fold(error => _fail(error.message), identity).hcursor
      if (_string(control, "scope") != scope || _string(control, "owner") != owner ||
        _string(control, "batch") != publication || _string(control, "producerSelectionId") != selection ||
        !control.get[Boolean]("complete").getOrElse(false) ||
        control.downField("module").focus != cursor.downField("module").focus ||
        control.downField("artifacts").focus != cursor.downField("artifacts").focus)
        _fail("v3 control differs from completed batch")
      val artifacts = cursor.get[Vector[Json]]("artifacts").fold(error => _fail(error.message), identity)
      if (artifacts.isEmpty || artifacts.distinct.size != artifacts.size) _fail("invalid v3 publication artifacts")
      val declarations = (scala.xml.XML.loadFile(ivy.toFile) \ "publications" \ "artifact").map { node =>
        val attrs = node.attributes.asAttrMap
        val classifier = node.attribute("http://ant.apache.org/ivy/extra", "classifier").map(_.text).filter(_.nonEmpty)
        val extra = attrs.filterNot { case (name, _) =>
          Set("name", "type", "ext", "conf").contains(name) || name.endsWith(":classifier")
        }
        (attrs.getOrElse("name", ""), attrs.getOrElse("type", ""), attrs.getOrElse("ext", ""), classifier, extra)
      }.toVector
      val published = artifacts.map { artifact =>
        val key = _object(artifact.hcursor, "key")
        (_string(key, "name"), _string(key, "artifactType"), _string(key, "extension"),
          key.get[Option[String]]("classifier").fold(error => _fail(error.message), identity),
          key.get[Map[String, String]]("extraAttributes").fold(error => _fail(error.message), identity))
      }
      if (declarations.distinct.size != declarations.size || published.distinct.size != published.size ||
        declarations.toSet != published.toSet) _fail("v3 publication differs from Ivy descriptor")
      artifacts.foreach { artifact =>
        val entry = artifact.hcursor
        val key = _object(entry, "key")
        val moduleIdentity = coordinate.organization + ":" + coordinate.module + ":" + coordinate.version
        if (_string(key, "module") != moduleIdentity) _fail("foreign v3 publication artifact")
        val classifier = key.get[Option[String]]("classifier").fold(error => _fail(error.message), identity)
        val filename = _string(key, "name") + classifier.map("-" + _).getOrElse("") + "." + _string(key, "extension")
        if (filename.contains("/") || filename.contains("\\") ||
          _string(entry, "publicationFile") != jar.getParent.resolve(filename).toString)
          _fail("invalid v3 publication artifact path")
        val file = _within(repository, jar.getParent.resolve(filename))
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) _fail("v3 publication artifact unavailable")
      }
    }
  }

  def read(file: File, buildRoot: File): Input = {
    val path = file.toPath.toAbsolutePath.normalize
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536)
      _fail("input must be a regular file of at most 64 KiB")
    val json = parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).fold(error => _fail(error.message), identity)
    val cursor = json.hcursor
    if (_string(cursor, "schema") != "cncf.scoped-sbt-input.v1") _fail("unexpected envelope schema")
    val kind = _string(cursor, "kind")
    if (kind != "consumer" && kind != "producer") _fail("unexpected input kind")
    Vector("component", "buildWorkspace", "ownerWorkspace").foreach(key => _identity(_string(cursor, key)))
    val scope = _identity(_string(cursor, "scope"))
    val selection = _identity(_string(cursor, "selection"))
    val adapter = _object(cursor, "cozyArtifact")
    val schema = _string(adapter, "schema")
    if (schema != "sbt-cozy.development-artifact.v1" && schema != "sbt-cozy.development-artifact.v2")
      _fail("unexpected adapter schema")
    Vector("component", "buildWorkspace", "ownerWorkspace", "scope", "selection").foreach { key =>
      if (_string(adapter, key) != _string(cursor, key)) _fail("adapter disagrees with envelope " + key)
    }
    val buildroot = _absolute(_string(adapter, "buildRoot"))
    val ownerroot = _absolute(_string(adapter, "ownerRoot"))
    if (!Files.isDirectory(buildroot, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(ownerroot, LinkOption.NOFOLLOW_LINKS) ||
      buildroot.toRealPath() != buildRoot.toPath.toRealPath() || ownerroot.toRealPath() != ownerroot)
      _fail("foreign or noncanonical build/owner root")
    _within(ownerroot, path)
    val repository = _within(ownerroot, _absolute(_string(adapter, "repositoryRoot")))
    if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)) _fail("repository is unavailable")
    val result = _within(ownerroot, _absolute(_string(adapter, "resultFile")))
    if (result == path || result.startsWith(repository) ||
      (buildroot != ownerroot && buildroot.startsWith(ownerroot) && result.startsWith(buildroot)))
      _fail("result path conflicts with input, repository or build")
    def _mappings_(values: Vector[Json]): Vector[Mapping] = {
    val mappings = values.map { value =>
      val entry = value.hcursor
      val requested = _coordinate(_object(entry, "requested"))
      val selected = _coordinate(_object(entry, "selected"))
      if (requested.organization != selected.organization || requested.module != selected.module)
        _fail("mapping must retain organization and exact cross-module name")
      val jar = _within(repository, _absolute(_string(entry, "jar")))
      val ivy = _within(repository, _absolute(_string(entry, "ivy")))
      if ((jar, ivy) != _layout(repository, selected) || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isRegularFile(ivy, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(_marker(ivy), LinkOption.NOFOLLOW_LINKS))
        _fail("selected publication is unavailable")
      val marker = parse(new String(Files.readAllBytes(_marker(ivy)), StandardCharsets.UTF_8)).fold(error => _fail(error.message), identity).hcursor
      val sourcepublication = _identity(_string(entry, "publication"))
      val markerschema = _string(marker, "schema")
      if ((markerschema != "sbt-cozy.development-artifact-publication.v1" &&
        markerschema != "sbt-cozy.development-artifact-publication.v2") ||
        _string(marker, "scope") != scope || _string(marker, "publication") != sourcepublication ||
        _string(marker, "selection") != _identity(_string(entry, "publicationSelection")) ||
        _string(marker, "organization") != selected.organization ||
        _string(marker, "module") != selected.module || _string(marker, "version") != selected.version ||
        selected.version != requested.version + _suffix(scope, sourcepublication))
        _fail("foreign or contradictory publication")
      if (markerschema == "sbt-cozy.development-artifact-publication.v2")
        _verify_batch(repository, scope, _string(cursor, "ownerWorkspace"), sourcepublication, _string(marker, "selection"), selected)
      val descriptor = _descriptor(ivy)
      val described = marker.get[Vector[Json]]("dependencies").fold(error => _fail(error.message), identity).
        map(value => _coordinate(value.hcursor))
      if (descriptor._1 != selected || descriptor._2 != described) _fail("Ivy descriptor contradicts publication")
      Mapping(requested, selected, jar, ivy)
    }
    if (mappings.map(x => (x.requested.organization, x.requested.module)).distinct.size != mappings.size)
      _fail("duplicate requested module")
    mappings.foreach { mapping =>
      _descriptor(mapping.ivy)._2.foreach { dependency =>
        val selected = mappings.find(value => value.selected.organization == dependency.organization &&
          value.selected.module == dependency.module)
        selected.foreach { value =>
          if (dependency != value.selected) _fail("selected publication has a conflicting dependency closure: " + dependency)
        }
        if (dependency.version.contains("-smdev-") && selected.isEmpty)
          _fail("selected publication has an undeclared development dependency: " + dependency)
      }
    }
    mappings
    }
    val values = adapter.get[Vector[Json]]("mappings").fold(error => _fail(error.message), identity)
    val mappings = _mappings_(values)
    val generator = adapter.downField("generator").success.map { item =>
      if (schema != "sbt-cozy.development-artifact.v2" || kind != "consumer")
        _fail("generator selection requires a v2 consumer")
      val batch = _identity(_string(item, "batch"))
      val values = item.get[Vector[Json]]("mappings").fold(error => _fail(error.message), identity)
      if (values.isEmpty || values.exists(value => _string(value.hcursor, "publication") != batch))
        _fail("generator mappings must belong to the declared batch")
      val cozyversion = _string(item, "cozyVersion")
      val modelerversion = _string(item, "modelerVersion")
      if (Vector(cozyversion, modelerversion).exists(value => !_segment.pattern.matcher(value).matches))
        _fail("invalid generator base version")
      val selected = _mappings_(values)
      selected.foreach { mapping =>
        val marker = parse(new String(Files.readAllBytes(_marker(mapping.ivy)), StandardCharsets.UTF_8)).
          fold(error => _fail(error.message), identity).hcursor
        if (_string(marker, "schema") != "sbt-cozy.development-artifact-publication.v2")
          _fail("generator requires a completed v2 batch")
      }
      Vector("cozy_2.12" -> cozyversion, "simplemodeler_2.12" -> modelerversion).foreach { case (module, version) =>
        selected.find(value => value.requested.organization == "org.simplemodeling" && value.requested.module == module).
          foreach(value => if (value.requested.version != version) _fail("generator root base version disagrees with mapping"))
      }
      GeneratorSelection(cozyversion, modelerversion, batch, selected)
    }
    val publication = if (kind == "producer") Some(_identity(_string(cursor, "publication"))) else None
    val staging = if (kind == "producer") Some(_relative(ownerroot, _string(cursor, "staging"))) else None
    if (kind == "producer" && (_relative(ownerroot, _string(cursor, "repository")) != repository ||
      !Files.isDirectory(staging.get, LinkOption.NOFOLLOW_LINKS) || staging.get.startsWith(repository) ||
      repository.startsWith(staging.get) || result.startsWith(staging.get))) _fail("producer paths disagree or are unavailable")
    def _producer_(item: HCursor): Producer = {
      val coordinate = _coordinate(item)
      if (!coordinate.version.endsWith(_suffix(scope, publication.get))) _fail("invalid development version")
      val jar = _within(repository, _absolute(_string(item, "jar")))
      val ivy = _within(repository, _absolute(_string(item, "ivy")))
      if ((jar, ivy) != _layout(repository, coordinate)) _fail("producer paths disagree with coordinate")
      Producer(coordinate, jar, ivy)
    }
    val producer = if (kind == "producer" && schema.endsWith(".v1")) Some(_producer_(_object(adapter, "producer"))) else None
    val producers = if (kind == "producer" && schema.endsWith(".v2")) {
      val values = adapter.get[Vector[Json]]("producers").fold(error => _fail(error.message), identity)
      val result = values.map { value =>
        val item = value.hcursor
        BatchProducer(_identity(_string(item, "project")), _producer_(item))
      }
      if (result.isEmpty || result.map(_.project).distinct.size != result.size ||
        result.map(_.producer.coordinate).distinct.size != result.size) _fail("invalid producer set")
      result
    } else Vector.empty
    Input(kind, scope, selection, publication, buildroot, ownerroot, repository, staging, result, mappings, producer,
      _string(cursor, "ownerWorkspace"), producers, generator)
  }

  private def _coordinate_json(value: Coordinate): Json = Json.obj(
    "organization" -> Json.fromString(value.organization), "module" -> Json.fromString(value.module),
    "version" -> Json.fromString(value.version))

  private def _write(path: Path, value: Json): Unit = {
    Files.createDirectories(path.getParent)
    val temp = Files.createTempFile(path.getParent, ".cozy-artifact-", ".tmp")
    try {
      Files.write(temp, value.spaces2.getBytes(StandardCharsets.UTF_8))
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally Files.deleteIfExists(temp)
  }

  /** Promote a prepared producer set; the common record is the final visibility barrier. */
  def publishBatch(input: Input, members: Vector[BatchMember]): Vector[File] = {
    val publication = input.publication.getOrElse(_fail("producer input required"))
    if (input.kind != "producer" || members.isEmpty) _fail("nonempty producer batch required")
    val coordinates = members.map(_.producer.coordinate)
    if (coordinates.map(value => (value.organization, value.module)).distinct.size != coordinates.size)
      _fail("duplicate batch module")
    val originals = members.map(member => Coordinate(member.organization, member.module, member.version) -> member.producer.coordinate).toMap
    val stageroot = _within(input.ownerroot, input.staging.get.resolve(input.scope))
    Files.createDirectories(stageroot)
    val stage = _within(input.ownerroot, stageroot.resolve(publication))
    Files.createDirectory(stage)
    val entries = members.zipWithIndex.map { case (member, index) =>
      val producer = member.producer
      val coordinate = producer.coordinate
      if (member.organization != coordinate.organization || member.module != coordinate.module ||
        coordinate.version != member.version + _suffix(input.scope, publication) ||
        (producer.jar, producer.ivy) != _layout(input.repository, coordinate))
        _fail("batch project disagrees with declared coordinate")
      _within(input.repository, producer.jar)
      _within(input.repository, producer.ivy)
      val resolved = _resolved(input, member.report)
      val directory = stage.resolve(index.toString)
      val jar = directory.resolve("jars").resolve(coordinate.module + ".jar")
      val ivy = directory.resolve("ivys").resolve("ivy.xml")
      Files.createDirectories(jar.getParent)
      Files.createDirectories(ivy.getParent)
      Files.copy(member.jar.toPath, jar)
      val xml = scala.xml.XML.loadFile(member.ivy)
      val info = (xml \ "info").headOption.getOrElse(_fail("generated Ivy descriptor lacks info"))
      val transformed = new scala.xml.transform.RuleTransformer(new scala.xml.transform.RewriteRule {
        override def transform(node: scala.xml.Node): Seq[scala.xml.Node] = node match {
          case element: scala.xml.Elem if element eq info =>
            element % new scala.xml.UnprefixedAttribute("revision", coordinate.version, scala.xml.Null)
          case element: scala.xml.Elem if element.label == "dependency" =>
            val organization = element.attribute("org").map(_.text).getOrElse("")
            val name = element.attribute("name").map(_.text).getOrElse("")
            val revision = element.attribute("rev").map(_.text).getOrElse("")
            val original = originals.keys.find(value => value.organization == organization && value.module == name)
            original match {
              case Some(value) if revision != value.version => _fail("batch dependency disagrees with declared producer version")
              case Some(value) => element % new scala.xml.UnprefixedAttribute("rev", originals(value).version, scala.xml.Null)
              case None => element
            }
          case other => other
        }
      }).transform(xml).head
      scala.xml.XML.save(ivy.toString, transformed, "UTF-8", xmlDecl = true)
      val descriptor = _descriptor(ivy)
      if (descriptor._1 != coordinate) _fail("batch descriptor disagrees with producer")
      input.mappings.foreach { mapping =>
        if (descriptor._2.exists(x => x.organization == mapping.requested.organization && x.module == mapping.requested.module) &&
          !descriptor._2.contains(mapping.selected)) _fail("producer metadata omitted a selected dependency")
      }
      val entry = _coordinate_json(coordinate).deepMerge(Json.obj(
        "dependencies" -> Json.arr(descriptor._2.map(_coordinate_json): _*),
        "name" -> Json.fromString(coordinate.module), "requestedVersion" -> Json.fromString(member.version),
        "selectedVersion" -> Json.fromString(coordinate.version), "baseVersion" -> Json.fromString(member.version),
        "artifact" -> Json.fromString(producer.jar.toString), "development" -> Json.fromBoolean(true),
        "publication" -> Json.fromString(producer.jar.toString), "control" -> Json.fromString(_marker(producer.ivy).toString)))
      // Keep legacy controls intact; the supplier's structured module field
      // has a different type from the v2 control's module string.
      val identity = coordinate.organization + ":" + coordinate.module + ":" + coordinate.version
      val v3control = producer.ivy.getParent.getParent.resolve("completed-v3.json")
      val publishedModule = Json.obj(
        "organization" -> Json.fromString(coordinate.organization),
        "name" -> Json.fromString(coordinate.module),
        "selectedVersion" -> Json.fromString(coordinate.version),
        "baseVersion" -> Json.fromString(member.version),
        "descriptor" -> Json.fromString(producer.ivy.toString),
        "control" -> Json.fromString(v3control.toString))
      val declarations = (transformed \ "publications" \ "artifact").map { node =>
        val attrs = node.attributes.asAttrMap
        val classifier = node.attribute("http://ant.apache.org/ivy/extra", "classifier").map(_.text).filter(_.nonEmpty)
        val extra = attrs.filterNot { case (name, _) =>
          Set("name", "type", "ext", "conf").contains(name) || name.endsWith(":classifier")
        }
        (attrs.getOrElse("name", ""), attrs.getOrElse("type", ""), attrs.getOrElse("ext", ""), classifier, extra)
      }.toVector
      def _artifact_key_(artifact: Artifact) =
        (artifact.name, artifact.`type`, artifact.extension, artifact.classifier, artifact.extraAttributes)
      // SBT can cross-version artifact names in Ivy after reporting packagedArtifacts.
      // Prebuilt artifacts may already use the names declared in their descriptors.
      val packaged = member.artifacts.map { case (artifact, file) =>
        val resolvedartifact = if (declarations.contains(_artifact_key_(artifact))) artifact
          else member.crossArtifactName.fold(artifact)(cross => artifact.withName(cross(artifact.name)))
        (resolvedartifact, file)
      }
      val supplied = packaged.map { case (artifact, _) => _artifact_key_(artifact) }
      if (declarations.distinct.size != declarations.size || supplied.distinct.size != supplied.size ||
          declarations.toSet != supplied.toSet)
        _fail("Ivy descriptor publications differ from packaged artifacts for " + coordinate +
          "; descriptor=" + declarations.mkString("[", ", ", "]") +
          "; packaged=" + supplied.mkString("[", ", ", "]"))
      val artifacts = packaged.map { case (artifact, file) =>
        val filename = artifact.name + artifact.classifier.map("-" + _).getOrElse("") + "." + artifact.extension
        if (filename.contains("/") || filename.contains("\\") || filename == "." || filename == "..")
          _fail("invalid published artifact filename")
        val staged = directory.resolve("jars").resolve(filename)
        if (staged != jar) Files.copy(file.toPath, staged)
        else if (!java.util.Arrays.equals(Files.readAllBytes(staged), Files.readAllBytes(file.toPath)))
          _fail("default publication artifact differs from packageBin")
        val key = Json.obj("module" -> Json.fromString(identity), "name" -> Json.fromString(artifact.name),
          "artifactType" -> Json.fromString(artifact.`type`), "extension" -> Json.fromString(artifact.extension),
          "classifier" -> artifact.classifier.map(Json.fromString).getOrElse(Json.Null),
          "extraAttributes" -> Json.obj(artifact.extraAttributes.toVector.map { case (k, v) => k -> Json.fromString(v) }: _*)).dropNullValues
        Json.obj("key" -> key, "publicationFile" -> Json.fromString(producer.jar.getParent.resolve(filename).toString))
      }
      if (artifacts.isEmpty || artifacts.distinct.size != artifacts.size) _fail("invalid published artifact set")
      val publishedArtifacts = Json.arr(artifacts: _*)
      val v3member = Json.obj("module" -> publishedModule, "artifacts" -> publishedArtifacts)
      val marker = entry.deepMerge(Json.obj(
        "schema" -> Json.fromString("sbt-cozy.development-artifact-publication.v2"),
        "scope" -> Json.fromString(input.scope), "selection" -> Json.fromString(input.selection),
        "publication" -> Json.fromString(publication), "owner" -> Json.fromString(input.owner),
        "producerSelectionId" -> Json.fromString(input.selection), "batch" -> Json.fromString(publication),
        "complete" -> Json.fromBoolean(true)))
      _write(directory.resolve("completed.json"), marker)
      _write(directory.resolve("completed-v3.json"), Json.obj(
        "scope" -> Json.fromString(input.scope), "owner" -> Json.fromString(input.owner),
        "producerSelectionId" -> Json.fromString(input.selection), "batch" -> Json.fromString(publication),
        "complete" -> Json.fromBoolean(true), "module" -> publishedModule, "artifacts" -> publishedArtifacts))
      (member, directory, entry, resolved, v3member)
    }
    entries.foreach { case (_, _, entry, _, _) =>
      entry.hcursor.get[Vector[Json]]("dependencies").fold(error => _fail(error.message), identity).
        map(value => _coordinate(value.hcursor)).foreach { dependency =>
          val batchmember = coordinates.find(value => value.organization == dependency.organization && value.module == dependency.module)
          if (batchmember.exists(_ != dependency)) _fail("batch metadata has a conflicting member dependency")
          if (dependency.version.contains("-smdev-") && batchmember.isEmpty &&
            !input.mappings.exists(_.selected == dependency)) _fail("batch has an undeclared development dependency")
        }
    }
    val batchmarker = _within(input.repository, _batch_marker(input.repository, input.scope, publication))
    Files.createDirectories(batchmarker.getParent)
    Files.createFile(batchmarker.resolveSibling("completed.claim"))
    if (Files.exists(batchmarker, LinkOption.NOFOLLOW_LINKS)) _fail("batch completion already exists")
    entries.foreach { case (member, directory, _, _, _) =>
      val base = member.producer.ivy.getParent.getParent
      Files.createDirectories(base.getParent)
      if (Files.getFileStore(directory) != Files.getFileStore(base.getParent))
        _fail("staging and repository must be on the same filesystem")
      Files.createFile(base.resolveSibling(base.getFileName.toString + ".claim"))
      if (Files.exists(base, LinkOption.NOFOLLOW_LINKS)) _fail("publication coordinate already exists")
      Files.move(directory, base)
    }
    _write(batchmarker, Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact-batch.v2"),
      "scope" -> Json.fromString(input.scope), "selection" -> Json.fromString(input.selection),
      "publication" -> Json.fromString(publication), "owner" -> Json.fromString(input.owner),
      "batch" -> Json.fromString(publication), "producerSelectionId" -> Json.fromString(input.selection),
      "complete" -> Json.fromBoolean(true), "members" -> Json.arr(entries.map(_._3): _*)))
    val v3batchmarker = batchmarker.resolveSibling("completed-v3.json")
    _write(v3batchmarker, Json.obj("scope" -> Json.fromString(input.scope),
      "owner" -> Json.fromString(input.owner), "batch" -> Json.fromString(publication),
      "producerSelectionId" -> Json.fromString(input.selection), "complete" -> Json.fromBoolean(true),
      "members" -> Json.arr(entries.map(_._5): _*)))
    _write(input.result, Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact-result.v2"),
      "kind" -> Json.fromString("producer"), "scope" -> Json.fromString(input.scope),
      "selection" -> Json.fromString(input.selection), "publication" -> Json.fromString(publication),
      "members" -> Json.arr(entries.map { case (member, _, entry, resolved, _) =>
        entry.deepMerge(Json.obj("originJar" -> Json.fromString(member.jar.getAbsolutePath),
          "jar" -> Json.fromString(member.producer.jar.toString), "ivy" -> Json.fromString(member.producer.ivy.toString),
          "resolved" -> Json.arr(resolved: _*)))
      }: _*)))
    members.map(_.producer.jar.toFile)
  }

  def publish(input: Input, jar: File, ivy: File, organization: String, module: String, version: String,
    report: UpdateReport): File = {
    val producer = input.producer.getOrElse(_fail("producer input required"))
    val coordinate = producer.coordinate
    if (input.kind != "producer" || organization != coordinate.organization || module != coordinate.module ||
      coordinate.version != version + _suffix(input.scope, input.publication.get))
      _fail("producer project disagrees with declared coordinate")
    val resolved = _resolved(input, report)
    val base = producer.ivy.getParent.getParent
    val stageroot = input.staging.get.resolve(input.scope)
    Files.createDirectories(stageroot)
    val stage = stageroot.resolve(input.publication.get)
    Files.createDirectory(stage)
    val stagejar = stage.resolve("jars").resolve(coordinate.module + ".jar")
    val stageivy = stage.resolve("ivys").resolve("ivy.xml")
    Files.createDirectories(stagejar.getParent)
    Files.createDirectories(stageivy.getParent)
    Files.copy(jar.toPath, stagejar)
    val xml = scala.xml.XML.loadFile(ivy)
    val info = (xml \ "info").headOption.getOrElse(_fail("generated Ivy descriptor lacks info"))
    val transformed = new scala.xml.transform.RuleTransformer(new scala.xml.transform.RewriteRule {
      override def transform(node: scala.xml.Node): Seq[scala.xml.Node] = node match {
        case element: scala.xml.Elem if element eq info =>
          element % new scala.xml.UnprefixedAttribute("revision", coordinate.version, scala.xml.Null)
        case other => other
      }
    }).transform(xml).head
    scala.xml.XML.save(stageivy.toString, transformed, "UTF-8", xmlDecl = true)
    val descriptor = _descriptor(stageivy)
    if (descriptor._1 != coordinate) _fail("generated Ivy descriptor disagrees with producer coordinate")
    input.mappings.foreach { mapping =>
      if (descriptor._2.exists(x => x.organization == mapping.requested.organization && x.module == mapping.requested.module) &&
        !descriptor._2.contains(mapping.selected)) _fail("producer metadata omitted a selected dependency")
    }
    val marker = Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact-publication.v1"),
      "scope" -> Json.fromString(input.scope), "selection" -> Json.fromString(input.selection),
      "publication" -> Json.fromString(input.publication.get),
      "organization" -> Json.fromString(coordinate.organization), "module" -> Json.fromString(coordinate.module),
      "version" -> Json.fromString(coordinate.version),
      "dependencies" -> Json.arr(descriptor._2.map(_coordinate_json): _*))
    val stagedmarker = stage.resolve("completed.json")
    _write(stagedmarker, marker)
    Files.createDirectories(base.getParent)
    if (Files.getFileStore(stage) != Files.getFileStore(base.getParent))
      _fail("staging and repository must be on the same filesystem")
    // The claim serializes this coordinate across cooperating publishers. It is
    // deliberately retained after interruption: recovery must use a new identity.
    Files.createFile(base.resolveSibling(base.getFileName.toString + ".claim"))
    if (Files.exists(base, LinkOption.NOFOLLOW_LINKS)) _fail("publication coordinate already exists")
    Files.move(stage, base)
    _write(input.result, Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact-result.v1"),
      "kind" -> Json.fromString("producer"), "coordinate" -> _coordinate_json(coordinate),
      "originJar" -> Json.fromString(jar.getAbsolutePath), "jar" -> Json.fromString(producer.jar.toString),
      "ivy" -> Json.fromString(producer.ivy.toString), "dependencies" -> Json.arr(descriptor._2.map(_coordinate_json): _*),
      "resolved" -> Json.arr(resolved: _*)))
    producer.jar.toFile
  }

  private def _resolved(input: Input, report: UpdateReport): Vector[Json] = {
    val modules = report.configurations.flatMap(_.modules).filterNot(_.evicted)
    input.mappings.map { mapping =>
      val selected = mapping.selected
      val observed = report.configurations.flatMap(configuration =>
        configuration.modules ++ configuration.details.flatMap(_.modules)).map(_.module).
        filter(module => module.organization == selected.organization && module.name == selected.module)
      if (observed.exists(module => module.revision != selected.version && module.revision != mapping.requested.version))
        _fail("resolved graph has a contradictory mapped revision: " + selected)
      if (modules.exists(value => value.module.organization == selected.organization &&
        value.module.name == selected.module && value.module.revision != selected.version))
        _fail("original or conflicting mapped module remains in the resolved graph: " + selected)
      val artifacts = modules.filter(x => x.module.organization == selected.organization &&
        x.module.name == selected.module && x.module.revision == selected.version).flatMap(_.artifacts.map(_._2))
      val jars = artifacts.filter(file => file.isFile && file.getName.endsWith(".jar"))
      if (jars.isEmpty || !jars.exists(file => java.util.Arrays.equals(Files.readAllBytes(file.toPath), Files.readAllBytes(mapping.jar))))
        _fail("selected module did not resolve to its declared JAR: " + selected)
      Json.obj("requested" -> _coordinate_json(mapping.requested), "selected" -> _coordinate_json(selected),
        "jars" -> Json.arr(jars.distinct.map(file => Json.fromString(file.getAbsolutePath)): _*))
    }
  }

  private[cozy] def normalizeGeneratorRows(rows: Vector[(Coordinate, Vector[File])]): Vector[Int] = {
    val seen = scala.collection.mutable.LinkedHashMap.empty[(String, String), (Coordinate, Set[File], Int)]
    rows.zipWithIndex.foreach { case ((coordinate, jars), index) =>
      val key = coordinate.organization -> coordinate.module
      val paths = jars.map(_.getCanonicalFile).toSet
      seen.get(key) match {
        case Some((previous, previouspaths, _)) if previous.version != coordinate.version || previouspaths != paths =>
          def _paths_(values: Set[File]): String = values.toVector.map(_.toString).sorted.mkString("[", ", ", "]")
          _fail("conflicting generator module " + coordinate.organization + ":" + coordinate.module +
            ": revision " + previous.version + " jars " + _paths_(previouspaths) +
            "; revision " + coordinate.version + " jars " + _paths_(paths))
        case Some(_) => ()
        case None => seen.put(key, (coordinate, paths, index))
      }
    }
    seen.valuesIterator.map(_._3).toVector
  }

  def resolveGenerator(input: Input, resolution: sbt.librarymanagement.DependencyResolution,
    configuration: sbt.librarymanagement.UpdateConfiguration,
    warning: sbt.librarymanagement.UnresolvedWarningConfiguration, log: Logger): GeneratorResolution = {
    val generator = input.generator.getOrElse(_fail("generator selection required"))
    def _root_(name: String, version: String): ModuleID = {
      val coordinate = Coordinate("org.simplemodeling", name, version)
      val selected = generator.mappings.find(_.requested == coordinate).map(_.selected).getOrElse(coordinate)
      ModuleID(selected.organization, selected.module, selected.version).withCrossVersion(CrossVersion.disabled)
    }
    val roots = Vector(_root_("cozy_2.12", generator.cozyVersion), _root_("simplemodeler_2.12", generator.modelerVersion))
    val overrides = generator.mappings.map(value =>
      ModuleID(value.selected.organization, value.selected.module, value.selected.version).withCrossVersion(CrossVersion.disabled))
    val synthetic = ModuleID("org.goldenport.cozy.internal", "generator-resolution", "0")
    val descriptor = sbt.librarymanagement.ModuleDescriptorConfiguration(synthetic,
      sbt.librarymanagement.ModuleInfo("sbt-cozy generator resolution"))
      .withDependencies(roots).withOverrides(overrides)
      .withConfigurations(Vector(Configurations.Compile, Configurations.Runtime))
      .withDefaultConfiguration(Configurations.Runtime)
    val report = resolution.update(resolution.moduleDescriptor(descriptor), configuration, warning, log).
      fold(error => _fail("generator dependency resolution failed: " + error.toString), identity)
    _resolved(input.copy(mappings = generator.mappings), report)
    projectGeneratorReport(input, report, roots, overrides)
  }

  private[cozy] def projectGeneratorReport(input: Input, report: UpdateReport,
    roots: Vector[ModuleID], overrides: Vector[ModuleID]): GeneratorResolution = {
    val generator = input.generator.getOrElse(_fail("generator selection required"))
    val runtime = report.configurations.find(_.configuration.name == "runtime").
      getOrElse(_fail("generator runtime resolution report is missing"))
    val relevant = report.configurations.filter(c => Set("runtime", "compile").contains(c.configuration.name))
    val rows = relevant.flatMap(c => c.modules ++ c.details.flatMap(_.modules))
    val runtimeRows = (runtime.modules ++ runtime.details.flatMap(_.modules)).filterNot(_.evicted)
    val selectedRows = rows.filterNot(_.evicted)
    val byName = selectedRows.groupBy(row => row.module.organization -> row.module.name)
    byName.foreach { case (name, values) =>
      if (values.map(_.module.revision).distinct.size != 1)
        _fail("conflicting selected generator revisions: " + name)
    }
    val orderedNames = selectedRows.map(row => row.module.organization -> row.module.name).distinct
    val selectedModules = orderedNames.map(name => byName(name).head.module)
    def moduleIdentity(module: ModuleID): String = module.organization + ":" + module.name + ":" + module.revision
    val selectedByName = selectedModules.map(module => (module.organization -> module.name) -> module).toMap
    def selectedIdentity(module: ModuleID): String = selectedByName.get(module.organization -> module.name).
      map(moduleIdentity).getOrElse(moduleIdentity(module))
    def strings(values: Seq[String]): Json = Json.arr(values.map(Json.fromString): _*)
    def attributes(values: Map[String, String]): Json =
      Json.obj(values.toVector.map { case (key, value) => key -> Json.fromString(value) }: _*)
    def optional(value: Option[String]): Json = value.map(Json.fromString).getOrElse(Json.Null)
    def selector(artifact: Artifact): Json = Json.obj(
      "name" -> Json.fromString(artifact.name), "artifactType" -> Json.fromString(artifact.`type`),
      "extension" -> Json.fromString(artifact.extension), "classifier" -> optional(artifact.classifier),
      "extraAttributes" -> attributes(artifact.extraAttributes)).dropNullValues
    def key(module: String, artifact: Artifact): Json = selector(artifact).deepMerge(
      Json.obj("module" -> Json.fromString(module)))
    def exclusion(rule: sbt.librarymanagement.InclExclRule): Json = Json.obj(
      "organization" -> Json.fromString(rule.organization), "name" -> Json.fromString(rule.name),
      "artifact" -> Json.fromString(rule.artifact),
      "configurations" -> strings(rule.configurations.map(_.name)), "attributes" -> Json.obj())
    def target(module: ModuleID): Json = Json.obj(
      "organization" -> Json.fromString(module.organization), "name" -> Json.fromString(module.name),
      "revision" -> Json.fromString(module.revision), "configurations" -> optional(module.configurations),
      "explicitArtifacts" -> Json.arr(module.explicitArtifacts.map(selector): _*),
      "exclusions" -> Json.arr(module.exclusions.map(exclusion): _*),
      "isForce" -> Json.fromBoolean(module.isForce), "isChanging" -> Json.fromBoolean(module.isChanging),
      "isTransitive" -> Json.fromBoolean(module.isTransitive), "extraAttributes" -> attributes(module.extraAttributes)).dropNullValues
    def caller(value: sbt.librarymanagement.Caller): Json = {
      val module = value.caller
      Json.obj("from" -> Json.fromString(selectedIdentity(module)), "sourceRevision" -> Json.fromString(module.revision),
        "configurations" -> strings(value.callerConfigurations.map(_.name)),
        "attributes" -> attributes(value.callerExtraAttributes),
        "explicitArtifacts" -> Json.arr(module.explicitArtifacts.map(item => key(selectedIdentity(module), item)): _*),
        "exclusions" -> Json.arr(module.exclusions.map(exclusion): _*),
        "isForce" -> Json.fromBoolean(value.isForceDependency), "isChanging" -> Json.fromBoolean(value.isChangingDependency),
        "isTransitive" -> Json.fromBoolean(value.isTransitiveDependency),
        "isDirectlyForce" -> Json.fromBoolean(value.isDirectlyForceDependency))
    }
    val modulesV3 = selectedModules.map { module =>
      val mapping = generator.mappings.find(_.selected == Coordinate(module.organization, module.name, module.revision))
      val base = mapping.map(_.requested.version).getOrElse(module.revision)
      Json.obj("organization" -> Json.fromString(module.organization), "name" -> Json.fromString(module.name),
        "requestedVersion" -> Json.fromString(base), "selectedVersion" -> Json.fromString(module.revision),
        "baseVersion" -> Json.fromString(base), "development" -> Json.fromBoolean(mapping.nonEmpty),
        "descriptor" -> optional(mapping.map(_.ivy.toString)),
        "control" -> optional(mapping.map(value => value.ivy.getParent.getParent.resolve("completed-v3.json").toString))).dropNullValues
    }
    roots.foreach { root =>
      if (!selectedByName.get(root.organization -> root.name).exists(_.revision == root.revision))
        _fail("generator root did not resolve to its selected revision: " + root)
    }
    val batchpath = _within(input.repository, _batch_marker(input.repository, input.scope, generator.batch))
    val v3batchpath = batchpath.resolveSibling("completed-v3.json")
    if (!Files.isRegularFile(batchpath, LinkOption.NOFOLLOW_LINKS) ||
      !Files.isRegularFile(v3batchpath, LinkOption.NOFOLLOW_LINKS)) _fail("complete v3 generator batch unavailable")
    val batch = parse(new String(Files.readAllBytes(batchpath), StandardCharsets.UTF_8)).
      fold(error => _fail(error.message), identity).hcursor
    val producerSelection = _string(batch, "producerSelectionId")
    generator.mappings.foreach { mapping =>
      _verify_batch(input.repository, input.scope, input.owner, generator.batch, producerSelection, mapping.selected)
    }
    val runtimeBindings = runtimeRows.flatMap(row => row.artifacts.map { case (artifact, file) =>
      key(selectedIdentity(row.module), artifact) -> file.getCanonicalFile
    }).toSet
    val artifactsByKey = scala.collection.mutable.LinkedHashMap.empty[Json, (File, File, Boolean)]
    (runtimeRows ++ selectedRows).foreach { row =>
      val module = selectedIdentity(row.module)
      val mapping = generator.mappings.find(_.selected == Coordinate(row.module.organization, row.module.name, row.module.revision))
      row.artifacts.foreach { case (artifact, resolvedFile) =>
        val artifactKey = key(module, artifact)
        val isRuntime = runtimeBindings.contains(artifactKey -> resolvedFile.getCanonicalFile) &&
          artifact.extension == "jar" && artifact.`type` != "src" && artifact.`type` != "doc" &&
          !artifact.classifier.exists(value => value == "sources" || value == "javadoc")
        val launch = mapping.map { selected =>
          val controlpath = selected.ivy.getParent.getParent.resolve("completed-v3.json")
          if (!Files.isRegularFile(controlpath, LinkOption.NOFOLLOW_LINKS)) _fail("v3 publication control unavailable")
          val control = parse(new String(Files.readAllBytes(controlpath), StandardCharsets.UTF_8)).
            fold(error => _fail(error.message), identity).hcursor
          val published = control.get[Vector[Json]]("artifacts").fold(error => _fail(error.message), identity)
          val entry = published.find(_.hcursor.downField("key").focus.contains(artifactKey)).
            getOrElse(_fail("resolved artifact is absent from immutable publication: " + artifactKey))
          Paths.get(_string(entry.hcursor, "publicationFile")).toFile
        }.getOrElse(resolvedFile)
        if (!resolvedFile.isFile || !launch.isFile ||
          !java.util.Arrays.equals(Files.readAllBytes(resolvedFile.toPath), Files.readAllBytes(launch.toPath)))
          _fail("resolved artifact differs from immutable publication: " + artifactKey)
        artifactsByKey.get(artifactKey) match {
          case Some((previous, priorLaunch, priorRuntime)) if previous.getCanonicalFile != resolvedFile.getCanonicalFile ||
            priorLaunch.getCanonicalFile != launch.getCanonicalFile || priorRuntime != isRuntime =>
            _fail("conflicting generator artifact: " + artifactKey)
          case Some(_) => ()
          case None => artifactsByKey.put(artifactKey, (resolvedFile, launch, isRuntime))
        }
      }
    }
    val reportRows = rows.map { row =>
      val selected = selectedByName.getOrElse(row.module.organization -> row.module.name,
        _fail("report row has no selected module: " + row.module))
      if (row.missingArtifacts.nonEmpty || row.problem.exists(_.trim.nonEmpty))
        _fail("generator report has missing artifacts or a problem: " + row.module)
      val module = moduleIdentity(selected)
      Json.obj("module" -> Json.fromString(module), "revision" -> Json.fromString(row.module.revision),
        "configurations" -> strings(row.configurations.map(_.name)),
        "artifacts" -> Json.arr(row.artifacts.map { case (artifact, file) =>
          Json.obj("key" -> key(module, artifact), "file" -> Json.fromString(file.getAbsolutePath))
        }: _*), "missingArtifacts" -> Json.arr(row.missingArtifacts.map(key(module, _)): _*),
        "callers" -> Json.arr(row.callers.map(caller): _*),
        "eviction" -> Json.obj("evicted" -> Json.fromBoolean(row.evicted),
          "selectedRevision" -> optional(if (row.evicted) Some(selected.revision) else None),
          "details" -> strings(row.evictedData.toVector ++ row.evictedReason.toVector)).dropNullValues,
        "problem" -> optional(row.problem), "attributes" -> attributes(row.extraAttributes),
        "targetModule" -> target(row.module), "resolver" -> optional(row.resolver),
        "artifactResolver" -> optional(row.artifactResolver)).dropNullValues
    }
    val edges = rows.flatMap { row => row.callers.flatMap { value =>
      val from = selectedByName.get(value.caller.organization -> value.caller.name)
      val to = selectedByName.get(row.module.organization -> row.module.name)
      for (source <- from; destination <- to) yield Json.obj(
        "from" -> Json.fromString(moduleIdentity(source)), "to" -> Json.fromString(moduleIdentity(destination)),
        "requestedVersion" -> Json.fromString(row.module.revision))
    }}.distinct
    val artifactRecords = artifactsByKey.toVector.map { case (artifactKey, (resolvedFile, launch, isRuntime)) =>
      Json.obj("key" -> artifactKey, "resolverFile" -> Json.fromString(resolvedFile.getAbsolutePath),
        "launchFile" -> Json.fromString(launch.getAbsolutePath), "runtime" -> Json.fromBoolean(isRuntime))
    }
    val runtimeKeys = artifactsByKey.toVector.collect { case (artifactKey, (_, _, true)) => artifactKey }
    val classpath = artifactsByKey.valuesIterator.collect { case (_, launch, true) => launch.getAbsoluteFile }.toVector
    val v3 = Json.obj("producerRunReference" -> Json.fromString(java.util.UUID.randomUUID().toString),
      "requestRoots" -> strings(roots.map(moduleIdentity)),
      "exactOverrides" -> attributes(overrides.map(x => (x.organization + ":" + x.name) -> x.revision).toMap),
      "requestedConfigurations" -> strings(Vector("compile", "runtime")),
      "reportRows" -> Json.arr(reportRows: _*), "modules" -> Json.arr(modulesV3: _*),
      "artifacts" -> Json.arr(artifactRecords: _*), "edges" -> Json.arr(edges: _*),
      "runtimeClasspath" -> Json.arr(runtimeKeys: _*),
      "producerJvm" -> attributes(Vector("java.version", "java.vendor", "os.name", "os.arch").map(x =>
        x -> System.getProperty(x, "")).toMap),
      "resolverConfiguration" -> Json.obj("runtimeConfiguration" -> Json.fromString(runtime.configuration.name)),
      "optionalDiagnostics" -> Json.obj())
    GeneratorResolution(modulesV3, edges, classpath, artifactRecords, v3)
  }

  def runGenerator(input: Input, resolved: GeneratorResolution, sourceRoot: File, source: File,
    output: File, result: File, settings: Map[String, String], log: Logger): Vector[File] = {
    val generator = input.generator.getOrElse(_fail("generator selection required"))
    val outputpath = _within(input.buildroot, output.toPath.toAbsolutePath.normalize)
    val resultpath = _within(input.buildroot, result.toPath.toAbsolutePath.normalize)
    if (resultpath.startsWith(outputpath)) _fail("generator result is inside output")
    if (Files.exists(outputpath, LinkOption.NOFOLLOW_LINKS) || Files.exists(resultpath, LinkOption.NOFOLLOW_LINKS))
      _fail("generator output or result already exists")
    val sourcepath = source.toPath.toAbsolutePath.normalize
    val sourcebase = sourceRoot.toPath.toAbsolutePath.normalize
    if (!Files.isDirectory(sourcebase, LinkOption.NOFOLLOW_LINKS) ||
      !Files.isRegularFile(_within(sourcebase, sourcepath), LinkOption.NOFOLLOW_LINKS))
      _fail("generator source is unavailable or outside source root")
    if (resolved.resolutionV3.isNull) _fail("actual v3 resolution is required")
    val run = _identity(resultpath.getFileName.toString)
    val evidence = _within(input.ownerroot, input.result.resolveSibling(input.result.getFileName.toString + "." + run + ".generator-resolution.json"))
    val requestpath = _within(input.ownerroot, input.result.resolveSibling(input.result.getFileName.toString + "." + run + ".generator-request.json"))
    val classpath = resolved.classpath.map(file => Json.fromString(file.getAbsolutePath))
    val selection = Json.obj("scope" -> Json.fromString(input.scope), "owner" -> Json.fromString(input.owner),
      "selectionId" -> Json.fromString(input.selection), "batch" -> Json.fromString(generator.batch),
      "publicationRoot" -> Json.fromString(input.repository.toString))
    if (settings.get("generation.versions.cozy").exists(_ != generator.cozyVersion))
      _fail("generation Cozy version disagrees with selected generator base version")
    val actualsettings = settings + ("sbt.project_dir" -> input.buildroot.toString) +
      ("generation.versions.cozy" -> generator.cozyVersion)
    val action = if (actualsettings.get(CozyGenerationVersionContract.TARGET_KEY).contains("library"))
      "modeler-scala-value" else "modeler-scala"
    // Retain this exact in-memory projection across child execution; the file
    // is an audit record, not the source of the subsequent comparison.
    val producerResolution = resolved.resolutionV3
    _write(evidence, producerResolution)
    _write(requestpath, Json.obj("version" -> Json.fromString("v3"), "action" -> Json.fromString("generate-development"),
      "selection" -> selection, "roots" -> Json.obj("build" -> Json.fromString(input.buildroot.toString),
        "owner" -> Json.fromString(input.ownerroot.toString), "source" -> Json.fromString(sourcebase.toString),
        "output" -> Json.fromString(outputpath.toString), "result" -> Json.fromString(resultpath.toString)),
      "resolution" -> producerResolution,
      "batchRecord" -> Json.fromString(_batch_marker(input.repository, input.scope, generator.batch).
        resolveSibling("completed-v3.json").toString),
      "arguments" -> Json.arr(Vector(action, sourcepath.toString, "--save", outputpath.toString).map(Json.fromString): _*),
      "settings" -> Json.obj(actualsettings.toVector.map { case (key, value) => key -> Json.fromString(value) }: _*)))
    val java = new File(new File(System.getProperty("java.home"), "bin"), "java")
    val command = Vector(java.getAbsolutePath, "-cp", resolved.classpath.map(_.getAbsolutePath).mkString(File.pathSeparator),
      "cozy.Cozy", "sbt-bridge", "v3", "--request", requestpath.toString)
    val exit = scala.sys.process.Process(command, input.buildroot.toFile).!(scala.sys.process.ProcessLogger(
      line => log.debug("[sbt-cozy/cozy] " + line), line => log.warn("[sbt-cozy/cozy] " + line)))
    if (exit != 0) {
      val output = if (Files.isRegularFile(resultpath, LinkOption.NOFOLLOW_LINKS)) Json.fromString(resultpath.toString) else Json.Null
      _write(input.result, Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact-result.v1"),
        "kind" -> Json.fromString("consumer"), "scope" -> Json.fromString(input.scope),
        "selection" -> Json.fromString(input.selection), "results" -> Json.arr(),
        "generation" -> Json.obj("outcome" -> Json.fromString("child-failed"), "exitCode" -> Json.fromInt(exit),
          "batch" -> Json.fromString(generator.batch), "childResult" -> output,
          "resolution" -> Json.fromString(evidence.toString), "request" -> Json.fromString(requestpath.toString))))
      _fail("selected generator failed; child result: " + resultpath)
    }
    if (!Files.isRegularFile(resultpath, LinkOption.NOFOLLOW_LINKS)) _fail("selected generator did not return a result")
    val json = parse(new String(Files.readAllBytes(resultpath), StandardCharsets.UTF_8)).
      fold(error => _fail(error.message), identity).hcursor
    reconcileGeneratorResult(resolved, generator, selection, outputpath, json)
  }

  private[cozy] def reconcileGeneratorResult(resolved: GeneratorResolution, generator: GeneratorSelection,
    selection: Json, outputpath: Path, json: HCursor): Vector[File] = {
    val producerResolution = resolved.resolutionV3
    val classpath = resolved.classpath.map(file => Json.fromString(file.getAbsolutePath))
    if (_string(json, "version") != "v3" || _string(json, "outcome") != "generated" ||
      json.downField("selection").focus != Some(selection) ||
      json.downField("receivedResolution").focus != Some(producerResolution))
      _fail("generator result contradicts the current producer resolution")
    val observation = _object(json, "observation")
    if (observation.downField("classpath").focus != Some(Json.arr(classpath: _*)) ||
      _string(observation, "cozyVersion") != generator.cozyVersion ||
      _string(observation, "requiredSimpleModelerVersion") != generator.modelerVersion ||
      _string(observation, "simpleModelerVersion") != generator.modelerVersion)
      _fail("generator observation contradicts selected versions or classpath")
    observation.get[Vector[String]]("loaderUrls").fold(error => _fail(error.message), identity)
    Vector("osName", "osArch", "javaVersion", "javaVendor").foreach(key => _string(observation, key))
    val actualArtifacts = producerResolution.hcursor.get[Vector[Json]]("artifacts").
      fold(error => _fail(error.message), identity)
    Vector("cozy" -> "cozy_2.12", "simpleModeler" -> "simplemodeler_2.12").foreach { case (name, module) =>
      val selected = resolved.modules.find(value => value.hcursor.get[String]("name").toOption.contains(module)).
        getOrElse(_fail("missing generator origin module")).hcursor
      val moduleId = _string(selected, "organization") + ":" + _string(selected, "name") + ":" + _string(selected, "selectedVersion")
      val origin = _object(observation, name)
      _string(origin, "className")
      _string(origin, "resource")
      val source = Paths.get(_string(origin, "codeSource")).toRealPath()
      val owns = actualArtifacts.exists { artifact =>
        val cursor = artifact.hcursor
        _string(_object(cursor, "key"), "module") == moduleId &&
          cursor.get[Boolean]("runtime").getOrElse(false) &&
          Paths.get(_string(cursor, "launchFile")).toRealPath() == source
      }
      if (!owns) _fail("generator origin contradicts selected artifact")
    }
    val names = json.get[Vector[String]]("generatedFiles").fold(error => _fail(error.message), identity)
    if (names.distinct.size != names.size) _fail("generator returned duplicate outputs")
    val files = names.map { value =>
      val path = _relative(outputpath, value)
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) _fail("generator returned a missing output")
      path.toFile
    }
    if (!Files.isDirectory(outputpath, LinkOption.NOFOLLOW_LINKS)) _fail("generator output directory is missing")
    val stream = Files.walk(outputpath)
    val observed = try {
      import scala.collection.JavaConverters._
      stream.iterator.asScala.filter(path => path != outputpath).map { path =>
        _within(outputpath, path)
        if (Files.isSymbolicLink(path)) _fail("symbolic link in generated output")
        path
      }.filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).
        map(path => outputpath.relativize(path).toString.replace('\\', '/')).toVector.sorted
    } finally stream.close()
    if (observed != names.sorted) _fail("generator inventory differs from actual output files")
    val metadata = json.get[Vector[String]]("metadataFiles").fold(error => _fail(error.message), identity)
    if (metadata != names.filter(x => x.endsWith(".json") || x.endsWith(".yaml") || x.endsWith(".yml")))
      _fail("generator metadata inventory differs from output inventory")
    json.get[Vector[String]]("diagnostics").fold(error => _fail(error.message), identity)
    if (!files.exists(_.getName.endsWith(".scala"))) _fail("selected generator returned no Scala output")

    files
  }

  def validateGenerationPaths(input: Input, paths: Seq[File]): Unit =
    paths.foreach(file => _within(input.buildroot, file.toPath.toAbsolutePath.normalize))

  def validateClasspath(input: Input, report: UpdateReport, classpath: Seq[File], configuration: String): Unit = {
    _resolved(input, report)
    input.mappings.foreach { mapping =>
      val selectedbytes = Files.readAllBytes(mapping.jar)
      val jars = classpath.filter(file => file.isFile && file.getName.endsWith(".jar"))
      if (!jars.exists(file => java.util.Arrays.equals(Files.readAllBytes(file.toPath), selectedbytes)))
        _fail("selected JAR is absent from " + configuration + " classpath: " + mapping.selected)
      if (jars.exists(file => file.getName == mapping.requested.module + "-" + mapping.requested.version + ".jar"))
        _fail("original JAR remains on " + configuration + " classpath: " + mapping.requested)
    }
  }

  def record(input: Input, report: UpdateReport, classpath: Seq[File], project: String,
    configuration: String): Unit = synchronized {
    val actual = _resolved(input, report)
    val previous = if (Files.isRegularFile(input.result, LinkOption.NOFOLLOW_LINKS)) {
      val json = parse(new String(Files.readAllBytes(input.result), StandardCharsets.UTF_8)).
        fold(error => _fail(error.message), identity).hcursor
      if (_string(json, "schema") != "sbt-cozy.development-artifact-result.v1" ||
        _string(json, "scope") != input.scope || _string(json, "selection") != input.selection)
        _fail("existing result belongs to another selection")
      Some(json)
    } else None
    val prior = previous.map(_.get[Vector[Json]]("results").fold(error => _fail(error.message), identity)).getOrElse(Vector.empty)
    val entry = Json.obj("project" -> Json.fromString(project),
      "configuration" -> Json.fromString(configuration), "outcome" -> Json.fromString("success"),
      "classpath" -> Json.arr(classpath.map(file => Json.fromString(file.getAbsolutePath)): _*),
      "resolved" -> Json.arr(actual: _*))
    val retained = prior.filterNot { value =>
      val cursor = value.hcursor
      cursor.get[String]("project").toOption.contains(project) &&
        cursor.get[String]("configuration").toOption.contains(configuration)
    }
    val generation = previous.flatMap(_.downField("generation").focus).map(value => Json.obj("generation" -> value)).getOrElse(Json.obj())
    _write(input.result, Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact-result.v1"),
      "kind" -> Json.fromString("consumer"), "scope" -> Json.fromString(input.scope),
      "selection" -> Json.fromString(input.selection), "results" -> Json.arr((retained :+ entry): _*)).deepMerge(generation))
  }

  def recordGeneration(input: Input, results: Seq[File], generated: Seq[File]): Unit = synchronized {
    val previous = if (Files.isRegularFile(input.result, LinkOption.NOFOLLOW_LINKS)) {
      val cursor = parse(new String(Files.readAllBytes(input.result), StandardCharsets.UTF_8)).
        fold(error => _fail(error.message), identity).hcursor
      if (_string(cursor, "schema") != "sbt-cozy.development-artifact-result.v1" ||
        _string(cursor, "scope") != input.scope || _string(cursor, "selection") != input.selection)
        _fail("existing result belongs to another selection")
      cursor.get[Vector[Json]]("results").fold(error => _fail(error.message), identity)
    } else Vector.empty
    val records = results.map { file =>
      val path = _within(input.buildroot, file.toPath.toAbsolutePath.normalize)
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) _fail("generation result is unavailable")
      val result = parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).
        fold(error => _fail(error.message), identity).hcursor
      Json.obj("path" -> Json.fromString(path.toString),
        "producerResolution" -> result.downField("receivedResolution").focus.getOrElse(_fail("missing producer resolution")),
        "observation" -> result.downField("observation").focus.getOrElse(_fail("missing child observation")),
        "outcome" -> Json.fromString(_string(result, "outcome")))
    }
    _write(input.result, Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact-result.v1"),
      "kind" -> Json.fromString("consumer"), "scope" -> Json.fromString(input.scope),
      "selection" -> Json.fromString(input.selection), "results" -> Json.arr(previous: _*),
      "generation" -> Json.obj("outcome" -> Json.fromString("generated"),
        "batch" -> Json.fromString(input.generator.getOrElse(_fail("generator selection required")).batch),
        "results" -> Json.arr(results.map(file => Json.fromString(file.getAbsolutePath)): _*),
        "observations" -> Json.arr(records: _*), "products" -> Json.arr(generated.map(file => Json.fromString(file.getAbsolutePath)): _*))))
  }

  def validateSettings(input: Input, expected: Seq[ModuleID], overrides: Seq[ModuleID],
    repositories: Seq[Resolver], fullversion: String, binaryversion: String): Unit = {
    def cross(module: ModuleID): String = CrossVersion(module.crossVersion, fullversion, binaryversion).
      map(_(module.name)).getOrElse(module.name)
    input.mappings.foreach { mapping =>
      val matching = expected.filter(module => module.organization == mapping.requested.organization &&
        cross(module) == mapping.requested.module)
      if (matching.size != 1 || matching.head.revision != mapping.requested.version || matching.head.explicitArtifacts.nonEmpty)
        _fail("expected modules must declare the exact original coordinate without classifiers: " + mapping.requested)
      val selected = overrides.filter(module => module.organization == mapping.selected.organization &&
        cross(module) == mapping.selected.module)
      if (selected.isEmpty || selected.exists(_.revision != mapping.selected.version))
        _fail("missing or conflicting effective dependency override: " + mapping.selected)
    }
    if (!repositories.exists(_.name == "sm-artifact-" + input.scope))
      _fail("private resolver is missing from effective resolvers")
  }

  def selectDependency(module: ModuleID, mappings: Seq[Mapping], scalaVersion: String, scalaBinaryVersion: String): ModuleID = {
    val name = CrossVersion(module.crossVersion, scalaVersion, scalaBinaryVersion).map(_(module.name)).getOrElse(module.name)
    mappings.find(x => x.requested.organization == module.organization && x.requested.module == name).map { mapping =>
      if (module.revision != mapping.requested.version) _fail("requested direct dependency revision disagrees with selection: " + module)
      module.withName(mapping.selected.module).withRevision(mapping.selected.version).withCrossVersion(CrossVersion.disabled)
    }.getOrElse(module)
  }

}
