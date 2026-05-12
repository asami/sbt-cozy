package org.goldenport.cozy

import java.nio.file.{Files, Paths}
import org.scalatest.funsuite.AnyFunSuite

/*
 * @since   Apr. 23, 2026
 *  version Apr. 23, 2026
 * @version May. 13, 2026
 * @author  ASAMI, Tomoharu
 */
final class BridgeContractSpec extends AnyFunSuite {

  private def _normalizeJson(p: String): String =
    p.filterNot(_.isWhitespace)

  private val base = Paths.get(sys.props("user.dir")).toAbsolutePath.normalize()
  private val fixtureDir = base.resolve("bridge-fixtures").resolve("sbt-bridge").resolve("v1")

  test("vendored bridge fixtures exist") {
    Vector(
      "README.md",
      "contract.json",
      "request-generate.json",
      "request-package-car.json",
      "request-package-sar.json",
      "request-publish-project.json",
      "request-index-warehouse.json",
      "response-success.json",
      "response-error.json"
    ).foreach { name =>
      assert(Files.isRegularFile(fixtureDir.resolve(name)), s"missing fixture: $name")
    }
  }

  test("generated bridge request JSON matches canonical generate fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "generate",
      arguments = Vector("modeler-scala", "/tmp/sample.cml", "--save=/tmp/generated")
    )
    val expected = Files.readString(fixtureDir.resolve("request-generate.json"))
    assert(_normalizeJson(json) == _normalizeJson(expected))
  }

  test("generated bridge request JSON matches canonical package-car fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "package-car",
      arguments = Vector(
        "--name=sample-component",
        "--version=0.1.0",
        "--save=/tmp/sample-component.car",
        "--source-dir=/tmp/src",
        "--main-jar=/tmp/component-main.jar"
      )
    )
    val expected = Files.readString(fixtureDir.resolve("request-package-car.json"))
    assert(_normalizeJson(json) == _normalizeJson(expected))
  }

  test("generated bridge request JSON matches canonical package-sar fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "package-sar",
      arguments = Vector(
        "--name=sample-subsystem",
        "--version=0.1.0",
        "--save=/tmp/sample-subsystem.sar",
        "--source-dir=/tmp/src",
        "--source-files=subsystem-descriptor.yaml"
      )
    )
    val expected = Files.readString(fixtureDir.resolve("request-package-sar.json"))
    assert(_normalizeJson(json) == _normalizeJson(expected))
  }

  test("generated bridge request JSON matches canonical publish-project fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "publish-project",
      arguments = Vector(
        "/tmp/sample-project",
        "--save=/tmp/publish.d",
        "--kind=car"
      )
    )
    val expected = Files.readString(fixtureDir.resolve("request-publish-project.json"))
    assert(_normalizeJson(json) == _normalizeJson(expected))
  }

  test("generated bridge request JSON matches canonical index-warehouse fixture") {
    val json = CozySbtBridge.renderRequestJsonForTest(
      action = "index-warehouse",
      arguments = Vector(
        "/tmp/warehouse",
        "--save=/tmp/publish.d",
        "--name=textus-tutorial",
        "--maven-coordinates=org.example:textus-tutorial_3",
        "--repository-artifacts=car,sar",
        "--repository-modules=textus-tutorial"
      )
    )
    val expected = Files.readString(fixtureDir.resolve("request-index-warehouse.json"))
    assert(_normalizeJson(json) == _normalizeJson(expected))
  }

  test("consumer-side request parsing tolerates additive v1 fields") {
    val baseJson = Files.readString(fixtureDir.resolve("request-generate.json"))
    val extended = baseJson.replace("\n}", ",\n  \"extra\": \"ignored\"\n}\n")
    val normalized = _normalizeJson(extended)

    assert(normalized.contains("\"version\":\"v1\""))
    assert(normalized.contains("\"action\":\"generate\""))
    assert(normalized.contains("\"arguments\":[\"modeler-scala\""))
  }
}
