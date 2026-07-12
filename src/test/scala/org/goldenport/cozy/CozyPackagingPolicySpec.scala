package org.goldenport.cozy

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 12, 2026
 * @version Jul. 12, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyPackagingPolicySpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Cozy packaging policy" should {
    "keep ordinary sbt projects on Maven publication" in {
      Given("a project without Cozy archive metadata")
      val metadata = CozyProjectConfig.empty

      When("sbt-cozy resolves its packaging policy")
      val policy = CozyPackagingPolicy.resolve(metadata)

      Then("standard Maven publication remains active")
      policy.packaging shouldBe "maven"
      policy.wireStandardPublishTasks shouldBe false
    }

    "route a project-declared CAR through Cozy publication" in {
      Given("project metadata declaring a CAR")
      val metadata = CozyProjectConfig.parse(Seq(
        "project:",
        "  kind: car"
      ))

      When("sbt-cozy resolves its packaging policy")
      val policy = CozyPackagingPolicy.resolve(metadata)

      Then("standard publish is routed to the CAR publisher")
      policy.packaging shouldBe "car"
      policy.wireStandardPublishTasks shouldBe true
    }

    "prefer the explicit packaging kind in project metadata" in {
      Given("project metadata declaring SAR packaging")
      val metadata = CozyProjectConfig.parse(Seq(
        "project:",
        "  kind: library",
        "packaging:",
        "  kind: sar"
      ))

      When("sbt-cozy resolves its packaging policy")
      val policy = CozyPackagingPolicy.resolve(metadata)

      Then("standard publish is routed to the SAR publisher")
      policy.packaging shouldBe "sar"
      policy.wireStandardPublishTasks shouldBe true
    }

    "keep non-archive project kinds on Maven publication" in {
      Given("project metadata declaring a library")
      val metadata = CozyProjectConfig.parse(Seq(
        "project:",
        "  kind: library"
      ))

      When("sbt-cozy resolves its packaging policy")
      val policy = CozyPackagingPolicy.resolve(metadata)

      Then("standard Maven publication remains active")
      policy.packaging shouldBe "maven"
      policy.wireStandardPublishTasks shouldBe false
    }
  }
}
