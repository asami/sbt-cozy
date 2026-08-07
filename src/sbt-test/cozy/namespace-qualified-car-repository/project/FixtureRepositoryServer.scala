import java.io.{File, OutputStream}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentHashMap, Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

import scala.collection.JavaConverters._

/** Deterministic loopback repository source for the Phase 56 scripted fixture. */
object FixtureRepositoryServer {
  private val _monitor = new AnyRef
  private val _request_counts = new ConcurrentHashMap[String, AtomicInteger]()
  private var _server: Option[HttpServer] = None
  private var _executor: Option[java.util.concurrent.ExecutorService] = None
  private var _endpoint_value: Option[String] = None
  private var _running_value = false

  def start(warehouseRoot: File): String = _monitor.synchronized {
    stop()
    val normalized = warehouseRoot.toPath.toAbsolutePath.normalize()
    Files.createDirectories(normalized.resolve("repository/car"))
    _request_counts.clear()
    val http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val threadfactory = new ThreadFactory {
      private val _sequence = new AtomicInteger(0)
      override def newThread(runnable: Runnable): Thread = {
        val thread = new Thread(runnable, s"phase56-fixture-http-${_sequence.incrementAndGet()}")
        thread.setDaemon(true)
        thread
      }
    }
    val pool = Executors.newCachedThreadPool(threadfactory)
    http.setExecutor(pool)
    http.createContext("/", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = _handle_request(exchange, normalized)
    })
    http.start()
    val port = http.getAddress.getPort
    _server = Some(http)
    _executor = Some(pool)
    _endpoint_value = Some(s"http://127.0.0.1:$port/repository/car")
    _running_value = true
    _endpoint_value.get
  }

  def endpoint: String = _monitor.synchronized {
    _endpoint_value.getOrElse(sys.error("Phase 56 fixture repository source is not started"))
  }

  def running: Boolean = _monitor.synchronized(_running_value)

  def stop(): Unit = _monitor.synchronized {
    _server.foreach(_.stop(0))
    _executor.foreach(_.shutdownNow())
    _server = None
    _executor = None
    _running_value = false
  }

  def counts: Map[String, Int] = _monitor.synchronized {
    _request_counts.asScala.map { case (path, count) => path -> count.get() }.toMap
  }

  private def _handle_request(exchange: HttpExchange, warehouseroot: Path): Unit = {
    val requestpath = exchange.getRequestURI.getPath
    _increment(requestpath)
    val method = exchange.getRequestMethod
    if (method != "GET") {
      _send(exchange, 405, "method not allowed\n".getBytes(StandardCharsets.UTF_8))
    } else {
      val relative = requestpath.stripPrefix("/")
      val candidate = warehouseroot.resolve(relative).normalize()
      val repositoryroot = warehouseroot.resolve("repository").normalize()
      val allowed = requestpath.startsWith("/repository/") && candidate.startsWith(repositoryroot)
      if (!allowed || !Files.isRegularFile(candidate)) {
        _send(exchange, 404, "not found\n".getBytes(StandardCharsets.UTF_8))
      } else {
        _send(exchange, 200, Files.readAllBytes(candidate))
      }
    }
  }

  private def _increment(path: String): Unit = _monitor.synchronized {
    val fresh = new AtomicInteger(0)
    val existing = _request_counts.putIfAbsent(path, fresh)
    val counter = if (existing == null) fresh else existing
    counter.incrementAndGet()
  }

  private def _send(exchange: HttpExchange, status: Int, bytes: Array[Byte]): Unit = {
    try {
      exchange.getResponseHeaders.add("Content-Type", "application/octet-stream")
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      val output: OutputStream = exchange.getResponseBody
      try output.write(bytes)
      finally output.close()
    } finally exchange.close()
  }
}
