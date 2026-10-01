package org.goldenport.cozy

import java.net.URLClassLoader
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._

final class CozyRuntimeAbiResourcesSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private def fixture(test: (File, File, File) => Unit): Unit = {
    val root = Files.createTempDirectory("cozy-runtime-abi-").toFile
    try test(root / "delegate-work", root / "target", root / "resources")
    finally IO.delete(root)
  }
  private def emit(work: File, name: String, bytes: Array[Byte], run: String = "run-0"): File = {
    val file = work / run / "target" / "cozy" / name
    IO.createDirectory(file.getParentFile)
    IO.write(file, bytes)
    file
  }

  "Official runtime ABI handoff" should {
    "recover exact interrupted duplicate-root output without removing edited files" in fixture { (work, target, _) =>
      Given("an interrupted generation left a duplicate Scala-root source without a manifest")
      val source = work / "Generated.scala"
      val managed = target / "src_managed" / "main" / "scala"
      val relative = "scala/demo/Generated.scala"
      val obsolete = managed / relative
      IO.write(source, "package demo\nobject Generated\n")
      IO.write(obsolete, IO.read(source))
      When("the corrected generator installs the same official bytes")
      val destination = CozyDelegatedGenerator.installGeneratedSource(source, managed, relative, Some("demo/Generated.scala"))
      Then("only the exact obsolete output is removed and the correct source is retained")
      obsolete.exists() shouldBe false
      IO.readBytes(destination).toVector shouldBe IO.readBytes(source).toVector
      When("a conflicting edit exists at the obsolete output path")
      IO.write(obsolete, "user edit\n")
      Then("installation fails and preserves the conflicting file")
      intercept[RuntimeException](CozyDelegatedGenerator.installGeneratedSource(source, managed, relative, None))
      IO.read(obsolete) shouldBe "user edit\n"
      IO.readBytes(destination).toVector shouldBe IO.readBytes(source).toVector
    }

    "avoid duplicating a configured Scala root while preserving canonical main-root output" in fixture { (_, target, _) =>
      Given("Cozy output relative to src_managed/main and both supported managed roots")
      val main = target / "src_managed" / "main"
      When("the delegated source destination is resolved")
      val canonical = CozyDelegatedGenerator.generatedDestination(main, "scala/org/example/Value.scala")
      val configured = CozyDelegatedGenerator.generatedDestination(main / "scala", "scala/org/example/Value.scala")
      val domain = CozyDelegatedGenerator.generatedDestination(main / "scala", "domain/Workflow.scala")
      Then("existing Scala roots are not repeated and domain output remains colocated")
      canonical shouldBe (main / "scala" / "org" / "example" / "Value.scala")
      configured shouldBe canonical
      domain shouldBe (main / "scala" / "domain" / "Workflow.scala")
      CozyDelegatedGenerator.generatedDestination(main, "org/example/Value.scala") shouldBe
        (main / "org" / "example" / "Value.scala")
    }

    "retain exact bytes and load the packaged resources without test classpath fallback" in fixture { (work, target, resources) =>
      Given("official generator sidecars with significant UTF-8 whitespace")
      val bytes = "{\n  \"schema\": \"正式ABI\"\n}\n".getBytes("UTF-8")
      CozyRuntimeAbiResources.filenames.foreach(emit(work, _, bytes))
      When("the handoff installs and projects the sidecars, and the work directory is removed")
      CozyRuntimeAbiResources.install(work, target)
      IO.delete(work)
      val projected = CozyRuntimeAbiResources.project(target, resources)
      val jar = target / "main-only.jar"
      val output = new ZipOutputStream(Files.newOutputStream(jar.toPath))
      try projected.foreach { file =>
        output.putNextEntry(new ZipEntry(IO.relativize(resources, file).get.replace('\\', '/')))
        output.write(IO.readBytes(file))
        output.closeEntry()
      } finally output.close()
      Then("an isolated main-JAR classloader returns the producer bytes verbatim")
      val loader = new URLClassLoader(Array(jar.toURI.toURL), null)
      try CozyRuntimeAbiResources.filenames.foreach { name =>
        IO.readBytes(target / "cozy" / name).toVector shouldBe bytes.toVector
        val input = loader.getResourceAsStream(s"META-INF/cozy/$name")
        input should not be null
        try input.readAllBytes().toVector shouldBe bytes.toVector finally input.close()
      } finally loader.close()
    }

    "invalidate missing or changed staged output and repair missing projected resources" in fixture { (work, target, resources) =>
      Given("a valid installed generation and its projected resources")
      val name = CozyRuntimeAbiResources.filenames.head
      emit(work, name, Array[Byte](1, 2, 3))
      CozyRuntimeAbiResources.install(work, target)
      val projected = CozyRuntimeAbiResources.project(target, resources)
      CozyRuntimeAbiResources.isAvailable(target) shouldBe true
      When("a projected resource is deleted")
      IO.delete(projected)
      Then("projection restores the exact resource from the same generation")
      CozyRuntimeAbiResources.project(target, resources).map(IO.readBytes(_).toVector) shouldBe Seq(Vector[Byte](1, 2, 3))
      When("the installed producer output is changed or deleted")
      IO.write(target / "cozy" / name, Array[Byte](4))
      Then("generation cache admission rejects both cases")
      CozyRuntimeAbiResources.isAvailable(target) shouldBe false
      intercept[RuntimeException](CozyRuntimeAbiResources.project(target, resources))
      IO.delete(target / "cozy" / name)
      CozyRuntimeAbiResources.isAvailable(target) shouldBe false
      CozyRuntimeAbiResources.install(work, target)
      CozyRuntimeAbiResources.isAvailable(target) shouldBe true
    }

    "remove stale sidecars and projections when declarations or all sources disappear" in fixture { (work, target, resources) =>
      Given("a generation containing both official sidecars")
      CozyRuntimeAbiResources.filenames.foreach(emit(work, _, Array[Byte](7)))
      CozyRuntimeAbiResources.install(work, target)
      CozyRuntimeAbiResources.project(target, resources).size shouldBe 2
      When("the next official generation no longer contains a Workflow")
      IO.delete(work)
      CozyRuntimeAbiResources.install(work, target)
      Then("the old resources disappear and an empty generation is explicitly valid")
      CozyRuntimeAbiResources.project(target, resources) shouldBe empty
      CozyRuntimeAbiResources.isAvailable(target) shouldBe true
      CozyRuntimeAbiResources.filenames.foreach { name =>
        (target / "cozy" / name).exists() shouldBe false
        (resources / "META-INF" / "cozy" / name).exists() shouldBe false
      }
      When("all CML sources disappear or the backend changes to legacy")
      CozyRuntimeAbiResources.clear(target)
      Then("the empty generation remains packageable without stale output")
      CozyRuntimeAbiResources.project(target, resources) shouldBe empty
    }

    "reject ambiguous sources before modifying installed output and ignore unofficial paths" in fixture { (work, target, resources) =>
      Given("one previously installed generation")
      val name = CozyRuntimeAbiResources.filenames.head
      emit(work, name, Array[Byte](9))
      CozyRuntimeAbiResources.install(work, target)
      When("two delegated sources produce the same official sidecar")
      emit(work, name, Array[Byte](10), "run-1")
      Then("the handoff rejects the ambiguity even if callers might choose a last result")
      intercept[RuntimeException](CozyRuntimeAbiResources.install(work, target)).getMessage should include("multiple official runtime ABI")
      IO.readBytes(target / "cozy" / name).toVector shouldBe Vector[Byte](9)
      When("only a coincidentally named unofficial file remains")
      IO.delete(work)
      IO.write(work / name, Array[Byte](11))
      CozyRuntimeAbiResources.install(work, target)
      Then("it is not promoted to a runtime ABI")
      CozyRuntimeAbiResources.project(target, resources) shouldBe empty
    }

    "require a handoff epoch even when no sidecar was generated" in fixture { (_, target, _) =>
      Given("a pre-handoff build cache without its runtime ABI manifest")
      When("cache side-output admission runs")
      Then("the old cache cannot suppress generation")
      CozyRuntimeAbiResources.isAvailable(target) shouldBe false
    }
  }
}
