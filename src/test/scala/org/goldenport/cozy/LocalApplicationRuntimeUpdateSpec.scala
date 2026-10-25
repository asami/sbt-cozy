package org.goldenport.cozy

import java.nio.file.Files
import java.util.UUID
import java.util.jar.{JarEntry, JarOutputStream}
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._
import sbt.Keys._

/*
 * @since   Oct. 21, 2026
 * @version Oct. 22, 2026
 */
final class LocalApplicationRuntimeUpdateSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Local application publication" should {
    "registration admission" which {
      "preserves ordinary publication for unregistered and explicitly disabled projects" in {
        Given("a project with no registration and a separately disabled registration")
        val disabled = CozyProjectConfig.parse(Seq("publication:", "  local_application_update:", "    enabled: false"))
        var publications = 0

        When("both ordinary publication routes finish")
        val results = Vector(CozyProjectConfig.empty, disabled).map { config =>
          val prepared = LocalApplicationRuntimeUpdate.prepare(config, file("."), file("target"), file("ordinary.car"),
            "ordinary:car:1-SNAPSHOT", Vector(Attributed.blank(file("unmanaged-missing.jar"))))
          LocalApplicationRuntimeUpdate.publish(prepared, "ordinary:car:1-SNAPSHOT", file("ordinary-destination.car")) {
            publications += 1
          }
        }

        Then("each publisher runs once without any runtime updater")
        publications shouldBe 2
        results.foreach { result =>
          result.succeeded shouldBe true
          result.runtimeupdate.hcursor.get[String]("state").toOption shouldBe Some("NotRegistered")
          result.evidence shouldBe None
        }
      }

      "rejects partial or unsupported bindings before the publisher is admitted" in {
        Given("an empty authored registration group")
        val partial = CozyProjectConfig.parse(Seq("publication:", "  local_application_update:"))

        When("the local publication preflight interprets it")
        val error = intercept[RuntimeException](LocalApplicationRuntimeUpdate.registration(partial, file(".")))

        Then("an incomplete promise cannot become publication-only success")
        error.getMessage should include("enabled is required")
      }

      "rejects foreign producers, arbitrary hooks, unsupported repositories and nonfinite deadlines" in {
        Given("one complete registered producer checkout")
        _with_prepared { (directory, config, _) =>
          val foreign = directory / "other-clone"
          IO.createDirectory(foreign)
          val variants = Vector(
            config.copy(values = config.values.updated(_key("producer"), foreign.getAbsolutePath)),
            config.copy(values = config.values.updated(_key("command"), "arbitrary hook")),
            config.copy(lists = config.lists.updated(_key("repositories"), Seq("https://unapproved.invalid"))),
            config.copy(values = config.values.updated(_key("timeout_seconds"), "Infinity")),
            config.copy(values = config.values.updated(_key("timeout_seconds"), "0"))
          )

          When("each altered registration is preflighted")
          val errors = variants.map(value => intercept[RuntimeException](
            LocalApplicationRuntimeUpdate.registration(value, directory)))

          Then("all unsupported authority or transport inputs are rejected")
          errors.size shouldBe variants.size
          errors.foreach(_.getMessage should include("preflight"))
        }
      }

      "rejects repository subsets and reordered policies before publication" in {
        Given("a complete registration and the fixed ordered repository policy")
        _with_prepared { (directory, config, _) =>
          val approved = LocalApplicationRuntimeUpdate.approvedRepositories
          val policies = Vector(approved.take(2), approved.reverse)
          var publications = 0

          When("publication preparation receives an incomplete or reordered policy")
          val errors = policies.map { repositories =>
            val altered = config.copy(lists = config.lists.updated(_key("repositories"), repositories))
            intercept[RuntimeException] {
              val prepared = LocalApplicationRuntimeUpdate.prepare(altered, directory, directory / "target",
                directory / "actual.car", LocalApplicationRuntimeUpdate.componentCoordinate, _runtime_classpath(directory))
              LocalApplicationRuntimeUpdate.publish(prepared, LocalApplicationRuntimeUpdate.componentCoordinate,
                directory / "published.car") { publications += 1 }
            }
          }

          Then("both policies fail preflight without admitting the publisher")
          errors.foreach(_.getMessage should include("unsupported repository policy"))
          publications shouldBe 0
        }
      }

      "requires explicitly authored repositories and timeout before publication or update" in {
        Given("a complete registration with explicit repositories and timeout")
        _with_prepared { (directory, config, existing) =>
          val variants = Vector(
            config.copy(lists = config.lists - _key("repositories")),
            config.copy(values = config.values - _key("timeout_seconds"))
          )
          _write_result_helper(existing, Json.obj("state" -> Json.fromString("Activated")), 0)
          var publications = 0

          When("publication preparation receives either missing required field")
          val errors = variants.map { altered =>
            intercept[RuntimeException] {
              val prepared = LocalApplicationRuntimeUpdate.prepare(altered, directory, directory / "target",
                directory / "actual.car", LocalApplicationRuntimeUpdate.componentCoordinate, _runtime_classpath(directory))
              LocalApplicationRuntimeUpdate.publish(prepared, LocalApplicationRuntimeUpdate.componentCoordinate,
                directory / "published.car") { publications += 1 }
            }
          }

          Then("both omissions fail preflight without invoking the publisher or updater")
          errors.map(_.getMessage).head should include("missing repositories list")
          errors.map(_.getMessage).last should include("missing timeout_seconds")
          publications shouldBe 0
          (existing.directory / "helper-invocations").exists shouldBe false
        }
      }

      "passes only the closed request and actual task archive to the fixed updater" in {
        Given("a preflighted local application update")
        _with_prepared { (directory, _, prepared) =>
          val request = parse(IO.read(prepared.request)).toOption.get

          When("the program builds the helper transport")
          val command = LocalApplicationRuntimeUpdate.command(prepared)

          Then("the executable receives only update and the concrete request file")
          command shouldBe Vector(prepared.registration.updater.getAbsolutePath, "update", "--request", prepared.request.getAbsolutePath)
          request.hcursor.get[String]("archive").toOption shouldBe Some((directory / "actual.car").getAbsolutePath)
          request.hcursor.get[String]("kind").toOption shouldBe Some(LocalApplicationRuntimeUpdate.REQUEST_KIND)
          request.hcursor.get[String]("result").toOption shouldBe Some(prepared.result.getAbsolutePath)
          request.hcursor.get[String]("component_coordinate").toOption shouldBe Some(LocalApplicationRuntimeUpdate.componentCoordinate)
        }
      }
    }

    "producer-selected runtime closure" which {
      "preserves the producer's ScalaTest 3.2.19 selection, order, classifier and actual files" in {
        Given("a producer Compile graph overriding the CNCF root's transitive ScalaTest 3.2.10 with 3.2.19")
        _with_prepared { (directory, config, _) =>
          val classpath = _runtime_classpath(directory)

          When("the publication owner retains its closed runtime update request")
          val prepared = LocalApplicationRuntimeUpdate.prepare(config, directory, directory / "target",
            directory / "actual.car", LocalApplicationRuntimeUpdate.componentCoordinate, classpath).get
          val request = parse(IO.read(prepared.request)).toOption.get
          val modules = request.hcursor.get[Vector[Json]]("runtime_modules").toOption.get

          Then("the selected metadata and order survive without a separate root resolution or filename inference")
          modules.map(_.hcursor.get[String]("coordinate").toOption.get) shouldBe Vector(
            "org.scalatest:scalatest_3:3.2.19", InstalledRuntimeClasspath.rootCoordinate,
            "fixture:resource:jar:tests:1.0-SNAPSHOT")
          modules.map(_.hcursor.get[String]("source").toOption.get) shouldBe classpath.map(_.data.getCanonicalPath)
          modules.foreach(_.asObject.get.keys.toSet shouldBe Set("coordinate", "source"))
          modules.map(_.hcursor.get[String]("coordinate").toOption.get) should not contain "org.scalatest:scalatest_3:3.2.10"
          LocalApplicationRuntimeUpdate.protocol shouldBe "cncf.local-application-update.v1"
        }
      }

      "accepts producer-selected ScalaTest bundle packaging as an actual JAR without changing its coordinate or order" in {
        Given("a selected ScalaTest 3.2.19 regular JAR reported by SBT with bundle packaging and jar extension")
        _with_prepared { (directory, config, _) =>
          val ordinary = _runtime_classpath(directory)
          val classpath = ordinary.updated(0, ordinary.head.put(artifact.key, Artifact("scalatest_3", "bundle", "jar")))

          When("the publication owner prepares the selected producer closure")
          val prepared = LocalApplicationRuntimeUpdate.prepare(config, directory, directory / "target",
            directory / "actual.car", LocalApplicationRuntimeUpdate.componentCoordinate, classpath).get
          val request = parse(IO.read(prepared.request)).toOption.get
          val modules = request.hcursor.get[Vector[Json]]("runtime_modules").toOption.get

          Then("request preparation succeeds with the same exact selected identities, actual files and order")
          prepared.request.isFile shouldBe true
          request.hcursor.get[String]("kind").toOption shouldBe Some(LocalApplicationRuntimeUpdate.REQUEST_KIND)
          modules.map(_.hcursor.get[String]("coordinate").toOption.get) shouldBe Vector(
            "org.scalatest:scalatest_3:3.2.19", InstalledRuntimeClasspath.rootCoordinate,
            "fixture:resource:jar:tests:1.0-SNAPSHOT")
          modules.map(_.hcursor.get[String]("source").toOption.get) shouldBe classpath.map(_.data.getCanonicalPath)
        }
      }

      "rejects missing root, missing metadata and unsupported JAR entries before publication" in {
        Given("a registered producer with actual attributed JARs and malformed dependency classpaths")
        _with_prepared { (directory, config, _) =>
          val classpath = _runtime_classpath(directory)
          val root = classpath(1)
          val text = directory / "not-a-jar.txt"
          IO.write(text, "unsupported runtime input")
          val corrupt = directory / "corrupt.jar"
          IO.write(corrupt, "interrupted JAR write")
          val variants = Vector(
            Vector.empty[Attributed[File]], classpath.patch(1, Vector.empty, 1),
            Vector(Attributed.blank(root.data)),
            Vector(Attributed.blank(root.data).put(moduleID.key, root.get(moduleID.key).get)),
            classpath.updated(1, root.put(artifact.key, Artifact("goldenport-cncf_3", "pom", "pom"))),
            classpath.updated(1, root.put(artifact.key, Artifact("goldenport-cncf_3", "zip", "jar"))),
            classpath.updated(1, Attributed(directory)(root.metadata)),
            classpath.updated(1, Attributed(text)(root.metadata)),
            classpath.updated(1, Attributed(corrupt)(root.metadata)),
            classpath.updated(1, root.put(artifact.key, Artifact("goldenport-cncf_3", "jar", "jar")
              .withClassifier(Some("tests:unsupported"))))
          )
          var publications = 0

          When("each invalid graph is preflighted by the normal publication owner")
          val errors = variants.map { entries =>
            intercept[RuntimeException] {
              val prepared = LocalApplicationRuntimeUpdate.prepare(config, directory, directory / "target",
                directory / "actual.car", LocalApplicationRuntimeUpdate.componentCoordinate, entries)
              LocalApplicationRuntimeUpdate.publish(prepared, LocalApplicationRuntimeUpdate.componentCoordinate,
                directory / "published.car") { publications += 1 }
            }
          }

          Then("none reaches the publisher and every failure is an explicit preflight rejection")
          errors.foreach(_.getMessage should include("preflight"))
          publications shouldBe 0
        }
      }

      "rejects duplicate coordinates, duplicate sources and conflicting revisions before publication" in {
        Given("one selected graph and alternate associations that would make its runtime ambiguous")
        _with_prepared { (directory, config, _) =>
          val classpath = _runtime_classpath(directory)
          val selected = classpath.head
          val alternate = _jar(directory / "alternate.jar")
          val variants = Vector(
            classpath :+ selected,
            classpath :+ Attributed(alternate)(selected.metadata),
            classpath :+ selected.put(moduleID.key, ModuleID("fixture", "other", "1")),
            classpath :+ Attributed(alternate)(selected.metadata)
              .put(moduleID.key, ModuleID("org.scalatest", "scalatest_3", "3.2.10"))
          )
          var publications = 0

          When("publication preparation admits the module associations")
          val errors = variants.map { entries =>
            intercept[RuntimeException] {
              val prepared = LocalApplicationRuntimeUpdate.prepare(config, directory, directory / "target",
                directory / "actual.car", LocalApplicationRuntimeUpdate.componentCoordinate, entries)
              LocalApplicationRuntimeUpdate.publish(prepared, LocalApplicationRuntimeUpdate.componentCoordinate,
                directory / "published.car") { publications += 1 }
            }
          }

          Then("no duplicate or competing version can be published as a complete registered update")
          errors.map(_.getMessage).take(2).foreach(_ should include("duplicate runtime coordinate"))
          errors(2).getMessage should include("duplicate runtime source")
          errors(3).getMessage should include("conflicting runtime module revision/classifier")
          publications shouldBe 0
        }
      }
    }

    "publication and activation outcomes" which {
      "retains successful publication and one activated continuation in a composite result" in {
        Given("a publisher and a helper acknowledging the current update identifier")
        _with_prepared { (_, _, prepared) =>
          _write_result_helper(prepared, Json.obj("state" -> Json.fromString("Activated"),
            "update_id" -> Json.fromString(prepared.updateid), "installation_id" -> Json.fromString(UUID.randomUUID.toString)), 0)
          var publications = 0

          When("the one publication owner completes its pipeline")
          val result = LocalApplicationRuntimeUpdate.publish(Some(prepared), LocalApplicationRuntimeUpdate.componentCoordinate,
            prepared.directory / "published.car") { publications += 1 }
          val stored = parse(IO.read(prepared.composite)).toOption.get

          Then("success requires the completed delegate and activated update")
          result.succeeded shouldBe true
          publications shouldBe 1
          stored.hcursor.get[String]("kind").toOption shouldBe Some("cncf.local-application-publish.v1")
          stored.hcursor.downField("publication").get[String]("state").toOption shouldBe Some("Completed")
          stored.hcursor.downField("runtime_update").get[String]("state").toOption shouldBe Some("Activated")
          IO.readLines(prepared.directory / "helper-invocations").size shouldBe 1
          CozyPlugin.publishTaskLabel("car", local = true) shouldBe "cozyPublishLocalCar"
        }
      }

      "never activates or replays a publisher which may already have written" in {
        Given("a publisher whose delegate fails and a helper which would otherwise activate")
        _with_prepared { (_, _, prepared) =>
          _write_result_helper(prepared, Json.obj("state" -> Json.fromString("Activated")), 0)
          var publications = 0

          When("the actual publisher throws after admission")
          val result = LocalApplicationRuntimeUpdate.publish(Some(prepared), LocalApplicationRuntimeUpdate.componentCoordinate,
            prepared.directory / "published.car") { publications += 1; sys.error("publisher failed after possible write") }

          Then("the retained result reports possible publication effects and no attempted runtime update")
          publications shouldBe 1
          result.succeeded shouldBe false
          result.publication.hcursor.get[String]("state").toOption shouldBe Some("FailedMayHaveWritten")
          result.runtimeupdate.hcursor.get[String]("state").toOption shouldBe Some("NotAttempted")
          (prepared.directory / "helper-invocations").exists shouldBe false
          prepared.composite.isFile shouldBe true
        }
      }

      "returns a nonzero task outcome after completed publication and failed staging without republishing" in {
        Given("a helper retaining the prior selection after an admission failure")
        _with_prepared { (_, _, prepared) =>
          val selection = Json.obj("installation_id" -> Json.fromString(UUID.randomUUID.toString))
          IO.write(prepared.registration.root / "selected", selection.noSpaces)
          _write_result_helper(prepared, Json.obj("state" -> Json.fromString("FailedBeforeActivation"),
            "stage" -> Json.fromString("admission"), "reason" -> Json.fromString("incompatible runtime"),
            "retained_selection" -> selection), 7)
          var publications = 0

          When("publication succeeds but its required update fails")
          val result = LocalApplicationRuntimeUpdate.publish(Some(prepared), LocalApplicationRuntimeUpdate.componentCoordinate,
            prepared.directory / "published.car") { publications += 1 }
          val error = intercept[RuntimeException](LocalApplicationRuntimeUpdate.requireSuccess(result))

          Then("completed publication and failed update remain distinct with no implicit retry")
          result.publication.hcursor.get[String]("state").toOption shouldBe Some("Completed")
          result.runtimeupdate.hcursor.get[String]("state").toOption shouldBe Some("FailedBeforeActivation")
          error.getMessage should include("publication=Completed runtime update=FailedBeforeActivation")
          error.getMessage should include(prepared.composite.getAbsolutePath)
          IO.read(prepared.registration.root / "selected") shouldBe selection.noSpaces
          publications shouldBe 1
          IO.readLines(prepared.directory / "helper-invocations").size shouldBe 1
        }
      }
    }

    "uncertain helper completion" which {
      "adds a fixed supervision allowance without changing the updater work budget" in {
        Given("a prepared request retaining a finite updater work budget")
        _with_prepared { (_, _, prepared) =>
          val method = LocalApplicationRuntimeUpdate.getClass.getDeclaredMethod("_watchdog_timeout_millis", java.lang.Double.TYPE)
          method.setAccessible(true)

          When("the bridge derives its watchdog from the registered budget")
          val watchdog = method.invoke(LocalApplicationRuntimeUpdate, Double.box(prepared.registration.timeoutseconds)).asInstanceOf[Long]
          val request = parse(IO.read(prepared.request)).toOption.get

          Then("only the bridge adds ten seconds for supervision, cleanup and retaining the result")
          watchdog shouldBe 130000L
          request.hcursor.get[Double]("timeout_seconds").toOption shouldBe Some(120.0)
          method.invoke(LocalApplicationRuntimeUpdate, Double.box(0.1)).asInstanceOf[Long] shouldBe 10100L
        }
      }

      "retains each known preactivation failure written by its owned helper on TERM" in {
        Given("an updater that handles TERM by retaining the unchanged selection and exiting nonzero")
        _with_prepared { (_, _, prepared) =>
          val selection = Json.obj("installation_id" -> Json.fromString(UUID.randomUUID.toString))
          IO.write(prepared.registration.root / "selected", selection.noSpaces)
          val stages = Vector("prepare", "materialize", "archive-admission", "bootstrap-admission", "install")

          When("each owned helper exceeds the watchdog and gracefully acknowledges the failure")
          val results = stages.map { stage =>
            val failure = Json.obj("state" -> Json.fromString("FailedBeforeActivation"),
              "stage" -> Json.fromString(stage), "reason" -> Json.fromString("work deadline expired"),
              "retained_selection" -> selection)
            _write_term_helper(prepared, Some(failure.noSpaces), 7)
            _run_with_watchdog(prepared)
          }

          Then("confirmed termination preserves the actual typed failure at every admitted stage")
          results.map(_.hcursor.get[String]("state").toOption) shouldBe Vector.fill(stages.size)(Some("FailedBeforeActivation"))
          results.map(_.hcursor.get[String]("stage").toOption.get) shouldBe stages
          results.foreach(_.hcursor.get[Json]("retained_selection").toOption shouldBe Some(selection))
          IO.read(prepared.registration.root / "selected") shouldBe selection.noSpaces
          IO.readLines(prepared.directory / "helper-invocations").size shouldBe stages.size
        }
      }

      "never accepts activation forged by a helper after its watchdog expired" in {
        Given("an owned helper that writes an otherwise bound Activated result when terminated")
        _with_prepared { (_, _, prepared) =>
          val activated = Json.obj("state" -> Json.fromString("Activated"),
            "update_id" -> Json.fromString(prepared.updateid), "installation_id" -> Json.fromString(UUID.randomUUID.toString))
          _write_term_helper(prepared, Some(activated.noSpaces), 0)

          When("the watchdog terminates that helper and reads its acknowledgement")
          val result = _run_with_watchdog(prepared)

          Then("even a successful helper exit cannot establish timed-out activation")
          result.hcursor.get[String]("state").toOption shouldBe Some("Indeterminate")
          result.hcursor.get[String]("stage").toOption shouldBe Some("helper-timeout")
        }
      }

      "keeps missing, invalid, mismatched and non-preactivation timeout outcomes unknown" in {
        Given("an unchanged selected installation and acknowledgements that cannot prove preactivation failure")
        _with_prepared { (_, _, prepared) =>
          val selection = Json.obj("installation_id" -> Json.fromString(UUID.randomUUID.toString))
          IO.write(prepared.registration.root / "selected", selection.noSpaces)
          val failure = Json.obj("state" -> Json.fromString("FailedBeforeActivation"),
            "stage" -> Json.fromString("bootstrap-admission"), "reason" -> Json.fromString("deadline expired"),
            "retained_selection" -> selection)
          val payloads = Vector(None, Some("invalid JSON"), Some("{}"),
            Some(failure.mapObject(_.add("retained_selection", Json.Null)).noSpaces)) ++
            Vector("activate", "result", "helper-result", "unknown", "admission").map { stage =>
              Some(failure.mapObject(_.add("stage", Json.fromString(stage))).noSpaces)
            }

          When("the watchdog observes a graceful nonzero exit for each unusable acknowledgement")
          val results = payloads.map { payload =>
            _write_term_helper(prepared, payload, 7)
            _run_with_watchdog(prepared)
          }

          Then("every outcome remains helper-timeout Indeterminate without accepting unsupported stages")
          results.foreach { result =>
            result.hcursor.get[String]("state").toOption shouldBe Some("Indeterminate")
            result.hcursor.get[String]("stage").toOption shouldBe Some("helper-timeout")
          }
        }
      }

      "retains a valid Indeterminate acknowledgement after confirmed graceful termination" in {
        Given("an owned helper that explicitly cannot determine its activation state")
        _with_prepared { (_, _, prepared) =>
          val unknown = Json.obj("state" -> Json.fromString("Indeterminate"),
            "stage" -> Json.fromString("activate"), "reason" -> Json.fromString("activation acknowledgement lost"))
          _write_term_helper(prepared, Some(unknown.noSpaces), 7)

          When("the helper retains its typed uncertainty while handling TERM")
          val result = _run_with_watchdog(prepared)

          Then("the actual helper outcome remains available")
          result shouldBe unknown
        }
      }

      "cannot confirm a retained selection when the installed selection is unreadable as JSON" in {
        Given("an invalid installed selection and a helper claiming no previous installation")
        _with_prepared { (_, _, prepared) =>
          IO.write(prepared.registration.root / "selected", "invalid JSON")
          val failure = Json.obj("state" -> Json.fromString("FailedBeforeActivation"),
            "stage" -> Json.fromString("bootstrap-admission"), "reason" -> Json.fromString("deadline expired"),
            "retained_selection" -> Json.Null)
          _write_term_helper(prepared, Some(failure.noSpaces), 7)

          When("the watchdog receives that claim after graceful termination")
          val result = _run_with_watchdog(prepared)

          Then("invalid selection data cannot be treated as a confirmed absent installation")
          result.hcursor.get[String]("state").toOption shouldBe Some("Indeterminate")
          result.hcursor.get[String]("stage").toOption shouldBe Some("helper-timeout")
        }
      }

      "records forced cleanup as uncertain even when a failure payload already exists" in {
        Given("an owned helper that ignores TERM after writing a preactivation failure")
        _with_prepared { (_, _, prepared) =>
          val failure = Json.obj("state" -> Json.fromString("FailedBeforeActivation"),
            "stage" -> Json.fromString("bootstrap-admission"), "reason" -> Json.fromString("deadline expired"),
            "retained_selection" -> Json.Null)
          IO.write(prepared.registration.updater,
            "#!/bin/sh\ntrap '' TERM\n" + s"printf '%s' '${failure.noSpaces}' > '${prepared.result.getAbsolutePath}'\n" +
              "while :; do :; done\n")

          When("the watchdog must force its noncooperating helper to terminate")
          val result = _run_with_watchdog(prepared)

          Then("a retained payload cannot turn forced cleanup into confirmed graceful failure")
          result.hcursor.get[String]("stage").toOption shouldBe Some("helper-timeout")
          result.hcursor.get[String]("reason").toOption.get should include("force_used=true")
          result.hcursor.get[String]("reason").toOption.get should include("direct_reaped=true")
        }
      }

      "preserves interruption after bounded owned-helper cleanup" in {
        Given("an interrupted caller and an owned helper awaiting termination")
        _with_prepared { (_, _, prepared) =>
          _write_term_helper(prepared, None, 7)

          When("the caller waits for the helper with its interrupt flag already set")
          val (result, interrupted) = try {
            Thread.currentThread.interrupt()
            val result = _run_with_watchdog(prepared)
            result -> Thread.currentThread.isInterrupted
          } finally Thread.interrupted()

          Then("cleanup returns typed uncertainty and restores the caller's interruption")
          interrupted shouldBe true
          result.hcursor.get[String]("state").toOption shouldBe Some("Indeterminate")
          result.hcursor.get[String]("stage").toOption shouldBe Some("helper-interrupted")
          result.hcursor.get[String]("reason").toOption.get should include("direct_reaped=true")
        }
      }

      "reports lost acknowledgement as indeterminate after the updater has started" in {
        Given("an updater which exits without a retained result")
        _with_prepared { (_, _, prepared) =>
          IO.write(prepared.registration.updater, "#!/bin/sh\nexit 0\n")

          When("the helper returns without its typed acknowledgement")
          val result = LocalApplicationRuntimeUpdate.publish(Some(prepared), LocalApplicationRuntimeUpdate.componentCoordinate,
            prepared.directory / "published.car") { () }

          Then("the completed publisher is preserved while activation cannot be claimed")
          result.succeeded shouldBe false
          result.publication.hcursor.get[String]("state").toOption shouldBe Some("Completed")
          result.runtimeupdate.hcursor.get[String]("state").toOption shouldBe Some("Indeterminate")
        }
      }

      "rejects an activated result belonging to another update or contradicting the process exit" in {
        Given("two invalid acknowledgements for a prepared update")
        _with_prepared { (_, _, prepared) =>
          val foreign = Json.obj("state" -> Json.fromString("Activated"),
            "update_id" -> Json.fromString(UUID.randomUUID.toString), "installation_id" -> Json.fromString(UUID.randomUUID.toString))
          IO.write(prepared.result, foreign.noSpaces)

          When("the typed result is correlated with its process outcome")
          val mismatched = LocalApplicationRuntimeUpdate.readResult(prepared, 0)
          IO.write(prepared.result, foreign.mapObject(_.add("update_id", Json.fromString(prepared.updateid))).noSpaces)
          val contradicted = LocalApplicationRuntimeUpdate.readResult(prepared, 9)

          Then("neither result establishes activation")
          mismatched.hcursor.get[String]("state").toOption shouldBe Some("Indeterminate")
          contradicted.hcursor.get[String]("state").toOption shouldBe Some("Indeterminate")
        }
      }

      "terminates its own overdue helper and retains an indeterminate update without retry" in {
        Given("a helper held beyond a short finite registered deadline")
        _with_prepared { (_, _, original) =>
          val prepared = original.copy(registration = original.registration.copy(timeoutseconds = 0.1))
          IO.write(prepared.registration.updater, "#!/bin/sh\nsleep 20\n")

          When("the publication continuation reaches its owned deadline")
          val result = LocalApplicationRuntimeUpdate.publish(Some(prepared), LocalApplicationRuntimeUpdate.componentCoordinate,
            prepared.directory / "published.car") { () }

          Then("publication is complete and the timed-out update remains uncertain")
          result.succeeded shouldBe false
          result.runtimeupdate.hcursor.get[String]("state").toOption shouldBe Some("Indeterminate")
          result.runtimeupdate.hcursor.get[String]("stage").toOption shouldBe Some("helper-timeout")
          prepared.composite.isFile shouldBe true
        }
      }
    }
  }

  private def _key(field: String): String = "publication.local_application_update." + field

  // Exercise the actual owned-process path with a short watchdog while the
  // production entry point retains the fixed ten-second supervision allowance.
  private def _run_with_watchdog(prepared: LocalApplicationRuntimeUpdate.Prepared): Json = {
    val method = LocalApplicationRuntimeUpdate.getClass.getDeclaredMethod("_run",
      classOf[LocalApplicationRuntimeUpdate.Prepared], java.lang.Long.TYPE)
    method.setAccessible(true)
    method.invoke(LocalApplicationRuntimeUpdate, prepared, Long.box(300L)).asInstanceOf[Json]
  }

  private def _write_term_helper(prepared: LocalApplicationRuntimeUpdate.Prepared, payload: Option[String], exitcode: Int): Unit = {
    IO.delete(prepared.result)
    val write = payload.map(value => s"printf '%s' '$value' > '${prepared.result.getAbsolutePath}'; ").getOrElse("")
    val handler = write + s"exit $exitcode"
    val quotedhandler = "'" + handler.replace("'", "'\\''") + "'"
    IO.write(prepared.registration.updater,
      "#!/bin/sh\n" + s"trap $quotedhandler TERM\n" +
        s"echo invoked >> '${(prepared.directory / "helper-invocations").getAbsolutePath}'\n" +
        "while :; do :; done\n")
  }

  private def _jar(filename: File): File = {
    if (!filename.exists()) {
      val output = new JarOutputStream(Files.newOutputStream(filename.toPath))
      try {
        output.putNextEntry(new JarEntry("marker"))
        output.write("actual selected runtime fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        output.closeEntry()
      } finally output.close()
    }
    filename.getCanonicalFile
  }

  private def _runtime_classpath(directory: File): Vector[Attributed[File]] = Vector(
    ("org.scalatest", "scalatest_3", "3.2.19", None),
    ("org.goldenport", "goldenport-cncf_3", "0.5.3-SNAPSHOT", None),
    ("fixture", "resource", "1.0-SNAPSHOT", Some("tests"))
  ).map { case (organization, name, revision, classifier) =>
    Attributed.blank(_jar(directory / (name + ".jar")))
      .put(moduleID.key, ModuleID(organization, name, revision))
      .put(artifact.key, Artifact(name, "jar", "jar").withClassifier(classifier))
  }

  private def _write_result_helper(prepared: LocalApplicationRuntimeUpdate.Prepared, result: Json, exitcode: Int): Unit = {
    val invocation = (prepared.directory / "helper-invocations").getAbsolutePath
    val filename = prepared.result.getAbsolutePath
    IO.write(prepared.registration.updater,
      "#!/bin/sh\n" + s"echo invoked >> '$invocation'\n" + s"printf '%s' '${result.noSpaces}' > '$filename'\nexit $exitcode\n")
  }

  private def _with_prepared(body: (File, CozyProjectConfig, LocalApplicationRuntimeUpdate.Prepared) => Unit): Unit = {
    val parent = file("target/sbt-cozy-test/work/local-application-update")
    IO.createDirectory(parent)
    val directory = Files.createTempDirectory(parent.toPath, "publication-").toFile.getCanonicalFile
    try {
      val root = directory / "installations"
      val host = directory / "host"
      IO.createDirectory(root)
      IO.createDirectory(host)
      val updater = root / "updater"
      IO.write(updater, "#!/bin/sh\nexit 0\n")
      updater.setExecutable(true, true) shouldBe true
      IO.write(directory / "actual.car", "actual invocation CAR")
      val config = CozyProjectConfig(Map(
        _key("enabled") -> "true", _key("application") -> "sm-workflow",
        _key("producer") -> directory.getAbsolutePath, _key("root") -> root.getAbsolutePath,
        _key("updater") -> updater.getAbsolutePath, _key("protocol") -> LocalApplicationRuntimeUpdate.protocol,
        _key("root_coordinate") -> InstalledRuntimeClasspath.rootCoordinate,
        _key("admission_host") -> host.getAbsolutePath,
        _key("timeout_seconds") -> "120"
      ), Map(_key("repositories") -> LocalApplicationRuntimeUpdate.approvedRepositories))
      val prepared = LocalApplicationRuntimeUpdate.prepare(config, directory, directory / "target", directory / "actual.car",
        LocalApplicationRuntimeUpdate.componentCoordinate, _runtime_classpath(directory)).get
      body(directory, config, prepared)
    } finally IO.delete(directory)
  }
}
