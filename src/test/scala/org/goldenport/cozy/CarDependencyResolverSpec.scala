package org.goldenport.cozy

import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt._

/*
 * @since   Jul. 12, 2026
 * @version Aug. 13, 2026
 * @author  ASAMI, Tomoharu
 */
final class CarDependencyResolverSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private val _namespace = "org.alpha.textus"
  private val _dependency = CarDependency(_namespace, "Shared", "0.6.0")
  private val _path = "org/alpha/textus/textus-shared/0.6.0/textus-shared-0.6.0.car"

  "CAR dependency resolver" should {
    "admit canonical dependencies" which {
      "resolve canonical local and file repositories in configured order" in {
        Given("a local first repository and an observable HTTP later candidate")
        _with_temp_dir("sbt-cozy-car-resolver") { directory =>
          val first = _write(directory / "first" / _path, "first")
          val lateraccesses = new AtomicInteger(0)
          val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
          server.createContext("/car", new HttpHandler {
            override def handle(exchange: HttpExchange): Unit = {
              lateraccesses.incrementAndGet()
              exchange.sendResponseHeaders(500, -1)
              exchange.close()
            }
          })
          server.start()

          try {
            When("the canonical dependency is resolved from either local root form")
            val local = CarDependencyResolver.resolve(
              _dependency,
              Seq((directory / "first").getAbsolutePath, s"http://127.0.0.1:${server.getAddress.getPort}/car"),
              directory / "cache-local"
            )
            val fromfile = CarDependencyResolver.resolve(
              _dependency,
              Seq((directory / "first").toURI.toString),
              directory / "cache-file"
            )

            Then("the first canonical repository path is selected without accessing later candidates")
            local.getCanonicalFile shouldBe first.getCanonicalFile
            fromfile.getCanonicalFile shouldBe first.getCanonicalFile
            lateraccesses.get() shouldBe 0
          } finally {
            server.stop(0)
          }
        }
      }

      "reject invalid canonical namespaces before repository access" in {
        Given("canonical dependencies with raw invalid namespace values")
        val invalid = Vector(
          CarDependency(" org.alpha.textus ", "Shared", "0.6.0") -> "component.identity.namespace.segment-format",
          CarDependency("", "Shared", "0.6.0") -> "component.identity.namespace.segment-count",
          CarDependency(null, "Shared", "0.6.0") -> "component.identity.namespace.required"
        )

        When("repository resolution is requested with a repository value invalid for I/O")
        val errors = invalid.map { case (dependency, code) =>
          val error = intercept[RuntimeException] {
            CarDependencyResolver.resolve(dependency, Seq(null), file("target/sbt-cozy-test/work/car-dependency-resolver/invalid-namespace-cache"))
          }
          code -> error.getMessage
        }

        Then("raw namespace admission returns exact shared codes before repository access")
        errors.foreach { case (code, message) =>
          message shouldBe s"[sbt-cozy] ${code}"
        }
      }
    }

    "preserve deterministic structural values" which {
      "preserve namespace-aware structural values without Product compatibility" in {
        Given("equal and namespace-distinct canonical dependencies")
        val first = CarDependency("org.alpha.textus", "Shared", "0.6.0")
        val equal = CarDependency("org.alpha.textus", "Shared", "0.6.0")
        val othernamespace = CarDependency("org.beta.textus", "Shared", "0.6.0")

        When("their structural value behavior is observed")
        val rendered = first.toString

        Then("namespace, local ID, and version determine equality and rendering")
        first.namespace shouldBe "org.alpha.textus"
        first shouldBe equal
        first.hashCode shouldBe equal.hashCode
        first should not be othernamespace
        rendered should include("namespace=org.alpha.textus")
        rendered should include("localId=Shared")
        rendered should include("version=0.6.0")
      }
    }

    "manage HTTP cache lifecycle" which {
      "use a coordinate-qualified HTTP cache without contacting a stopped source" in {
        Given("a loopback release repository for two same-filename namespace coordinates")
        _with_temp_dir("sbt-cozy-car-http") { directory =>
          val requests = new ConcurrentLinkedQueue[String]()
          val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
          server.createContext("/car", new HttpHandler {
            override def handle(exchange: HttpExchange): Unit = {
              val requestpath = exchange.getRequestURI.getPath
              requests.add(requestpath)
              val bytes = requestpath.getBytes("UTF-8")
              exchange.sendResponseHeaders(200, bytes.length)
              val output = exchange.getResponseBody
              try output.write(bytes)
              finally output.close()
            }
          })
          server.start()
          val repository = s"http://127.0.0.1:${server.getAddress.getPort}/car"
          val second = CarDependency("org.beta.textus", "Shared", "0.6.0")
          try {
            When("both coordinates are downloaded and the source is then stopped")
            val firstarchive = CarDependencyResolver.resolve(_dependency, Seq(repository), directory / "cache")
            val secondarchive = CarDependencyResolver.resolve(second, Seq(repository), directory / "cache")
            server.stop(0)
            val cached = CarDependencyResolver.resolve(_dependency, Seq(repository), directory / "cache")

            Then("same human filenames remain namespace-isolated and cache reuse is offline")
            firstarchive.getName shouldBe secondarchive.getName
            firstarchive.getCanonicalPath should not be secondarchive.getCanonicalPath
            IO.read(firstarchive) should include("/org/alpha/textus/")
            IO.read(secondarchive) should include("/org/beta/textus/")
            cached.getCanonicalFile shouldBe firstarchive.getCanonicalFile
            requests.toArray.toVector.map(_.toString) shouldBe Vector(
              "/car/org/alpha/textus/textus-shared/0.6.0/textus-shared-0.6.0.car",
              "/car/org/beta/textus/textus-shared/0.6.0/textus-shared-0.6.0.car"
            )
          } finally {
            server.stop(0)
          }
        }
      }

      "clean a sibling temporary cache file after a failed HTTP download" in {
        Given("a loopback repository that rejects the canonical release path")
        _with_temp_dir("sbt-cozy-car-http-failure") { directory =>
          val requests = new ConcurrentLinkedQueue[String]()
          val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
          server.createContext("/car", new HttpHandler {
            override def handle(exchange: HttpExchange): Unit = {
              requests.add(exchange.getRequestURI.getPath)
              exchange.sendResponseHeaders(404, -1)
              exchange.close()
            }
          })
          server.start()
          try {
            When("the canonical download fails")
            val error = intercept[RuntimeException] {
              CarDependencyResolver.resolve(
                _dependency,
                Seq(s"http://127.0.0.1:${server.getAddress.getPort}/car"),
                directory / "cache"
              )
            }
            val cacheparent = directory / "cache" / "org" / "alpha" / "textus" / "textus-shared" / "0.6.0"
            val archive = cacheparent / "textus-shared-0.6.0.car"
            val debris = Option(cacheparent.listFiles()).toVector.flatten.map(_.getName).filter(_.endsWith(".tmp"))

            Then("resolution reports the canonical key and leaves no partial cache archive or sibling debris")
            error.getMessage shouldBe s"[sbt-cozy] CAR dependency not found: org.alpha.textus.Shared:0.6.0; searched http://127.0.0.1:${server.getAddress.getPort}/car"
            requests.toArray.toVector.map(_.toString) shouldBe Vector(
              "/car/org/alpha/textus/textus-shared/0.6.0/textus-shared-0.6.0.car"
            )
            archive.isFile shouldBe false
            debris shouldBe empty
          } finally {
            server.stop(0)
          }
        }
      }
    }

    "apply SNAPSHOT policy" which {
      "retain remote SNAPSHOT suppression and report the canonical missing key" in {
        Given("a SNAPSHOT dependency and an empty local repository")
        _with_temp_dir("sbt-cozy-car-missing") { directory =>
          val dependency = CarDependency(_namespace, "Shared", "0.6.0-SNAPSHOT")

          When("no local canonical artifact is present")
          val error = intercept[RuntimeException] {
            CarDependencyResolver.resolve(
              dependency,
              Seq((directory / "repository").getAbsolutePath, "http://127.0.0.1:1/car"),
              directory / "cache"
            )
          }

          Then("the remote source remains suppressed and the key remains qualified")
          error.getMessage should include("CAR dependency not found: org.alpha.textus.Shared:0.6.0-SNAPSHOT")
        }
      }
    }
  }

  private def _with_temp_dir[A](prefix: String)(f: File => A): A = {
    val parent = file("target/sbt-cozy-test/work/car-dependency-resolver")
    IO.createDirectory(parent)
    val directory = Files.createTempDirectory(parent.toPath, s"${prefix}-").toFile
    try f(directory)
    finally IO.delete(directory)
  }

  private def _write(path: File, content: String): File = {
    IO.createDirectory(path.getParentFile)
    IO.write(path, content)
    path
  }
}
