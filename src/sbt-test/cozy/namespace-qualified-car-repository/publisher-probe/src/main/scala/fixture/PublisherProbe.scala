package fixture

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.jar.{JarEntry, JarOutputStream}
import java.util.zip.{ZipEntry, ZipOutputStream}

import scala.collection.JavaConverters._

import cozy.Cozy
import cozy.archive.ComponentRepositoryIndex

object PublisherProbe {
  private val _version = "0.6.0"
  private val _published_at = "2026-08-07T00:00:00Z"
  private val _artifact = "textus-shared"

  def main(args: Array[String]): Unit = {
    val root = Paths.get(sys.props.getOrElse("phase56.fixture.root", sys.error("phase56.fixture.root is required"))).toAbsolutePath.normalize()
    val warehouse = root.resolve("warehouse")
    val alpha = _publish(root.resolve("publisher-projects/alpha"), warehouse, "org.alpha.textus", "Shared", "alpha")
    val beta = _publish(root.resolve("publisher-projects/beta"), warehouse, "org.beta.textus", "Shared", "beta")
    val index = ComponentRepositoryIndex.load(warehouse.resolve(ComponentRepositoryIndex.PUBLIC_PATH))
    val identities = index.artifacts.map(_.identity).toSet
    require(identities.contains(("car", "org.alpha.textus", "Shared")), s"publisher index lacks alpha identity: $identities")
    require(identities.contains(("car", "org.beta.textus", "Shared")), s"publisher index lacks beta identity: $identities")
    require(alpha.getFileName == beta.getFileName, "namespace-qualified CARs must retain the same filename projection")
    require(!java.util.Arrays.equals(Files.readAllBytes(alpha), Files.readAllBytes(beta)), "namespace-qualified CAR payloads must differ")
    require(Files.readString(alpha.resolveSibling(alpha.getFileName.toString + ".sha256"), StandardCharsets.UTF_8) == _sha256(alpha) + "\n", "alpha checksum sidecar disagrees")
    require(Files.readString(beta.resolveSibling(beta.getFileName.toString + ".sha256"), StandardCharsets.UTF_8) == _sha256(beta) + "\n", "beta checksum sidecar disagrees")
    Files.writeString(root.resolve("publisher-evidence.txt"), s"alpha=$alpha\nbeta=$beta\n", StandardCharsets.UTF_8)
  }

  private def _publish(project: Path, warehouse: Path, namespace: String, id: String, payload: String): Path = {
    val projectyaml =
      s"""project:
         |  namespace: $namespace
         |  id: $id
         |  name: ${_artifact}
         |  component:
         |    version: ${_version}
         |""".stripMargin
    Files.createDirectories(project.resolve("src/main/cozy"))
    Files.writeString(project.resolve("project.yaml"), projectyaml, StandardCharsets.UTF_8)
    Files.writeString(project.resolve(s"src/main/cozy/${_artifact}.cml"), "# ENTITY\n\n## Shared\n", StandardCharsets.UTF_8)
    val archive = project.resolve(s"${_artifact}-${_version}.car")
    val componentjar = _jar(payload)
    _archive(
      archive,
      Vector(
        "component-descriptor.json" -> _descriptor(namespace, id).getBytes(StandardCharsets.UTF_8),
        "abi-manifest.json" -> _abi(namespace, id, if (namespace == "org.alpha.textus") Some("org.beta.textus") else None).getBytes(StandardCharsets.UTF_8),
        "component/main.jar" -> componentjar
      )
    )
    Cozy.main(Array(
      "publish-car",
      project.toString,
      "--warehouse", warehouse.toString,
      "--name", _artifact,
      "--version", _version,
      "--car", archive.toString,
      "--published-at", _published_at
    ))
    val relative = s"repository/car/${namespace.replace('.', '/')}/${_artifact}/${_version}/${_artifact}-${_version}.car"
    val published = warehouse.resolve(relative)
    require(Files.isRegularFile(published), s"Cozy did not publish $namespace/$id: $published")
    published
  }

  private def _descriptor(namespace: String, id: String): String =
    s"""{"schemaVersion":3,"component":{"namespace":"$namespace","id":"$id","version":"${_version}"}}"""

  private def _abi(namespace: String, id: String, dependency: Option[String]): String = {
    val dependencies = dependency.map(value => s"""{"namespace":"$value","id":"Shared","abiRange":"[1,2)"}""").getOrElse("")
    val comma = if (dependencies.nonEmpty) dependencies else ""
    s"""{"format":"cozy.car.abi-manifest.v2","component":{"namespace":"$namespace","id":"$id","version":"${_version}"},"abi":{"version":1,"exports":{"components":[{"namespace":"$namespace","id":"$id"}],"services":[],"operations":[],"types":[],"entities":[]},"dependencies":[$comma]}}"""
  }

  private def _jar(payload: String): Array[Byte] = {
    val bytes = new ByteArrayOutputStream()
    val output = new JarOutputStream(bytes)
    try {
      output.putNextEntry(new JarEntry("fixture-payload.txt"))
      output.write(payload.getBytes(StandardCharsets.UTF_8))
      output.closeEntry()
    } finally output.close()
    bytes.toByteArray
  }

  private def _archive(archive: Path, entries: Vector[(String, Array[Byte])]): Unit = {
    Files.createDirectories(archive.getParent)
    val output = new ZipOutputStream(Files.newOutputStream(archive))
    try entries.foreach { case (name, bytes) =>
      output.putNextEntry(new ZipEntry(name))
      output.write(bytes)
      output.closeEntry()
    } finally output.close()
  }

  private def _sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).map(byte => f"${byte & 0xff}%02x").mkString
}
