package org.goldenport.cozy

import java.net.URI

/*
 * @since   Jul. 16, 2026
 * @version Jul. 23, 2026
 * @author  ASAMI, Tomoharu
 */
/**
 * The sbt-side execution boundary for CAR Review.  A normal CI invocation is
 * deliberately local-only: the Cozy and sbt-cozy evidence providers run in
 * the build workspace, while a CBD HTTP gateway may only be loopback.  A
 * project must state an opt-in setting before a future external, AI, or
 * network provider can cross that boundary.
 */
private[cozy] final case class SbtReviewCiPolicy(
  standardCi: Boolean,
  externalProvidersEnabled: Boolean,
  aiProvidersEnabled: Boolean,
  networkGatewayEnabled: Boolean
) {
  def validateEndpoint(endpoint: String): Either[String, Unit] =
    if (!standardCi || networkGatewayEnabled || SbtReviewCiPolicy.isLoopback(endpoint)) Right(())
    else Left("cbd-review-ci-network-gateway-disabled")

  def providerEnabled(providerKind: String): Boolean = providerKind match {
    case "cozy" | "sbt-cozy" => true
    case "external" => externalProvidersEnabled
    case "ai" => aiProvidersEnabled
    case _ => false
  }

  def validateProviderKinds(providerKinds: Vector[String]): Either[String, Unit] =
    providerKinds.find(kind => !providerEnabled(kind)) match {
      case Some(kind) => Left(s"cbd-review-ci-provider-disabled:$kind")
      case None => Right(())
    }
}

private[cozy] object SbtReviewCiPolicy {
  val LOCAL_DETERMINISTIC_PROVIDER_KINDS: Vector[String] = Vector("cozy", "sbt-cozy")

  def resolve(config: CozyProjectConfig, environment: Map[String, String]): Either[String, SbtReviewCiPolicy] =
    for {
      standardCi <- _standard_ci(config, environment)
      external <- _boolean(config, "review.ci.external_providers_enabled", default = false)
      ai <- _boolean(config, "review.ci.ai_providers_enabled", default = false)
      network <- _boolean(config, "review.ci.network_gateway_enabled", default = false)
    } yield SbtReviewCiPolicy(standardCi, external, ai, network)

  def isLoopback(endpoint: String): Boolean =
    scala.util.Try(new URI(endpoint)).toOption.exists { uri =>
      Set("http", "https").contains(Option(uri.getScheme).getOrElse("").toLowerCase(java.util.Locale.ROOT)) &&
        Set("127.0.0.1", "::1", "localhost").contains(Option(uri.getHost).getOrElse("").toLowerCase(java.util.Locale.ROOT))
    }

  private def _standard_ci(config: CozyProjectConfig, environment: Map[String, String]): Either[String, Boolean] =
    config.value("review.ci.profile").map(_.toLowerCase(java.util.Locale.ROOT)) match {
      case None => Right(environment.get("CI").exists(_.trim.equalsIgnoreCase("true")))
      case Some("standard") => Right(true)
      case Some("development") => Right(false)
      case Some(_) => Left("cbd-review-ci-profile-invalid")
    }

  private def _boolean(config: CozyProjectConfig, key: String, default: Boolean): Either[String, Boolean] =
    config.value(key) match {
      case None => Right(default)
      case Some(_) => config.boolean(key).toRight(s"cbd-review-ci-setting-invalid:$key")
    }
}
