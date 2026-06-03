package org.goldenport.cozy

import java.nio.file.{Files, Paths}
import org.scalatest.funsuite.AnyFunSuite

/*
 * @since   Apr. 23, 2026
 *  version May. 20, 2026
 * @version Jun.  4, 2026
 * @author  ASAMI, Tomoharu
 */
final class BridgeContractSpec extends AnyFunSuite {

  private def _normalize_json(p: String): String =
    p.filterNot(_.isWhitespace)

  private val _base = Paths.get(sys.props("user.dir")).toAbsolutePath.normalize()
  private val _fixture_dir = _base.resolve("bridge-fixtures").resolve("sbt-bridge").resolve("v1")

  test("vendored bridge fixtures exist") {
    Vector(
      "README.md",
      "contract.json",
      "request-generate.json",
      "request-package-car.json",
      "request-package-sar.json",
      "request-publish-car.json",
      "request-publish-sar.json",
      "request-publish-project.json",
      "request-distribute-samples.json",
      "request-index-warehouse.json",
      "response-success.json",
      "response-error.json"
    ).foreach { name =>
      assert(Files.isRegularFile(_fixture_dir.resolve(name)), s"missing fixture: $name")
    }
  }

  test("generated bridge request JSON matches canonical generate fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "generate",
      arguments = Vector("modeler-scala", "/tmp/sample.cml", "--save", "/tmp/generated")
    )
    val expected = Files.readString(_fixture_dir.resolve("request-generate.json"))
    assert(_normalize_json(json) == _normalize_json(expected))
  }

  test("generated bridge request JSON matches canonical package-car fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "package-car",
      arguments = Vector(
        "--name",
        "sample-component",
        "--version",
        "0.1.0",
        "--save",
        "/tmp/sample-component.car",
        "--project-dir",
        "/tmp/sample-project",
        "--main-jar",
        "/tmp/component-main.jar"
      )
    )
    val expected = Files.readString(_fixture_dir.resolve("request-package-car.json"))
    assert(_normalize_json(json) == _normalize_json(expected))
  }

  test("generated bridge request JSON matches canonical package-sar fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "package-sar",
      arguments = Vector(
        "--name",
        "sample-subsystem",
        "--version",
        "0.1.0",
        "--save",
        "/tmp/sample-subsystem.sar",
        "--source-dir",
        "/tmp/src",
        "--source-files",
        "subsystem-descriptor.yaml"
      )
    )
    val expected = Files.readString(_fixture_dir.resolve("request-package-sar.json"))
    assert(_normalize_json(json) == _normalize_json(expected))
  }

  test("generated bridge request JSON matches canonical publish-car fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "publish-car",
      arguments = Vector(
        "/tmp/sample-project",
        "--warehouse",
        "/tmp/warehouse",
        "--name",
        "sample-component",
        "--version",
        "0.1.0",
        "--car",
        "/tmp/sample-component.car"
      )
    )
    val expected = Files.readString(_fixture_dir.resolve("request-publish-car.json"))
    assert(_normalize_json(json) == _normalize_json(expected))
  }

  test("generated bridge request JSON matches canonical publish-sar fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "publish-sar",
      arguments = Vector(
        "/tmp/sample-project",
        "--warehouse",
        "/tmp/warehouse",
        "--name",
        "sample-subsystem",
        "--version",
        "0.1.0",
        "--sar",
        "/tmp/sample-subsystem.sar"
      )
    )
    val expected = Files.readString(_fixture_dir.resolve("request-publish-sar.json"))
    assert(_normalize_json(json) == _normalize_json(expected))
  }

  test("generated bridge request JSON matches canonical publish-project fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "publish-project",
      arguments = Vector(
        "/tmp/sample-project",
        "--save",
        "/tmp/publish.d",
        "--kind",
        "car"
      )
    )
    val expected = Files.readString(_fixture_dir.resolve("request-publish-project.json"))
    assert(_normalize_json(json) == _normalize_json(expected))
  }

  test("generated bridge request JSON matches canonical distribute-samples fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "distribute-samples",
      arguments = Vector(
        "/tmp/sample-project",
        "--warehouse",
        "/tmp/warehouse",
        "--name",
        "textus-tutorial",
        "--version",
        "0.1.0",
        "--path",
        "textus/tutorial/textus-tutorial",
        "--samples-dir",
        "/tmp/sample-project/samples",
        "--dry-run"
      )
    )
    val expected = Files.readString(_fixture_dir.resolve("request-distribute-samples.json"))
    assert(_normalize_json(json) == _normalize_json(expected))
  }

  test("generated bridge request JSON matches canonical index-warehouse fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "index-warehouse",
      arguments = Vector(
        "/tmp/warehouse",
        "--save",
        "/tmp/publish.d",
        "--name",
        "textus-tutorial",
        "--maven-coordinates",
        "org.example:textus-tutorial_3",
        "--repository-artifacts",
        "car,sar",
        "--repository-modules",
        "textus-tutorial",
        "--download-samples",
        "textus-tutorial"
      )
    )
    val expected = Files.readString(_fixture_dir.resolve("request-index-warehouse.json"))
    assert(_normalize_json(json) == _normalize_json(expected))
  }

  test("consumer-side request parsing tolerates additive v1 fields") {
    val basejson = Files.readString(_fixture_dir.resolve("request-generate.json"))
    val extended = basejson.replace("\n}", ",\n  \"extra\": \"ignored\"\n}\n")
    val normalized = _normalize_json(extended)

    assert(normalized.contains("\"version\":\"v1\""))
    assert(normalized.contains("\"action\":\"generate\""))
    assert(normalized.contains("\"arguments\":[\"modeler-scala\""))
  }
}
