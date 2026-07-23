package org.goldenport.cozy

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 23, 2026
 * @version Jul. 23, 2026
 * @author  ASAMI, Tomoharu
 */
final class SbtReviewPublicationBoundarySpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy Review publication boundary" should {
    "keep Review gating explicit for publication and distribution only" in {
      Given("one CAR packaging task surface")

      When("standard and Review-gated labels are resolved")
      val standardpublish = CozyPlugin.publishTaskLabel("car", local = false)
      val standardlocalpublish = CozyPlugin.publishTaskLabel("car", local = true)
      val reviewpublish = CozyPlugin.reviewGatedTaskLabel("car", "publish")
      val reviewdistribution = CozyPlugin.reviewGatedTaskLabel("car", "distribute")

      Then("ordinary publication remains its existing target and Review exposes opt-in aliases")
      standardpublish shouldBe "cozyPublishCar"
      standardlocalpublish shouldBe "cozyPublishLocalCar"
      reviewpublish shouldBe "cozyPublishCar"
      reviewdistribution shouldBe "cozyDistributeCar"
    }

    "reject deployment as a Review-gated operation" in {
      Given("one attempt to introduce a Review deployment alias")

      When("the Review task boundary resolves the operation")
      val error = intercept[RuntimeException] {
        CozyPlugin.reviewGatedTaskLabel("car", "deploy")
      }

      Then("deployment remains outside the Review task surface")
      error.getMessage should include("invalid review-gated operation")
    }
  }
}
