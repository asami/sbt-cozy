package org.goldenport.cozy

import java.nio.file.Files

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._

/*
 * @since   Jul. 12, 2026
 * @version Jul. 12, 2026
 * @author  ASAMI, Tomoharu
 */
final class CarDependencyResolverSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "CAR dependency resolver" should {
    "resolve an exact CAR coordinate from an ordered local repository" in {
      Given("a local CAR repository containing the requested coordinate")
      _with_temp_dir("sbt-cozy-car-resolver") { dir =>
        val repository = dir / "repository" / "car"
        val archive = _write(repository / "provider" / "0.1.0-SNAPSHOT" / "provider-0.1.0-SNAPSHOT.car", "car")

        When("the dependency is resolved")
        val resolved = CarDependencyResolver.resolve(
          CarDependency("provider", "0.1.0-SNAPSHOT"),
          Seq(repository.getAbsolutePath),
          dir / "cache"
        )

        Then("the exact local artifact is returned")
        resolved.getCanonicalFile shouldBe archive.getCanonicalFile
      }
    }

    "fail deterministically when a SNAPSHOT is absent from local repositories" in {
      Given("an empty local repository and a remote fallback")
      _with_temp_dir("sbt-cozy-car-missing") { dir =>
        When("a missing SNAPSHOT dependency is resolved")
        val error = intercept[RuntimeException] {
          CarDependencyResolver.resolve(
            CarDependency("provider", "0.1.0-SNAPSHOT"),
            Seq((dir / "repository" / "car").getAbsolutePath, "https://example.invalid/repository/car"),
            dir / "cache"
          )
        }

        Then("the error identifies the exact unresolved coordinate")
        error.getMessage should include("CAR dependency not found: provider:0.1.0-SNAPSHOT")
      }
    }
  }

  private def _with_temp_dir[A](prefix: String)(f: File => A): A = {
    val dir = Files.createTempDirectory(prefix).toFile
    try f(dir)
    finally IO.delete(dir)
  }

  private def _write(path: File, content: String): File = {
    IO.createDirectory(path.getParentFile)
    IO.write(path, content)
    path
  }
}
