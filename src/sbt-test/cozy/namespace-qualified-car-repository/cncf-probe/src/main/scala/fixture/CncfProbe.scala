package fixture

import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.zip.ZipFile

import io.circe.Json
import io.circe.parser.parse
import org.goldenport.cncf.repository.{CanonicalCarRepositoryResolver, ComponentArtifactKind, ComponentRepositoryIndex, ComponentRepositoryIndexEntry}
import org.yaml.snakeyaml.{LoaderOptions, Yaml}

import scala.jdk.CollectionConverters.*

object CncfProbe {
  def main(args: Array[String]): Unit = {
    val mode = args.headOption.getOrElse(sys.error("cncf probe mode must be online or offline"))
    require(mode == "online" || mode == "offline", s"unsupported cncf probe mode: $mode")
    val root = Paths.get(sys.props.getOrElse("phase56.fixture.root", sys.error("phase56.fixture.root is required"))).toAbsolutePath.normalize()
    val endpoint = Files.readString(root.resolve("source-url.txt"), StandardCharsets.UTF_8).trim
    val warehouseendpoint = endpoint.stripSuffix("/repository/car")
    val indexcache = root.resolve("cncf-index-cache.json")
    val indextext = if (mode == "online") {
      val text = _read_url(s"$warehouseendpoint/repository/catalog/index.json")
      Files.writeString(indexcache, text, StandardCharsets.UTF_8)
      text
    } else {
      require(Files.isRegularFile(root.resolve("source-stopped.txt")), "cncf offline probe requires source-stopped marker")
      require(Files.isRegularFile(indexcache), "cncf offline probe requires cached index")
      Files.readString(indexcache, StandardCharsets.UTF_8)
    }
    val index = ComponentRepositoryIndex.parse(indextext).fold(message => sys.error(message), identity)
    val alphaentry = _entry(index, "org.alpha.textus", "Shared")
    val cacheroot = root.resolve("cncf-cache")
    val alpha = _resolve(alphaentry, endpoint, warehouseendpoint, root, cacheroot, mode)
    val alphaabi = parse(_read_zip(alpha, "abi-manifest.json")).fold(error => sys.error(error.message), identity)
    val dependencies = (alphaabi.hcursor.downField("abi").downField("dependencies").as[Vector[Json]]).fold(error => sys.error(error.message), identity)
    val dependency = dependencies.find { value =>
      val cursor = value.hcursor
      cursor.get[String]("namespace").toOption.contains("org.beta.textus") &&
      cursor.get[String]("id").toOption.contains("Shared")
    }.getOrElse(sys.error("alpha ABI does not declare the beta transitive dependency"))
    require(dependency.asObject.exists(_.keys.toSet == Set("namespace", "id", "abiRange")), "alpha ABI dependency is not canonical")
    require(dependency.hcursor.get[String]("abiRange").toOption.contains("[1,2)"), "alpha ABI dependency has the wrong abiRange")
    val dependencynamespace = dependency.hcursor.get[String]("namespace").fold(error => sys.error(error.message), identity)
    val dependencyid = dependency.hcursor.get[String]("id").fold(error => sys.error(error.message), identity)
    val betaentry = _entry(index, dependencynamespace, dependencyid)
    val beta = _resolve(betaentry, endpoint, warehouseendpoint, root, cacheroot, mode)
    require(alpha.getFileName == beta.getFileName, "CNCF resolver must preserve equal CAR filenames")
    require(!java.util.Arrays.equals(Files.readAllBytes(alpha), Files.readAllBytes(beta)), "CNCF resolver collapsed distinct namespace-qualified CAR payloads")
    val marker = root.resolve(s"cncf-$mode.txt")
    Files.writeString(marker, s"alpha=$alpha\nbeta=$beta\n", StandardCharsets.UTF_8)
  }

  private def _entry(index: ComponentRepositoryIndex, namespace: String, id: String): ComponentRepositoryIndexEntry =
    index.artifacts.find(entry =>
      entry.kind == ComponentArtifactKind.Car && entry.namespace.contains(namespace) && entry.id.contains(id)
    ).getOrElse(sys.error(s"CNCF index lacks $namespace/$id"))

  private def _resolve(
    entry: ComponentRepositoryIndexEntry,
    endpoint: String,
    warehouseendpoint: String,
    root: Path,
    cacheroot: Path,
    mode: String
  ): Path = {
    val release = entry.recommended.getOrElse(sys.error(s"CNCF index lacks recommended release for ${entry.namespace}.${entry.id}"))
    val namespace = entry.namespace.getOrElse(sys.error(s"CNCF index lacks namespace for ${entry.artifactId}"))
    val id = entry.id.getOrElse(sys.error(s"CNCF index lacks id for ${entry.artifactId}"))
    val catalogrelease = _catalog(entry, namespace, id, release, warehouseendpoint, root, mode)
    val qualified = s"$namespace.$id"
    val repositories = if (mode == "online") Seq(endpoint) else Seq.empty
    val path = CanonicalCarRepositoryResolver.resolve(qualified, release, repositories, cacheroot).fold(message => sys.error(message), identity)
    val digest = _sha256(path)
    require(digest == catalogrelease.checksumsha256, s"CNCF CAR digest disagrees with catalog for $qualified:$release: actual=$digest expected=${catalogrelease.checksumsha256}")
    path
  }

  private def _catalog(
    entry: ComponentRepositoryIndexEntry,
    namespace: String,
    id: String,
    release: String,
    warehouseendpoint: String,
    root: Path,
    mode: String
  ): CatalogRelease = {
    val catalogcache = root.resolve("cncf-catalog-cache").toAbsolutePath.normalize()
    val catalogpath = catalogcache.resolve(entry.catalog).normalize()
    require(catalogpath.startsWith(catalogcache), s"CNCF catalog path escapes cache: ${entry.catalog}")
    val text = if (mode == "online") {
      require(!entry.catalog.startsWith("/"), s"CNCF index catalog path must be relative: ${entry.catalog}")
      val url = s"${warehouseendpoint.stripSuffix("/")}/repository/catalog/${entry.catalog}"
      val fetched = _read_url(url)
      Files.createDirectories(catalogpath.getParent)
      Files.writeString(catalogpath, fetched, StandardCharsets.UTF_8)
      fetched
    } else {
      require(Files.isRegularFile(catalogpath), s"CNCF offline probe requires cached catalog: ${entry.catalog}")
      Files.readString(catalogpath, StandardCharsets.UTF_8)
    }
    _validate_catalog(text, entry, namespace, id, release)
  }

  private def _validate_catalog(
    text: String,
    entry: ComponentRepositoryIndexEntry,
    namespace: String,
    id: String,
    release: String
  ): CatalogRelease = {
    val options = new LoaderOptions()
    options.setAllowDuplicateKeys(false)
    val parsed = try new Yaml(options).load[Any](text)
    catch {
      case error: Throwable => sys.error(s"CNCF detailed CAR catalog YAML is invalid for ${entry.catalog}: ${error.getMessage}")
    }
    val root = _map(parsed, s"catalog ${entry.catalog}")
    val rootallowed = Set("schemaVersion", "kind", "namespace", "id", "artifactId", "recommended", "latestStable", "latestSnapshot", "status", "aliases", "tags", "terms", "versions")
    _require_fields(root, rootallowed, s"catalog ${entry.catalog}")
    require(_scalar(_required(root, "schemaVersion", "catalog schemaVersion"), "catalog schemaVersion") == "2", s"CNCF detailed CAR catalog schemaVersion must be 2: ${entry.catalog}")
    require(_string(_required(root, "kind", "catalog kind"), "catalog kind") == "car", s"CNCF detailed catalog kind must be car: ${entry.catalog}")
    require(_string(_required(root, "namespace", "catalog namespace"), "catalog namespace") == namespace, s"CNCF catalog namespace disagrees with index: ${entry.catalog}")
    require(_string(_required(root, "id", "catalog id"), "catalog id") == id, s"CNCF catalog id disagrees with index: ${entry.catalog}")
    val artifactid = _string(_required(root, "artifactId", "catalog artifactId"), "catalog artifactId")
    require(entry.artifactId == artifactid, s"CNCF catalog artifactId disagrees with index: ${entry.catalog}")
    require(_string(_required(root, "recommended", "catalog recommended"), "catalog recommended") == release, s"CNCF catalog recommended selector disagrees with index: ${entry.catalog}")
    val versions = _sequence(_required(root, "versions", "catalog versions"), s"catalog versions ${entry.catalog}")
    val versionallowed = Set("version", "channel", "status", "component", "publishedAt", "file", "runtime", "checksum", "integrityKey")
    val versionmaps = versions.map(value => _map(value, s"catalog version ${entry.catalog}"))
    versionmaps.foreach(value => _require_fields(value, versionallowed, s"catalog version ${entry.catalog}"))
    val versionnames = versionmaps.map(value => _string(_required(value, "version", "catalog version"), "catalog version"))
    require(versionnames.distinct.size == versionnames.size, s"CNCF catalog contains duplicate versions: ${entry.catalog}")
    val matching = versionmaps.filter { value =>
      _string(_required(value, "version", "catalog version"), "catalog version") == release
    }
    require(matching.size == 1, s"CNCF catalog must contain exactly one selected version: ${entry.catalog} release=$release count=${matching.size}")
    val version = matching.head
    val file = _string(_required(version, "file", "catalog version file"), "catalog version file")
    val expectedfile = s"repository/car/${namespace.replace('.', '/')}/$artifactid/$release/$artifactid-$release.car"
    require(file == expectedfile, s"CNCF catalog CAR file projection disagrees: actual=$file expected=$expectedfile")
    val checksum = _map(_required(version, "checksum", "catalog checksum"), s"catalog checksum ${entry.catalog}")
    _require_fields(checksum, Set("sha256"), s"catalog checksum ${entry.catalog}")
    val checksumsha256 = _string(_required(checksum, "sha256", "catalog checksum.sha256"), "catalog checksum.sha256")
    require(checksumsha256.matches("[0-9a-f]{64}"), s"CNCF catalog checksum.sha256 must be lowercase 64-hex: ${entry.catalog}")
    val integritykey = _string(_required(version, "integrityKey", "catalog integrityKey"), "catalog integrityKey")
    val expectedkey = s"$namespace:$artifactid:$release@sha256:$checksumsha256"
    require(integritykey == expectedkey, s"CNCF catalog integrityKey projection disagrees: actual=$integritykey expected=$expectedkey")
    CatalogRelease(release, file, checksumsha256, integritykey)
  }

  private def _require_fields(map: java.util.Map[Any, Any], allowed: Set[String], context: String): Unit = {
    val actual = map.keySet.asScala.map {
      case value: String => value
      case value => sys.error(s"$context has non-string field name: $value")
    }.toSet
    val unknown = actual.diff(allowed)
    require(unknown.isEmpty, s"$context has non-canonical fields: ${unknown.toVector.sorted.mkString(",")}")
  }

  private def _required(map: java.util.Map[Any, Any], key: String, context: String): Any =
    Option(map.get(key)).getOrElse(sys.error(s"$context is missing: $key"))

  private def _string(value: Any, context: String): String = value match {
    case text: String => text
    case _ => sys.error(s"$context must be a YAML string")
  }

  private def _scalar(value: Any, context: String): String = value match {
    case text: String => text
    case number: java.lang.Number => number.toString
    case _ => sys.error(s"$context must be a YAML scalar")
  }

  private def _map(value: Any, context: String): java.util.Map[Any, Any] = value match {
    case map: java.util.Map[?, ?] => map.asInstanceOf[java.util.Map[Any, Any]]
    case _ => sys.error(s"$context must be a YAML mapping")
  }

  private def _sequence(value: Any, context: String): Vector[Any] = value match {
    case values: java.util.List[?] => values.asScala.toVector
    case _ => sys.error(s"$context must be a YAML sequence")
  }

  private def _read_url(url: String): String = {
    val input = new URL(url).openStream()
    try scala.io.Source.fromInputStream(input, "UTF-8").mkString
    finally input.close()
  }

  private def _read_zip(path: Path, name: String): String = {
    val zip = new ZipFile(path.toFile)
    try {
      val entry = Option(zip.getEntry(name)).getOrElse(sys.error(s"CAR archive lacks $name: $path"))
      val input = zip.getInputStream(entry)
      try scala.io.Source.fromInputStream(input, "UTF-8").mkString
      finally input.close()
    } finally zip.close()
  }

  private def _sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).map(byte => f"${byte & 0xff}%02x").mkString

  private final case class CatalogRelease(version: String, file: String, checksumsha256: String, integritykey: String)
}
