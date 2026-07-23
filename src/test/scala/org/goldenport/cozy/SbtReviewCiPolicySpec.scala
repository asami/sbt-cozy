package org.goldenport.cozy

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 23, 2026
 * @author  ASAMI, Tomoharu
 */
final class SbtReviewCiPolicySpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy Review CI policy" should {
    "default a CI environment to local deterministic providers and a loopback-only gateway" in {
      Given("a standard CI environment without Review opt-ins")
      val policy = SbtReviewCiPolicy.resolve(CozyProjectConfig.empty, Map("CI" -> "true")).fold(error => fail(error), identity)

      Then("external, AI, and remote gateway execution are refused")
      policy.providerEnabled("cozy") shouldBe true
      policy.providerEnabled("sbt-cozy") shouldBe true
      policy.providerEnabled("external") shouldBe false
      policy.providerEnabled("ai") shouldBe false
      policy.validateProviderKinds(SbtReviewCiPolicy.LOCAL_DETERMINISTIC_PROVIDER_KINDS) shouldBe Right(())
      policy.validateProviderKinds(Vector("external")) shouldBe Left("cbd-review-ci-provider-disabled:external")
      policy.validateEndpoint("http://127.0.0.1:8080/rest/v1/cbd-support/cbd-review-admin/post") shouldBe Right(())
      policy.validateEndpoint("https://review.example.test/review") shouldBe Left("cbd-review-ci-network-gateway-disabled")
    }

    "admit each exceptional CI capability only through its named configuration" in {
      Given("a project that explicitly enables an external provider, AI provider, and network gateway")
      val config = CozyProjectConfig.parse(Seq(
        "review:",
        "  ci:",
        "    profile: standard",
        "    external_providers_enabled: true",
        "    ai_providers_enabled: true",
        "    network_gateway_enabled: true"
      ))

      When("the Review policy is resolved")
      val policy = SbtReviewCiPolicy.resolve(config, Map.empty).fold(error => fail(error), identity)

      Then("the requested opt-ins are visible and the remote gateway is allowed")
      policy.providerEnabled("external") shouldBe true
      policy.providerEnabled("ai") shouldBe true
      policy.validateEndpoint("https://review.example.test/review") shouldBe Right(())
    }

    "reject an unrecognised explicit CI profile" in {
      SbtReviewCiPolicy.resolve(CozyProjectConfig.parse(Seq("review:", "  ci:", "    profile: nightly")), Map.empty) shouldBe
        Left("cbd-review-ci-profile-invalid")
    }
  }
}
