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

    "send the generated CBD HTTP envelope and unwrap its canonical response" in {
      Given("a local CBD HTTP gateway that records one request envelope")
      val canonical = "{\"schemaVersion\":\"textus.cbd.review-submission.v1\",\"documentType\":\"canonical-review-response\",\"report\":{\"report\":true},\"attestation\":{\"attestation\":true},\"gateResult\":\"unknown\"}"
      val gateway = new RecordingHttpGateway(canonical)
      gateway.start()
      val endpoint = new SbtCbdReviewHttpEndpoint(gateway.endpoint)

      When("sbt-cozy submits one raw provider-document submission")
      val response = try endpoint.submit("{\"documentType\":\"provider-document-submission\"}") finally gateway.stop()

      Then("the request uses the generated submission envelope and preserves CBD's canonical document and artifact bundle")
      gateway.method shouldBe "POST"
      gateway.contentType should startWith("application/json")
      gateway.body should include("\"submissionDocument\":\"{\\\"documentType\\\":\\\"provider-document-submission\\\"}\"")
      val envelope = response.fold(error => fail(error), identity)
      io.circe.parser.parse(envelope).toOption.flatMap(_.hcursor.get[String]("canonicalResponse").toOption) shouldBe Some(canonical)
      io.circe.parser.parse(envelope).toOption.flatMap(_.hcursor.get[String]("artifactBundle").toOption).map(_.nonEmpty) shouldBe Some(true)
    }

    "reject a malformed generated HTTP response envelope before it reaches the Review wire" in {
      Given("a loopback gateway whose response carries an undeclared envelope field")
      val gateway = new RecordingHttpGateway("{\"documentType\":\"canonical-review-response\"}", extraField = true)
      gateway.start()
      val endpoint = new SbtCbdReviewHttpEndpoint(gateway.endpoint)

      When("sbt-cozy receives a non-generated CBD response shape")
      val response = try endpoint.submit("{\"documentType\":\"provider-document-submission\"}") finally gateway.stop()

      Then("it refuses the response rather than scanning a nested string for a canonical result")
      response shouldBe Left("cbd-review-response-envelope-invalid")
    }

    "reject a canonical response with an invalid Report or attestation structure" in {
      Given("a CBD wire response whose attestation is a scalar rather than a document")
      val endpoint = new SbtCbdReviewWireEndpoint {
        def submit(value: String): Either[String, String] = Right(
          "{\"schemaVersion\":\"textus.cbd.review-submission.v1\",\"documentType\":\"canonical-review-response\",\"report\":{},\"attestation\":true,\"gateResult\":\"unknown\"}"
        )
      }
      val transport = new SbtCbdReviewWireTransport(endpoint)
      val paired = SbtReviewPairedSubmission("review-cozy-001", Vector(
        SbtReviewProviderDocuments("cozy", "{\"descriptor\":true}", "{\"reviewId\":\"review-cozy-001\",\"target\":{\"kind\":\"project\"}}", "{\"bundle\":true}"),
        SbtReviewProviderDocuments("sbt-cozy", "{\"descriptor\":true}", "{\"reviewId\":\"review-cozy-001\",\"target\":{\"kind\":\"project\"}}", "{\"bundle\":true}")
      ))

      When("the configured wire transport decodes CBD's response")
      val response = transport.submit(paired)

      Then("the transport refuses it before task artifacts can be written")
      response shouldBe Left("cbd-review-response-attestation-invalid")
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

  private final class RecordingHttpGateway(canonical: String, extraField: Boolean = false) {
    private val _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    private var _method = ""
    private var _content_type = ""
    private var _body = ""

    _server.createContext("/review", new HttpHandler {
      def handle(exchange: HttpExchange): Unit = {
        _method = exchange.getRequestMethod
        _content_type = Option(exchange.getRequestHeaders.getFirst("Content-Type")).getOrElse("")
        _body = scala.io.Source.fromInputStream(exchange.getRequestBody, "UTF-8").mkString
        val escaped = canonical.replace("\\", "\\\\").replace("\"", "\\\"")
        val suffix = if (extraField) ",\"unexpected\":true" else ""
        val bundle = "{\\\"documentType\\\":\\\"review-artifact-bundle\\\",\\\"markdown\\\":\\\"# Review\\\",\\\"pdfBase64\\\":\\\"JVBERg==\\\"}"
        val response = ("{\"canonical_response\":\"" + escaped + "\",\"artifact_bundle\":\"" + bundle + "\"" + suffix + "}").getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, response.length)
        try exchange.getResponseBody.write(response) finally exchange.close()
      }
    })

    def endpoint: String = s"http://127.0.0.1:${_server.getAddress.getPort}/review"
    def method: String = _method
    def contentType: String = _content_type
    def body: String = _body
    def start(): Unit = _server.start()
    def stop(): Unit = _server.stop(0)
  }
}
