package org.goldenport.cozy

import java.nio.file.Files

import org.goldenport.cozy.CarCoordinateContractScenarioReport.{Agreement, NamespaceIsolated, Rejected}
import org.goldenport.cozy.CarCoordinateContractScenarioRequest.{CanonicalAgreement, MetadataAdmission, NamespaceRetention}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._

/*
 * @since   Aug.  7, 2026
 * @version Aug. 11, 2026
 * @author  ASAMI, Tomoharu
 */
final class Phase56CarCoordinateContractSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private val _e8 = afterWord(
    "in spec:phase-56-car-coordinate-contract, example:E8, rules:CID01-R1,CID01-R2,CID01-R3, phase:56, slice:CID-01D"
  )
  private val _e8_cid03c = afterWord(
    "in spec:phase-56-car-coordinate-contract, example:E8, rules:CID01-R1,CID01-R2,CID01-R3, phase:56, slice:CID-03C"
  )
  private val _e9 = afterWord(
    "in spec:phase-56-car-coordinate-contract, example:E9, rules:CID01-R4,CID01-R5,CID01-R6, phase:56, slice:CID-04D"
  )
  private val _e10 = afterWord(
    "in spec:phase-56-car-coordinate-contract, example:E10, rules:CID01-R7,CID01-R8,CID01-R9, phase:56, slice:CID-01D"
  )
  private val _e10_cid03c = afterWord(
    "in spec:phase-56-car-coordinate-contract, example:E10, rules:CID01-R7,CID01-R8,CID01-R9, phase:56, slice:CID-03C"
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

    "E8 project canonical CAR prerequisites through the shared identity ABI" must _e8 {
      "when one canonical identity is projected for CAR packaging" in {
        Given(
          "the exact E8 rules CID01-R1, CID01-R2, CID01-R3 and canonical UserAccount identity from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )

        When("the canonical identity is projected through CarComponentIdentityAdapter")
        val observed = CarComponentIdentityAdapter.projectRelease(
          _namespace,
          _local_id,
          "3",
          _version
        ).map { projection =>
          Map(
            "qualified" -> projection.identity.qualifiedId(),
            "mavenGroup" -> projection.identity.mavenGroupId(),
            "artifact" -> projection.identity.mavenArtifactId(),
            "path" -> projection.identity.pathSegment(),
            "jvmPackage" -> projection.identity.jvmPackage(),
            "generatedClass" -> projection.identity.generatedClassName(),
            "carFilename" -> projection.carFilename,
            "mavenCoordinate" -> projection.mavenCoordinate
          )
        }

        Then("the shared ABI supplies every canonical CAR prerequisite")
        observed shouldBe Right(
          Map(
            "qualified" -> _qualified_identity,
            "mavenGroup" -> _maven_group,
            "artifact" -> _artifact,
            "path" -> "user-account",
            "jvmPackage" -> _jvm_package,
            "generatedClass" -> _generated_class,
            "carFilename" -> _car_filename,
            "mavenCoordinate" -> s"${_maven_group}:${_scala3_artifact}:${_version}"
          )
        )
      }
    }

    "E8 retain exact shared safe error codes through the adapter" must _e8 {
      "when canonical identity, release, and Scala suffix inputs are invalid" in {
        Given(
          "the exact E8 rules CID01-R1, CID01-R2, CID01-R3 and invalid canonical input boundaries from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )

        When("the invalid inputs are projected through CarComponentIdentityAdapter")
        val invalidnamespace = CarComponentIdentityAdapter.project(
          "org..textus",
          _local_id
        ).left.map(_.code())
        val invalidlocalid = CarComponentIdentityAdapter.project(
          _namespace,
          "user-account"
        ).left.map(_.code())
        val invalidrelease = CarComponentIdentityAdapter.projectRelease(
          _namespace,
          _local_id,
          "3",
          "invalid release"
        ).left.map(_.code())
        val invalidscalasuffix = CarComponentIdentityAdapter.projectRelease(
          _namespace,
          _local_id,
          "3_2",
          _version
        ).left.map(_.code())

        Then("the exact shared error codes are retained")
        invalidnamespace shouldBe Left("component.identity.namespace.segment-format")
        invalidlocalid shouldBe Left("component.identity.local-id.format")
        invalidrelease shouldBe Left("component.identity.release.format")
        invalidscalasuffix shouldBe Left("component.identity.scala-suffix.format")
      }
    }

    "E8 admit one canonical agreement through the production scenario" must _e8_cid03c {
      "when a canonical identity request is evaluated" in {
        Given(
          "the exact E8 rules CID01-R1, CID01-R2, CID01-R3 and example org.simplemodeling.textus.UserAccount from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )
        val scenarioid = "e8-canonical-agreement"
        val request = CanonicalAgreement(scenarioid, _namespace, _local_id, _version)

        When("the canonical agreement request is sent to the production scenario SPI")
        val report = CarCoordinateContractScenarioSpi.evaluate(request)

        Then("the target report is one exact agreement across every frozen projection")
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

    "E9 retain two-argument construction while rejecting namespace-free resolution" must _e9 {
      "when a legacy dependency is submitted to the resolver" in {
        Given(
          "the exact E9 rules CID01-R4, CID01-R5, CID01-R6 and example shared:0.6.0-SNAPSHOT from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )
        val legacy = CarDependency("Shared", _version)

        When("the retained construction reaches a namespace-sensitive authority")
        val error = intercept[RuntimeException] {
          CarDependencyResolver.resolve(legacy, Seq.empty, file("target/sbt-cozy-test/work/phase56-car-coordinate/legacy-cache"))
        }

        Then("construction compatibility remains while resolution rejects before lookup")
        legacy.localId shouldBe "Shared"
        error.getMessage shouldBe "[sbt-cozy] component.identity.namespace.required"
      }
    }

    "E9 retain namespace keys while allowing one shared human filename" must _e9 {
      "when the two fixed namespace-retention coordinates are submitted" in {
        Given(
          "the exact E9 rules CID01-R4, CID01-R5, CID01-R6 and fixed org.alpha.textus/org.beta.textus Shared coordinates"
        )
        val scenarioid = "e9-namespace-retention"

        When("the namespace-retention request is sent to the production scenario SPI")
        val report = CarCoordinateContractScenarioSpi.evaluate(
          NamespaceRetention(
            scenarioid,
            "org.alpha.textus",
            "org.beta.textus",
            "Shared",
            _version
          )
        )

        Then("the report retains exact independent keys and paths with one filename")
        report shouldBe NamespaceIsolated(
          scenarioid,
          Vector(s"org.alpha.textus.Shared:${_version}", s"org.beta.textus.Shared:${_version}"),
          Vector(
            s"org/alpha/textus/textus-shared/${_version}/textus-shared-${_version}.car",
            s"org/beta/textus/textus-shared/${_version}/textus-shared-${_version}.car"
          ),
          Vector(
            s"org/alpha/textus/textus-shared/${_version}/textus-shared-${_version}.car",
            s"org/beta/textus/textus-shared/${_version}/textus-shared-${_version}.car"
          ),
          Vector(s"textus-shared-${_version}.car")
        )
      }
    }

    "E9 validate scoped collisions through the shared identity ABI" must _e9 {
      "when colliding and namespace-isolated HTTP gateway identities are projected" in {
        Given(
          "the exact E9 rules CID01-R4, CID01-R5, CID01-R6 and same-local-ID namespace isolation from /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md"
        )

        When("the identity adapter validates colliding and namespace-isolated identities")
        val collision = CarComponentIdentityAdapter.validateNoScopedCollisions(
          Vector(
            _namespace -> "HTTPGateway",
            _namespace -> "HttpGateway"
          )
        ).left.map(_.code())
        val admitted = CarComponentIdentityAdapter.validateNoScopedCollisions(
          Vector(
            "org.simplemodeling.textus" -> "HTTPGateway",
            "org.other.textus" -> "HTTPGateway"
          )
        ).map(_.map(_.qualifiedId()))
        val filenames = Vector(
          CarComponentIdentityAdapter.projectRelease(
            "org.simplemodeling.textus",
            "HTTPGateway",
            "3",
            _version
          ).map(_.carFilename),
          CarComponentIdentityAdapter.projectRelease(
            "org.other.textus",
            "HTTPGateway",
            "3",
            _version
          ).map(_.carFilename)
        )

        Then("the shared ABI rejects scoped collisions and preserves two qualified identities")
        collision shouldBe Left("component.identity.projection.collision")
        admitted shouldBe Right(
          Vector(
            "org.other.textus.HTTPGateway",
            "org.simplemodeling.textus.HTTPGateway"
          )
        )
        filenames shouldBe Vector(
          Right(s"textus-http-gateway-${_version}.car"),
          Right(s"textus-http-gateway-${_version}.car")
        )
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

    "E10 admit canonical metadata and reject conflicting compatibility metadata" must _e10_cid03c {
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
            reason should include ("CAR_COMPONENT_IDENTITY_DISAGREEMENT")
            reason should include ("project.organization expected=org.simplemodeling.textus actual=org.textus")
          case other =>
            fail(s"Expected conflicting metadata rejection, got $other")
        }
      }
    }

    "CID-03C admit authored canonical metadata through one evidence contract" must _e10_cid03c {
      "when complete, partial, blank, null, invalid, and legacy project metadata are inspected" in {
        Given("Spec: /Users/asami/src/dev2025/cloud-native-component-framework/docs/notes/phase-56-cid01-component-identity-inventory-and-failing-first-contract.md; Rules: CID01-R7,CID01-R8,CID01-R9; Example: E10; authored project.yaml canonical and legacy metadata")
        val canonical = CozyProjectConfig.parse(Seq(
          "project:",
          s"  namespace: ${_namespace}",
          s"  id: ${_local_id}",
          s"  name: ${_artifact}",
          s"  organization: ${_maven_group}",
          s"  scalaPackage: ${_jvm_package}",
          "  component:",
          s"    name: ${_local_id}",
          s"    className: ${_generated_class}",
          s"    version: ${_version}",
          "  identity:",
          s"    qualified: ${_qualified_identity}",
          s"    organization: ${_maven_group}",
          s"    artifact: ${_artifact}",
          s"    jvmPackage: ${_jvm_package}",
          s"    generatedClass: ${_generated_class}",
          "    path: user-account",
          "packaging:",
          "  car:",
          "    manifest_metadata:",
          s"      component: ${_qualified_identity}",
          s"      namespace: ${_namespace}",
          s"      id: ${_local_id}",
          s"      qualifiedIdentity: ${_qualified_identity}",
          s"      organization: ${_maven_group}",
          s"      artifact: ${_artifact}",
          s"      jvmPackage: ${_jvm_package}",
          s"      generatedClass: ${_generated_class}",
          "      path: user-account",
          "      boundedContext: identity",
          "      domain: user-account"
        ))
        val directvalues = Map("project.namespace" -> _namespace)
        val directlists = Map("project.tags" -> Seq("identity"))
        val direct = CozyProjectConfig(directvalues, directlists)
        val constructed = new CozyProjectConfig(directvalues, directlists)
        val copied = direct.copy(lists = Map("project.tags" -> Seq("identity", "canonical")))
        val extracted = direct match {
          case CozyProjectConfig(values, lists) => values -> lists
        }
        val directproduct: Product = direct
        val parsedblank = CozyProjectConfig.parse(Seq(
          "project:",
          "  namespace:",
          s"  id: ${_local_id}",
          "  component:",
          "    version: ~"
        ))
        val missing = Vector(
          CozyProjectConfig.parse(Seq("project:", "  namespace:", s"  id: ${_local_id}", s"  component:", s"    version: ${_version}")),
          CozyProjectConfig.parse(Seq("project:", "  namespace: ~", s"  id: ${_local_id}", s"  component:", s"    version: ${_version}")),
          CozyProjectConfig.parse(Seq("project:", s"  namespace: ${_namespace}", s"  component:", s"    version: ${_version}")),
          CozyProjectConfig.parse(Seq("project:", "  component:", "    version: null"))
        )
        val legacyversions = Vector(
          CozyProjectConfig.parse(Seq("project:", "  component:", "    version:")),
          missing.last
        )
        val invalidnamespace = CozyProjectConfig.parse(Seq("project:", "  namespace: org..textus", s"  id: ${_local_id}", s"  component:", s"    version: ${_version}"))
        val invalidlocalid = CozyProjectConfig.parse(Seq("project:", s"  namespace: ${_namespace}", "  id: user-account", s"  component:", s"    version: ${_version}"))
        val legacy = CozyProjectConfig.parse(Seq("project:", "  name: legacy-component", "  organization: org.legacy", "  component:", "    name: Legacy", s"    version: ${_version}"))
        val expectedcompatibilityvalues = Map(
          "project.name" -> _artifact,
          "project.organization" -> _maven_group,
          "project.scalaPackage" -> _jvm_package,
          "project.component.name" -> _local_id,
          "project.component.className" -> _generated_class,
          "project.identity.qualified" -> _qualified_identity,
          "project.identity.organization" -> _maven_group,
          "project.identity.artifact" -> _artifact,
          "project.identity.jvmPackage" -> _jvm_package,
          "project.identity.generatedClass" -> _generated_class,
          "project.identity.path" -> "user-account",
          "packaging.car.manifest_metadata.component" -> _qualified_identity,
          "packaging.car.manifest_metadata.namespace" -> _namespace,
          "packaging.car.manifest_metadata.id" -> _local_id,
          "packaging.car.manifest_metadata.qualifiedIdentity" -> _qualified_identity,
          "packaging.car.manifest_metadata.organization" -> _maven_group,
          "packaging.car.manifest_metadata.artifact" -> _artifact,
          "packaging.car.manifest_metadata.jvmPackage" -> _jvm_package,
          "packaging.car.manifest_metadata.generatedClass" -> _generated_class,
          "packaging.car.manifest_metadata.path" -> "user-account"
        )
        val expectedmanifestmetadata = Map(
          "component" -> _qualified_identity,
          "namespace" -> _namespace,
          "id" -> _local_id,
          "qualifiedIdentity" -> _qualified_identity,
          "organization" -> _maven_group,
          "artifact" -> _artifact,
          "jvmPackage" -> _jvm_package,
          "generatedClass" -> _generated_class,
          "path" -> "user-account",
          "boundedContext" -> "identity",
          "domain" -> "user-account"
        )
        val allconflicts = Vector(
          "project.name" -> "wrong-project-name",
          "project.organization" -> "wrong-project-organization",
          "project.scalaPackage" -> "wrong-project-scala-package",
          "project.package" -> "wrong-project-package",
          "project.component.name" -> "wrong-project-component-name",
          "project.component.className" -> "wrong-project-component-class-name",
          "project.identity.qualified" -> "wrong-project-identity-qualified",
          "project.identity.organization" -> "wrong-project-identity-organization",
          "project.identity.artifact" -> "wrong-project-identity-artifact",
          "project.identity.jvmPackage" -> "wrong-project-identity-jvm-package",
          "project.identity.generatedClass" -> "wrong-project-identity-generated-class",
          "project.identity.path" -> "wrong-project-identity-path",
          "packaging.car.manifest_metadata.component" -> "wrong-manifest-component",
          "packaging.car.manifest_metadata.namespace" -> "wrong-manifest-namespace",
          "packaging.car.manifest_metadata.id" -> "wrong-manifest-id",
          "packaging.car.manifest_metadata.qualifiedIdentity" -> "wrong-manifest-qualified-identity",
          "packaging.car.manifest_metadata.organization" -> "wrong-manifest-organization",
          "packaging.car.manifest_metadata.artifact" -> "wrong-manifest-artifact",
          "packaging.car.manifest_metadata.jvmPackage" -> "wrong-manifest-jvm-package",
          "packaging.car.manifest_metadata.generatedClass" -> "wrong-manifest-generated-class",
          "packaging.car.manifest_metadata.path" -> "wrong-manifest-path"
        )
        val expectedallconflicts = allconflicts.map { case (field, actual) =>
          val expected = field match {
            case "project.name" | "project.identity.artifact" | "packaging.car.manifest_metadata.artifact" => _artifact
            case "project.organization" | "project.identity.organization" | "packaging.car.manifest_metadata.organization" => _maven_group
            case "project.scalaPackage" | "project.package" | "project.identity.jvmPackage" | "packaging.car.manifest_metadata.jvmPackage" => _jvm_package
            case "project.component.name" | "packaging.car.manifest_metadata.id" => _local_id
            case "project.component.className" | "project.identity.generatedClass" | "packaging.car.manifest_metadata.generatedClass" => _generated_class
            case "project.identity.qualified" | "packaging.car.manifest_metadata.component" | "packaging.car.manifest_metadata.qualifiedIdentity" => _qualified_identity
            case "project.identity.path" | "packaging.car.manifest_metadata.path" => "user-account"
            case "packaging.car.manifest_metadata.namespace" => _namespace
          }
          s"$field expected=$expected actual=$actual"
        }.sorted

        When("the production admission contract inspects every metadata shape")
        val evidence = CozyProjectIdentityContract.inspect(canonical, "3")
        val missingevidence = missing.map(CozyProjectIdentityContract.inspect(_, "3"))
        val legacyversionsevidence = legacyversions.map(CozyProjectIdentityContract.inspect(_, "3"))
        val legacyversionsadmission = legacyversions.map(CozyProjectIdentityContract.admit(_, "3"))
        val invalidnamespaceevidence = CozyProjectIdentityContract.inspect(invalidnamespace, "3")
        val invalidlocalidevidence = CozyProjectIdentityContract.inspect(invalidlocalid, "3")
        val legacyevidence = CozyProjectIdentityContract.inspect(legacy, "3")
        val allconflictsconfig = CozyProjectConfig(canonical.values ++ allconflicts, canonical.lists)
        val allconflictsevidence = CozyProjectIdentityContract.inspect(allconflictsconfig, "3")
        val allconflictsadmission = CozyProjectIdentityContract.admit(allconflictsconfig, "3")
        val canonicaldescriptor = CozyManifestMetadata.from(
          evidence.manifestMetadata,
          evidence.moduleName.getOrElse(""),
          evidence.effectiveVersion
        ).extensions.get("componentDescriptorJson")
        val allconflictsdescriptor = CozyManifestMetadata.from(
          allconflictsevidence.manifestMetadata,
          allconflictsevidence.moduleName.getOrElse(""),
          allconflictsevidence.effectiveVersion
        ).extensions.get("componentDescriptorJson")

        Then("the public two-field case-class ABI and parsed canonical absence state remain exact")
        direct shouldBe constructed
        direct shouldBe CozyProjectConfig(directvalues, directlists)
        extracted shouldBe (directvalues -> directlists)
        copied shouldBe CozyProjectConfig(directvalues, Map("project.tags" -> Seq("identity", "canonical")))
        direct.hashCode shouldBe constructed.hashCode
        direct.toString should startWith ("CozyProjectConfig(")
        direct.toString should include ("project.namespace")
        direct.toString should include ("project.tags")
        directproduct.productArity shouldBe 2
        directproduct.productElement(0) shouldBe directvalues
        directproduct.productElement(1) shouldBe directlists
        parsedblank shouldBe CozyProjectConfig(parsedblank.values, parsedblank.lists)
        parsedblank.values shouldBe Map(
          "project.namespace" -> "",
          "project.id" -> _local_id,
          "project.component.version" -> ""
        )
        parsedblank.isAuthored("project.namespace") shouldBe true
        parsedblank.isAuthored("project.component.version") shouldBe true
        parsedblank.value("project.namespace") shouldBe None
        parsedblank.value("project.component.version") shouldBe None
        parsedblank.productArity shouldBe 2

        And("canonical evidence exposes exact build, manifest, and descriptor projections")
        evidence.shape shouldBe "canonical"
        evidence.schemaVersion shouldBe CozyProjectIdentityContract.SCHEMA_VERSION
        evidence.organization shouldBe Some(_maven_group)
        evidence.moduleName shouldBe Some(_artifact)
        evidence.effectiveVersion shouldBe _version
        evidence.carBaseName shouldBe Some(s"${_artifact}-${_version}")
        evidence.carFilename shouldBe Some(_car_filename)
        evidence.mavenCoordinate shouldBe Some(s"${_maven_group}:${_scala3_artifact}:${_version}")
        evidence.canonicalQualifiedId shouldBe Some(_qualified_identity)
        evidence.jvmPackage shouldBe Some(_jvm_package)
        evidence.generatedClass shouldBe Some(_generated_class)
        evidence.path shouldBe Some("user-account")
        evidence.authoredCompatibilityValues shouldBe expectedcompatibilityvalues
        evidence.materializedValues shouldBe expectedcompatibilityvalues
        evidence.manifestMetadata shouldBe expectedmanifestmetadata
        canonicaldescriptor shouldBe None
        evidence.diagnosticCodes shouldBe Vector.empty

        And("authored blanks, YAML nulls, and partial canonical keys are never legacy fallbacks")
        missingevidence.map(_.shape) shouldBe Vector("canonical", "canonical", "partial", "legacy")
        missingevidence.take(3).map(_.diagnosticCodes.nonEmpty) shouldBe Vector(true, true, true)
        missing.last.values shouldBe Map("project.component.version" -> "")
        missing.last.isAuthored("project.component.version") shouldBe true
        missing.last.value("project.component.version") shouldBe None
        missingevidence.last.effectiveVersion shouldBe ""
        missingevidence.last.canonicalNamespace shouldBe None
        missingevidence.last.expectedDerivedValues shouldBe Map.empty
        missingevidence.last.diagnosticCodes shouldBe Vector.empty
        legacyversions.map(_.values) shouldBe Vector.fill(2)(Map("project.component.version" -> ""))
        legacyversionsevidence.map(_.shape) shouldBe Vector("legacy", "legacy")
        legacyversionsevidence.map(_.effectiveVersion) shouldBe Vector("", "")
        legacyversionsevidence.map(_.canonicalNamespace) shouldBe Vector(None, None)
        legacyversionsevidence.map(_.expectedDerivedValues) shouldBe Vector(Map.empty, Map.empty)
        legacyversionsadmission shouldBe legacyversionsevidence.map(Right(_))
        invalidnamespaceevidence.diagnosticCodes shouldBe Vector("component.identity.namespace.segment-format")
        invalidlocalidevidence.diagnosticCodes shouldBe Vector("component.identity.local-id.format")

        And("all authored conflicts retain the exact sorted disagreement evidence and canonical override direction")
        allconflictsevidence.disagreements shouldBe expectedallconflicts
        allconflictsevidence.diagnosticCodes shouldBe Vector(CozyProjectIdentityContract.DISAGREEMENT_CODE)
        allconflictsadmission shouldBe Left(
          (Vector(CozyProjectIdentityContract.DISAGREEMENT_CODE) ++ expectedallconflicts).mkString("; ")
        )
        allconflictsevidence.manifestMetadata shouldBe expectedmanifestmetadata
        allconflictsdescriptor shouldBe None

        And("legacy evidence retains authored values and version without inventing a canonical namespace")
        legacyevidence.shape shouldBe "legacy"
        legacyevidence.effectiveVersion shouldBe _version
        legacyevidence.canonicalNamespace shouldBe None
        legacyevidence.expectedDerivedValues shouldBe Map.empty
        legacyevidence.authoredCompatibilityValues should contain ("project.name" -> "legacy-component")
        legacyevidence.diagnosticCodes shouldBe Vector.empty
      }
    }
  }

  private def _retains_each_namespace_once(
    keys: Vector[String],
    namespaces: Vector[String]
  ): Boolean = {
    val matches = keys.map { key =>
      namespaces.filter(key.contains)
    }
    keys.size == namespaces.size &&
      matches.forall(_.size == 1) &&
      matches.flatten.size == namespaces.size &&
      matches.flatten.toSet == namespaces.toSet
  }

  private def _with_temp_dir(prefix: String)(f: File => Any): Any = {
    val parent = file("target/sbt-cozy-test/work/phase56-car-coordinate")
    IO.createDirectory(parent)
    val directory = Files.createTempDirectory(parent.toPath, prefix).toFile
    try f(directory)
    finally IO.delete(directory)
  }

  private def _write(path: File, content: String): File = {
    IO.createDirectory(path.getParentFile)
    IO.write(path, content)
    path
  }
}
