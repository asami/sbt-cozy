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
 * @version Nov. 12, 2026
 */
object CozyDevelopmentArtifactOverlay {
  final case class Coordinate(organization: String, module: String, version: String)
  final case class Mapping(requested: Coordinate, selected: Coordinate, jar: Path, ivy: Path)
  final case class Producer(coordinate: Coordinate, jar: Path, ivy: Path)
  final case class Input(kind: String, scope: String, selection: String, publication: Option[String],
    buildroot: Path, ownerroot: Path, repository: Path, staging: Option[Path], result: Path,
    mappings: Vector[Mapping], producer: Option[Producer])

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
    if (_string(adapter, "schema") != "sbt-cozy.development-artifact.v1") _fail("unexpected adapter schema")
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
    val values = adapter.get[Vector[Json]]("mappings").fold(error => _fail(error.message), identity)
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
      if (_string(marker, "schema") != "sbt-cozy.development-artifact-publication.v1" ||
        _string(marker, "scope") != scope || _string(marker, "publication") != sourcepublication ||
        _string(marker, "selection") != _identity(_string(entry, "publicationSelection")) ||
        _string(marker, "organization") != selected.organization ||
        _string(marker, "module") != selected.module || _string(marker, "version") != selected.version ||
        selected.version != requested.version + _suffix(scope, sourcepublication))
        _fail("foreign or contradictory publication")
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
    val publication = if (kind == "producer") Some(_identity(_string(cursor, "publication"))) else None
    val staging = if (kind == "producer") Some(_relative(ownerroot, _string(cursor, "staging"))) else None
    if (kind == "producer" && (_relative(ownerroot, _string(cursor, "repository")) != repository ||
      !Files.isDirectory(staging.get, LinkOption.NOFOLLOW_LINKS) || staging.get.startsWith(repository) ||
      repository.startsWith(staging.get) || result.startsWith(staging.get))) _fail("producer paths disagree or are unavailable")
    val producer = if (kind == "producer") {
      val item = _object(adapter, "producer")
      val coordinate = _coordinate(item)
      if (!coordinate.version.endsWith(_suffix(scope, publication.get))) _fail("invalid development version")
      val jar = _within(repository, _absolute(_string(item, "jar")))
      val ivy = _within(repository, _absolute(_string(item, "ivy")))
      if ((jar, ivy) != _layout(repository, coordinate)) _fail("producer paths disagree with coordinate")
      Some(Producer(coordinate, jar, ivy))
    } else None
    Input(kind, scope, selection, publication, buildroot, ownerroot, repository, staging, result, mappings, producer)
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
    val prior = if (Files.isRegularFile(input.result, LinkOption.NOFOLLOW_LINKS)) {
      val json = parse(new String(Files.readAllBytes(input.result), StandardCharsets.UTF_8)).
        fold(error => _fail(error.message), identity).hcursor
      if (_string(json, "schema") != "sbt-cozy.development-artifact-result.v1" ||
        _string(json, "scope") != input.scope || _string(json, "selection") != input.selection)
        _fail("existing result belongs to another selection")
      json.get[Vector[Json]]("results").fold(error => _fail(error.message), identity)
    } else Vector.empty
    val entry = Json.obj("project" -> Json.fromString(project),
      "configuration" -> Json.fromString(configuration), "outcome" -> Json.fromString("success"),
      "classpath" -> Json.arr(classpath.map(file => Json.fromString(file.getAbsolutePath)): _*),
      "resolved" -> Json.arr(actual: _*))
    val retained = prior.filterNot { value =>
      val cursor = value.hcursor
      cursor.get[String]("project").toOption.contains(project) &&
        cursor.get[String]("configuration").toOption.contains(configuration)
    }
    _write(input.result, Json.obj("schema" -> Json.fromString("sbt-cozy.development-artifact-result.v1"),
      "kind" -> Json.fromString("consumer"), "scope" -> Json.fromString(input.scope),
      "selection" -> Json.fromString(input.selection), "results" -> Json.arr((retained :+ entry): _*)))
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
