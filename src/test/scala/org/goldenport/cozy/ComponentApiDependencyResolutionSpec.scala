package org.goldenport.cozy

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 12, 2026
 * @version Jul. 12, 2026
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
      val dependencies = Seq(CarDependency("provider", "0.1.0"))

      When("sbt-cozy determines whether component API resolution is required")
      val required = ComponentApiDependencyResolution.isRequired(dependencies)

      Then("the consumer component API descriptor remains part of dependency matching")
      required shouldBe true
    }
  }
}
