package org.goldenport.cozy

import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 * @version Jul. 16, 2026
 * @author  ASAMI, Tomoharu
 */
final class SbtCarReviewClientSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "The sbt-cozy CAR Review client" should {
    "use fixed Cozy descriptor and stdin-evidence command templates" in {
      Given("one configured Cozy command prefix")
      val transport = new SbtCozyCommandReviewTransport(Seq("cozy"))

      When("the local client is asked to collect its provider documents")
      val outcome = transport.collect(new File("missing-project-root"), "{\"reviewId\":\"review-cozy-001\"}", "0.1.15-SNAPSHOT")

      Then("an invalid root is rejected before any caller-provided command or path reaches CBD")
      outcome shouldBe Left("cozy-review-project-root-invalid")
    }

    "collect local Cozy evidence and submit only paired provider documents to CBD" in {
      Given("local Cozy evidence, sbt evidence, and a recording CBD submission transport")
      val artifacts = SbtReviewEvidence.render(
        SbtReviewEvidenceTarget(Some("org.example"), "fixture", "1.0.0", "sha256:" + ("a" * 64)),
        "0.1.15-SNAPSHOT",
        Vector(SbtReviewTaskResult("task-result", "succeeded"))
      )
      val cozy = new RecordingCozyTransport()
      val cbd = new RecordingSubmissionTransport()

      When("the client drives the local/CI Review route")
      val response = SbtCarReviewClient.submit(new File("."), "{\"reviewId\":\"review-cozy-001\"}", "0.1.15-SNAPSHOT", artifacts, cozy, cbd).fold(error => fail(error), identity)

      Then("CBD receives exactly Cozy and sbt provider documents, never the workspace path")
      cozy.called shouldBe true
      cbd.submission.map(_.providerId) shouldBe Vector("cozy", "sbt-cozy")
      cbd.submission.map(_.bundle).forall(_.contains("workspace")) shouldBe false
      response.gate shouldBe "pass"
    }

    "refuse a CBD response without both canonical report and gate" in {
      Given("a paired submission transport with an incomplete response")
      val artifacts = SbtReviewEvidence.render(SbtReviewEvidenceTarget(None, "fixture", "1.0.0", "sha256:" + ("a" * 64)), "0.1.15-SNAPSHOT", Vector(SbtReviewTaskResult("task-result", "succeeded")))

      When("the transport omits the CBD gate")
      val response = SbtCarReviewClient.submit(new File("."), "{\"reviewId\":\"review-cozy-001\"}", "0.1.15-SNAPSHOT", artifacts, new RecordingCozyTransport(), new RecordingSubmissionTransport(SbtReviewCanonicalResponse("{\"report\":true}", "")))

      Then("sbt-cozy does not invent a local gate result")
      response shouldBe Left("cbd-review-response-incomplete")
    }

    "submit the two provider documents through the CBD wire contract without a workspace field" in {
      Given("a paired Review submission and a recording CBD wire endpoint")
      val endpoint = new RecordingWireEndpoint()
      val transport = new SbtCbdReviewWireTransport(endpoint)
      val request = "{\"reviewId\":\"review-cozy-001\",\"target\":{\"kind\":\"project\",\"name\":\"fixture\",\"digest\":\"sha256:" + ("a" * 64) + "\"}}"
      val paired = SbtReviewPairedSubmission("review-cozy-001", Vector(
        SbtReviewProviderDocuments("cozy", "{\"descriptor\":true}", request, "{\"bundle\":true}"),
        SbtReviewProviderDocuments("sbt-cozy", "{\"descriptor\":true}", request, "{\"bundle\":true}")
      ))

      When("sbt-cozy sends the provider documents to CBD")
      val response = transport.submit(paired).fold(error => fail(error), identity)

      Then("the request has no workspace authority and CBD's canonical result is retained")
      endpoint.document should include("\"providers\":[")
      endpoint.document should include("\"target\":{")
      endpoint.document should not include "workspace"
      response.report shouldBe "{\"report\":true}"
      response.gate shouldBe "unknown"
    }

    "reject an endpoint that could carry credentials or a non-HTTP scheme" in {
      Given("two unsafe configured CBD gateway locations")
      val credentialed = new SbtCbdReviewHttpEndpoint("https://user:secret@cbd.example/review")
      val file = new SbtCbdReviewHttpEndpoint("file:///tmp/cbd-review")

      When("sbt-cozy attempts to submit a Review document")
      val credentialedresponse = credentialed.submit("{}")
      val fileresponse = file.submit("{}")

      Then("the client refuses them before any HTTP request")
      credentialedresponse shouldBe Left("cbd-review-endpoint-invalid")
      fileresponse shouldBe Left("cbd-review-endpoint-invalid")
    }

    "admit only a declared Review role for local development gateway execution" in {
      Given("one valid, one invalid, and one non-loopback configured Review role")
      val valid = new SbtCbdReviewHttpEndpoint("http://127.0.0.1:1/review", reviewRole = Some("reviewer"))
      val invalid = new SbtCbdReviewHttpEndpoint("http://127.0.0.1:1/review", reviewRole = Some("viewer"))
      val remote = new SbtCbdReviewHttpEndpoint("https://cbd.example/review", reviewRole = Some("reviewer"))

      When("the clients validate their configured role before the HTTP exchange")
      val validresponse = valid.submit("{}")
      val invalidresponse = invalid.submit("{}")
      val remoteresponse = remote.submit("{}")

      Then("only a submission role on a loopback endpoint is accepted")
      validresponse shouldBe Left("cbd-review-http-request-failed")
      invalidresponse shouldBe Left("cbd-review-role-invalid")
      remoteresponse shouldBe Left("cbd-review-role-loopback-required")
    }

    "send the generated CBD HTTP envelope and unwrap its canonical response" in {
      Given("a local CBD HTTP gateway that records one request envelope")
      val canonical = "{\"schemaVersion\":\"textus.cbd.review-submission.v1\",\"documentType\":\"canonical-review-response\",\"report\":{\"report\":true},\"attestation\":{\"attestation\":true},\"gateResult\":\"unknown\"}"
      val gateway = new RecordingHttpGateway(canonical)
      gateway.start()
      val endpoint = new SbtCbdReviewHttpEndpoint(gateway.endpoint, reviewRole = Some("reviewer"))

      When("sbt-cozy submits one raw provider-document submission")
      val response = try endpoint.submit("{\"documentType\":\"provider-document-submission\"}") finally gateway.stop()

      Then("the request uses the generated submission envelope and preserves CBD's raw canonical document")
      gateway.method shouldBe "POST"
      gateway.contentType should startWith("application/json")
      gateway.role shouldBe "reviewer"
      gateway.body should include("\"submissionDocument\":\"{\\\"documentType\\\":\\\"provider-document-submission\\\"}\"")
      response shouldBe Right(canonical)
    }

    "derive Cozy's provider request from the same sbt target binding" in {
      Given("one sbt-cozy provider request")
      val sbtrequest = SbtReviewEvidence.render(
        SbtReviewEvidenceTarget(Some("org.example"), "fixture", "1.0.0", "sha256:" + ("a" * 64)),
        "0.1.15-SNAPSHOT",
        Vector(SbtReviewTaskResult("task-result", "succeeded"))
      ).request

      When("the local Cozy invocation request is derived")
      val request = SbtCarReviewClient.cozyProviderRequest(sbtrequest).fold(error => fail(error), identity)

      Then("both providers receive the identical Review and target without workspace authority")
      request should include("\"reviewId\":\"sbt-cozy-fixture-")
      request should include("\"target\":{\"digest\":\"sha256:")
      request should include("\"cozy.car-analysis\"")
      request should not include "workspace"
    }
  }

  private final class RecordingCozyTransport extends SbtLocalCozyReviewTransport {
    var called = false

    def collect(projectRoot: File, providerRequest: String, providerVersion: String): Either[String, SbtReviewProviderDocuments] = {
      called = true
      Right(SbtReviewProviderDocuments("cozy", "{\"descriptor\":true}", providerRequest, "{\"bundle\":true}"))
    }
  }

  private final class RecordingSubmissionTransport(response: SbtReviewCanonicalResponse = SbtReviewCanonicalResponse("{\"report\":true}", "pass")) extends SbtReviewSubmissionTransport {
    var submission = Vector.empty[SbtReviewProviderDocuments]

    def submit(value: SbtReviewPairedSubmission): Either[String, SbtReviewCanonicalResponse] = {
      submission = value.providers
      Right(response)
    }
  }

  private final class RecordingWireEndpoint extends SbtCbdReviewWireEndpoint {
    var document = ""

    def submit(value: String): Either[String, String] = {
      document = value
      Right("{\"schemaVersion\":\"textus.cbd.review-submission.v1\",\"documentType\":\"canonical-review-response\",\"report\":{\"report\":true},\"attestation\":{\"attestation\":true},\"gateResult\":\"unknown\"}")
    }
  }

  private final class RecordingHttpGateway(canonical: String) {
    private val _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    private var _method = ""
    private var _contentType = ""
    private var _role = ""
    private var _body = ""

    _server.createContext("/review", new HttpHandler {
      def handle(exchange: HttpExchange): Unit = {
        _method = exchange.getRequestMethod
        _contentType = Option(exchange.getRequestHeaders.getFirst("Content-Type")).getOrElse("")
        _role = Option(exchange.getRequestHeaders.getFirst("role")).getOrElse("")
        _body = scala.io.Source.fromInputStream(exchange.getRequestBody, "UTF-8").mkString
        val escaped = canonical.replace("\\", "\\\\").replace("\"", "\\\"")
        val response = ("{\"canonicalResponse\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.length)
        try exchange.getResponseBody.write(response) finally exchange.close()
      }
    })

    def endpoint: String = s"http://127.0.0.1:${_server.getAddress.getPort}/review"
    def method: String = _method
    def contentType: String = _contentType
    def role: String = _role
    def body: String = _body
    def start(): Unit = _server.start()
    def stop(): Unit = _server.stop(0)
  }
}
