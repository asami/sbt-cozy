package org.goldenport.cozy

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 12, 2026
 * @version Aug. 13, 2026
 * @author  ASAMI, Tomoharu
 */
final class ComponentApiDependencyResolutionSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Component API dependency resolution" should {
    "remain disabled for a launcher project without CAR dependencies" in {
      Given("an sbt-cozy project that declares no CAR dependencies")
      val dependencies = Seq.empty[CarDependency]

      When("sbt-cozy determines whether component API resolution is required")
      val required = ComponentApiDependencyResolution.isRequired(dependencies)

      Then("ordinary JVM compilation does not require a generated component API descriptor")
      required shouldBe false
    }

    "remain enabled for a CAR consumer with an API dependency" in {
      Given("an sbt-cozy project that declares a CAR dependency")
      val dependencies = Seq(CarDependency("org.example.component", "Provider", "0.1.0"))

      When("sbt-cozy determines whether component API resolution is required")
      val required = ComponentApiDependencyResolution.isRequired(dependencies)

      Then("the consumer component API descriptor remains part of dependency matching")
      required shouldBe true
    }

    "build the exact four-field component API bridge payload" in {
      Given("a canonical component archive dependency")
      val archive = new java.io.File("target/sbt-cozy-test/work/component-api-dependency/provider.car").getAbsoluteFile

      When("the production bridge arguments are projected")
      val arguments = CozySbtBridge._component_api_dependency_arguments(
        Seq(CarDependency("org.example.component", "Provider", "0.1.0") -> archive)
      )

      Then("the v1 bridge receives namespace, ID, version, and absolute archive path")
      arguments shouldBe Vector(
        "--dependency",
        s"org.example.component\tProvider\t0.1.0\t${archive.toPath.normalize}"
      )
    }
  }
}
