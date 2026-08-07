package org.goldenport.cozy

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._

/*
 * @since   Aug.  7, 2026
 * @version Aug.  7, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyCarPublicationCoordinateSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "CAR publication coordinate projection" should {
    "derive archive and warehouse destination from the admitted shared release coordinate" in {
      Given("canonical project metadata with compatibility publication names omitted")
      val metadata = CozyProjectConfig.parse(Seq(
        "project:",
        "  namespace: org.alpha.textus",
        "  id: Shared",
        "  component:",
        "    version: 0.6.0-SNAPSHOT"
      ))
      val target = file("target/sbt-cozy-test/work/cozy-car-publication/publication")
      val warehouse = file("target/sbt-cozy-test/work/cozy-car-publication/warehouse")

      When("the pure CAR publication projection is evaluated")
      val projection = CarPublicationCoordinate._project(metadata, "3", target, warehouse)

      Then("archive, transport artifact, release, and result destination are canonical")
      projection.release._maven_release_key shouldBe "org.alpha.textus:textus-shared:0.6.0-SNAPSHOT"
      projection.archive.getPath.replace('\\', '/') should endWith("target/sbt-cozy-test/work/cozy-car-publication/publication/textus-shared-0.6.0-SNAPSHOT.car")
      projection.destination.getPath.replace('\\', '/') should endWith(
        "target/sbt-cozy-test/work/cozy-car-publication/warehouse/repository/car/org/alpha/textus/textus-shared/0.6.0-SNAPSHOT/textus-shared-0.6.0-SNAPSHOT.car"
      )
    }

    "retain standard and local CAR version diagnostics for projected coordinates" in {
      Given("project metadata for a SNAPSHOT standard publication and a release local publication")
      val snapshotmetadata = CozyProjectConfig.parse(Seq(
        "project:",
        "  namespace: org.alpha.textus",
        "  id: Shared",
        "  component:",
        "    version: 0.6.0-SNAPSHOT"
      ))
      val releasemetadata = CozyProjectConfig.parse(Seq(
        "project:",
        "  namespace: org.alpha.textus",
        "  id: Shared",
        "  component:",
        "    version: 0.6.0"
      ))

      When("the admitted publication releases are checked by their respective CAR publication guards")
      val snapshot = CarPublicationCoordinate._project(snapshotmetadata, "3", file("target/sbt-cozy-test/work/cozy-car-publication/snapshot"), file("target/sbt-cozy-test/work/cozy-car-publication/warehouse"))
      val release = CarPublicationCoordinate._project(releasemetadata, "3", file("target/sbt-cozy-test/work/cozy-car-publication/release"), file("target/sbt-cozy-test/work/cozy-car-publication/local-warehouse"))
      val standarderror = intercept[RuntimeException] {
        CozyPlugin.validatePublishVersion(snapshot.release._release, "cozyPublishCar", expectsnapshot = false)
      }
      val localerror = intercept[RuntimeException] {
        CozyPlugin.validatePublishVersion(release.release._release, "cozyPublishLocalCar", expectsnapshot = true)
      }

      Then("the standard and local publication guards retain their stable diagnostics")
      standarderror.getMessage shouldBe "[sbt-cozy] cozyPublishCar rejects SNAPSHOT version '0.6.0-SNAPSHOT'; use cozyPublishLocalCar/cozyPublishLocalSar during SNAPSHOT development"
      localerror.getMessage shouldBe "[sbt-cozy] cozyPublishLocalCar requires a SNAPSHOT version; release version '0.6.0' must use cozyPublishCar/cozyPublishSar"
    }

    "preserve SAR publication task routing" in {
      Given("the existing SAR packaging kind")

      When("standard publication labels are selected")
      val release = CozyPlugin.publishTaskLabel("sar", local = false)
      val local = CozyPlugin.publishTaskLabel("sar", local = true)

      Then("CAR coordinate projection has not replaced SAR publication semantics")
      release shouldBe "cozyPublishSar"
      local shouldBe "cozyPublishLocalSar"
    }
  }
}
