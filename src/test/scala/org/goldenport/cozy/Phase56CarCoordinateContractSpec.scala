package org.goldenport.cozy

import java.nio.file.Files

import org.goldenport.cozy.CarCoordinateContractScenarioReport.{Agreement, NamespaceIsolated, Rejected}
import org.goldenport.cozy.CarCoordinateContractScenarioRequest.{CanonicalAgreement, MetadataAdmission, NamespaceRetention}
import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._

/*
 * @since   Aug.  7, 2026
 * @version Aug.  7, 2026
 * @author  ASAMI, Tomoharu
 */
final class Phase56CarCoordinateContractSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private val _e8 = afterWord(
    "in spec:phase-56-car-coordinate-contract, example:E8, rules:CID01-R1,CID01-R2,CID01-R3, phase:56, slice:CID-01D"
  )
  private val _e9 = afterWord(
    "in spec:phase-56-car-coordinate-contract, example:E9, rules:CID01-R4,CID01-R5,CID01-R6, phase:56, slice:CID-01D"
  )
  private val _e10 = afterWord(
    "in spec:phase-56-car-coordinate-contract, example:E10, rules:CID01-R7,CID01-R8,CID01-R9, phase:56, slice:CID-01D"
  )

  private val _namespace = "org.simplemodeling.textus"
  private val _local_id = "UserAccount"
  private val _version = "0.6.0-SNAPSHOT"
  private val _qualified_identity = "org.simplemodeling.textus.UserAccount"
  private val _maven_group = "org.simplemodeling.textus"
  private val _artifact = "textus-user-account"
  private val _scala3_artifact = "textus-user-account_3"
  private val _car_filename = "textus-user-account-0.6.0-SNAPSHOT.car"
  private val _jvm_package = "org.simplemodeling.textus.useraccount"
  private val _generated_class = "UserAccountComponent"

  "Phase 56 CAR coordinate contract" should {
    "E8 preserve the current legacy CAR metadata agreement" must _e8 {
      "when one real project mapping is parsed and packaged" in {
        Given(
          "the exact E8 rules CID01-R1, CID01-R2, CID01-R3 and example E8 from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )
        val config = CozyProjectConfig.parse(
          Seq(
            "project:",
            "  name: textus-user-account",
            "  kind: car",
            "  organization: org.textus",
            "  scalaPackage: org.simplemodeling.textus.useraccount",
            "  component:",
            "    name: UserAccount",
            "    className: UserAccount",
            "    version: 0.6.0-SNAPSHOT",
            "packaging:",
            "  kind: car",
            "  car:",
            "    manifest_metadata:",
            "      boundedContext: identity",
            "      domain: user-account"
          )
        )

        When("CozyPackagingPolicy.resolve and the CAR-build manifest metadata input are observed")
        val policy = CozyPackagingPolicy.resolve(config)
        val projectname = config.value("project.name").get
        val version = config.value("project.component.version").get
        val manifestmetadata = config.mapUnder("packaging.car.manifest_metadata") ++
          Map("component" -> config.value("project.component.name").get)
        val packagemetadata = CozyManifestMetadata.from(manifestmetadata, projectname, version)
        val descriptor = packagemetadata.extensions("componentDescriptorJson")

        Then("the legacy project, packaging, manifest, and descriptor values agree as currently implemented")
        policy.packaging shouldBe "car"
        config.value("project.organization") shouldBe Some("org.textus")
        config.value("project.scalaPackage") shouldBe Some("org.simplemodeling.textus.useraccount")
        config.value("project.component.className") shouldBe Some("UserAccount")
        config.value("project.component.version") shouldBe Some(_version)
        packagemetadata.component shouldBe "UserAccount"
        descriptor should include ("\"name\":\"textus-user-account\"")
        descriptor should include ("\"version\":\"0.6.0-SNAPSHOT\"")
        descriptor should include ("\"component\":\"UserAccount\"")
      }
    }

    "E8 admit one canonical agreement through the deferred target scenario" must _e8 {
      "when a canonical identity request is evaluated" in {
        Given(
          "the exact E8 rules CID01-R1, CID01-R2, CID01-R3 and example org.simplemodeling.textus.UserAccount from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )
        val scenarioid = "e8-canonical-agreement"
        val request = CanonicalAgreement(scenarioid, _namespace, _local_id, _version)

        When("the canonical agreement request is sent to the production scenario SPI")
        val report = CarCoordinateContractScenarioSpi.evaluate(request)

        Then("the target report is one exact agreement across every frozen projection")
        pendingUntilFixed {
          report shouldBe Agreement(
            scenarioid,
            _maven_group,
            _artifact,
            _version,
            _scala3_artifact,
            _car_filename,
            _qualified_identity,
            _qualified_identity,
            _jvm_package,
            _generated_class
          )
        }
      }
    }

    "E9 preserve the current namespace-free dependency and repository collision" must _e9 {
      "when two legacy dependencies resolve from one repository" in {
        Given(
          "the exact E9 rules CID01-R4, CID01-R5, CID01-R6 and example shared:0.6.0-SNAPSHOT from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )
        _with_temp_dir("phase56-car-coordinate") { directory =>
          val repository = directory / "repository" / "car"
          val archive = _write(
            repository / "shared" / _version / s"shared-${_version}.car",
            "car"
          )
          val firstdependency = CarDependency("shared", _version)
          val seconddependency = CarDependency("shared", _version)

          When("the existing CarDependency and local repository resolver are observed")
          val first = CarDependencyResolver.resolve(
            firstdependency,
            Seq(repository.getAbsolutePath),
            directory / "cache"
          )
          val second = CarDependencyResolver.resolve(
            seconddependency,
            Seq(repository.getAbsolutePath),
            directory / "cache"
          )

          Then("the legacy dependency keys and repository destination collapse to one file")
          firstdependency shouldBe seconddependency
          first.getCanonicalFile shouldBe archive.getCanonicalFile
          second.getCanonicalFile shouldBe archive.getCanonicalFile
          Vector(first, second).map(_.getCanonicalPath).distinct should have size 1
        }
      }
    }

    "E9 retain namespace keys while allowing one shared human filename" must _e9 {
      "when valid distinct lowercase multi-segment namespaces are submitted" in {
        Given(
          "the exact E9 rules CID01-R4, CID01-R5, CID01-R6 and examples org.simplemodeling.textus.shared, org.other.textus.shared, and one shared CAR filename from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )
        val namespacegenerator = for {
          firstsegment <- Gen.nonEmptyListOf(Gen.alphaLowerChar).map(_.mkString)
          secondsegment <- Gen.nonEmptyListOf(Gen.alphaLowerChar).map(_.mkString)
        } yield (s"org.$firstsegment", s"net.$secondsegment")
        val scenarioid = "e9-namespace-retention"
        val property = Prop.forAll(namespacegenerator) { case (firstnamespace, secondnamespace) =>
          val report = CarCoordinateContractScenarioSpi.evaluate(
            NamespaceRetention(
              scenarioid,
              firstnamespace,
              secondnamespace,
              "Shared",
              _version
            )
          )
          report match {
            case NamespaceIsolated(reportid, dependencykeys, repositorykeys, filenames) =>
              reportid == scenarioid &&
                dependencykeys.distinct.size == 2 &&
                repositorykeys.distinct.size == 2 &&
                filenames == Vector(s"shared-${_version}.car")
            case _ =>
              false
          }
        }

        When("each generated namespace-retention request is sent to the production scenario SPI")

        Then("the target report retains two namespace-bearing keys and exactly one shared filename")
        pendingUntilFixed {
          Test.check(
            Test.Parameters.default.withMinSuccessfulTests(50),
            property
          ).passed shouldBe true
        }
      }
    }

    "E10 preserve the current manifest and descriptor agreement" must _e10 {
      "when actual project and packaging metadata paths are parsed" in {
        Given(
          "the exact E10 rules CID01-R7, CID01-R8, CID01-R9 and example project.component metadata from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )
        val config = CozyProjectConfig.parse(
          Seq(
            "project:",
            "  name: textus-user-account",
            "  kind: car",
            "  organization: org.textus",
            "  scalaPackage: org.simplemodeling.textus.useraccount",
            "  component:",
            "    name: UserAccount",
            "    className: UserAccount",
            "    version: 0.6.0-SNAPSHOT",
            "packaging:",
            "  kind: car",
            "  car:",
            "    manifest_metadata:",
            "      boundedContext: identity",
            "      domain: user-account"
          )
        )
        val projectname = config.value("project.name").get
        val version = config.value("project.component.version").get
        val manifestmetadata = config.mapUnder("packaging.car.manifest_metadata") ++
          Map("component" -> config.value("project.component.name").get)

        When("CozyManifestMetadata.from is called with the real project name and component version")
        val packagemetadata = CozyManifestMetadata.from(manifestmetadata, projectname, version)
        val descriptor = packagemetadata.extensions("componentDescriptorJson")

        Then("the legacy manifest component and descriptor fields agree truthfully")
        config.value("project.name") shouldBe Some("textus-user-account")
        config.value("project.component.version") shouldBe Some(_version)
        packagemetadata.component shouldBe "UserAccount"
        descriptor should include ("\"name\":\"textus-user-account\"")
        descriptor should include ("\"version\":\"0.6.0-SNAPSHOT\"")
        descriptor should include ("\"component\":\"UserAccount\"")
      }
    }

    "E10 admit canonical metadata and reject conflicting compatibility metadata" must _e10 {
      "when canonical-only and conflicting metadata requests are evaluated" in {
        Given(
          "the exact E10 rules CID01-R7, CID01-R8, CID01-R9 and example canonical UserAccount plus org.textus compatibility metadata from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )
        val canonicalid = "e10-canonical-admission"
        val conflictingid = "e10-conflicting-compatibility"
        val canonicalrequest = MetadataAdmission(
          canonicalid,
          _namespace,
          _local_id,
          _version,
          Map.empty
        )
        val conflictingrequest = MetadataAdmission(
          conflictingid,
          _namespace,
          _local_id,
          _version,
          Map(
            "organization" -> "org.textus",
            "artifact" -> "textus-user-account",
            "scalaPackage" -> "org.simplemodeling.textus.useraccount",
            "component" -> "UserAccount",
            "className" -> "UserAccount"
          )
        )

        When("both metadata admission requests are sent to the production scenario SPI")
        val reports = Vector(
          CarCoordinateContractScenarioSpi.evaluate(canonicalrequest),
          CarCoordinateContractScenarioSpi.evaluate(conflictingrequest)
        )

        Then("canonical metadata agrees across qualified components while conflicting compatibility metadata rejects")
        pendingUntilFixed {
          reports.head shouldBe Agreement(
            canonicalid,
            _maven_group,
            _artifact,
            _version,
            _scala3_artifact,
            _car_filename,
            _qualified_identity,
            _qualified_identity,
            _jvm_package,
            _generated_class
          )
          reports(1) match {
            case Rejected(reportid, reason) =>
              reportid shouldBe conflictingid
              reason should not be empty
            case other =>
              fail(s"Expected conflicting metadata rejection, got $other")
          }
        }
      }
    }
  }

  private def _with_temp_dir(prefix: String)(f: File => Any): Any = {
    val directory = Files.createTempDirectory(prefix).toFile
    try f(directory)
    finally IO.delete(directory)
  }

  private def _write(path: File, content: String): File = {
    IO.createDirectory(path.getParentFile)
    IO.write(path, content)
    path
  }
}
