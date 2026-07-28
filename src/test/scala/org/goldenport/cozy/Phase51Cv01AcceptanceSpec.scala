package org.goldenport.cozy

import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class Phase51Cv01AcceptanceSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private def _config(lines: String*): CozyProjectConfig = CozyProjectConfig.parse(lines)

  "sbt-cozy CV-01 transport identities" should {
    "register descriptor identity mismatch" in {
      Given("a runtime descriptor whose declared identity differs from the requested target")
      When("the sbt-cozy configuration parser prepares descriptor identity")
      val config = _config("project:", "  name: declared")
      val identity = config.value("project.name")
      Then("CV-04 owns runtime descriptor target validation")
      identity shouldBe Some("declared")
      cancel("CV-04 owns descriptor identity mismatch")
    }
    "register descriptor byte or digest tampering" in {
      Given("resolved output metadata with an integrity marker")
      When("the sbt-cozy configuration parser prepares provenance evidence")
      val config = _config("artifact:", "  bytes: actual", "  digest: recorded")
      val evidence = config.mapUnder("artifact")
      Then("CV-05 owns provenance and provenance digest tampering")
      evidence shouldBe Map("bytes" -> "actual", "digest" -> "recorded")
      cancel("CV-05 owns provenance digest tampering")
    }
    "register provenance and digest" in {
      Given("resolved jar and generated output metadata plus generated safe scalar values")
      val scalar = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)
      val property = Prop.forAll(scalar, scalar) { (jar, output) =>
        _config("provenance:", s"  jar: $jar", s"  output: $output").
          mapUnder("provenance") == Map("jar" -> jar, "output" -> output)
      }
      When("the sbt-cozy configuration parser prepares provenance evidence")
      val config = _config("provenance:", "  jar: input.jar", "  output: generated.car")
      val evidence = config.mapUnder("provenance")
      val propertyresult = Test.check(
        Test.Parameters.default.withMinSuccessfulTests(50),
        property
      )
      Then("CV-05 owns provenance and provenance digest tampering")
      evidence shouldBe Map("jar" -> "input.jar", "output" -> "generated.car")
      propertyresult.passed shouldBe true
      cancel("CV-05 owns provenance")
    }
    "register collection identity" in {
      Given("two collections sharing one logical name in a build descriptor")
      When("the sbt-cozy configuration parser collects declared identities")
      val config = _config("collections:", "  - collection-a", "  - collection-b")
      val identities = config.list("collections")
      Then("the later identity stage owns exact identity")
      identities shouldBe Seq("collection-a", "collection-b")
      cancel("CI-01 owns exact identity")
    }
  }
}
