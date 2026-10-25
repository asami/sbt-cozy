package org.goldenport.cozy

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import io.circe.Json
import io.circe.parser.parse
import sbt._
import sbt.Keys._

/*
 * @since   Oct. 21, 2026
 * @version Oct. 22, 2026
 */
private[cozy] object LocalApplicationRuntimeUpdate {
  val protocol = "cncf.local-application-update.v1"
  val REQUEST_KIND = "cncf.local-application-update.v2"
  val componentIdentity = "org.simplemodeling.textus.SmWorkflow"
  val componentCoordinate = "org.simplemodeling.textus:textus-sm-workflow:0.1.0-SNAPSHOT"
  val approvedRepositories = Vector("ivy2Local", "central", "https://www.simplemodeling.org/repository/maven")
  private val _prefix = "publication.local_application_update."
  private val _fields = Set("enabled", "application", "producer", "root", "updater", "protocol",
    "root_coordinate", "repositories", "admission_host", "timeout_seconds")
  // The updater owns the one work budget; this fixed allowance covers supervision,
  // cleanup and retaining its result, without rearming or multiplying that budget.
  private val _supervision_allowance_millis = 10000L
  private val _preactivation_stages = Set("prepare", "materialize", "archive-admission", "bootstrap-admission", "install")
  private case class TerminationObservation(directreaped: Boolean, descendantsterminated: Boolean, forced: Boolean)

  final case class Registration(producer: File, root: File, updater: File, repositories: Vector[String],
    admissionhost: File, timeoutseconds: Double)
  final case class Prepared(registration: Registration, updateid: String, request: File, result: File,
    composite: File, directory: File)
  final case class PublishResult(publication: Json, runtimeupdate: Json, evidence: Option[File]) {
    def succeeded: Boolean =
      publication.hcursor.get[String]("state").toOption.contains("Completed") &&
        runtimeupdate.hcursor.get[String]("state").toOption.exists(Set("Activated", "NotRegistered"))
  }

  def registration(config: CozyProjectConfig, baseDirectory: File): Option[Registration] = {
    val authored = config.values.keySet ++ config.lists.keySet
    val keys = authored.filter(_.startsWith(_prefix))
    if (keys.isEmpty && !authored.contains(_prefix.stripSuffix("."))) None
    else {
      val enabled = config.value(_prefix + "enabled").getOrElse(_reject("registration enabled is required"))
      enabled match {
        case "false" => None
        case "true" =>
          if (keys.exists(key => !_fields.contains(key.stripPrefix(_prefix))))
            _reject("unsupported registration field")
          def _required_(key: String): String = config.value(_prefix + key).getOrElse(_reject("missing " + key))
          if (_required_("application") != "sm-workflow" || _required_("protocol") != protocol ||
            _required_("root_coordinate") != InstalledRuntimeClasspath.rootCoordinate)
            _reject("unsupported application update registration")
          val producer = _absolute(_required_("producer"))
          if (!producer.isDirectory || producer.toPath.toRealPath() != baseDirectory.toPath.toRealPath() ||
            producer.toPath != producer.toPath.toRealPath())
            _reject("registered producer must be the current real checkout")
          val root = _absolute(_required_("root"))
          val updater = _absolute(_required_("updater"))
          val host = _absolute(_required_("admission_host"))
          if (!root.isDirectory || !host.isDirectory ||
            !Files.isRegularFile(updater.toPath, LinkOption.NOFOLLOW_LINKS) || !updater.canExecute)
            _reject("registered root/host/updater is unavailable")
          if (!config.lists.contains(_prefix + "repositories")) _reject("missing repositories list")
          val repositories = config.list(_prefix + "repositories").toVector
          if (repositories != approvedRepositories)
            _reject("unsupported repository policy")
          val timeout = try _required_("timeout_seconds").toDouble catch {
            case _: NumberFormatException => _reject("timeout_seconds must be finite positive seconds")
          }
          if (timeout.isNaN || timeout.isInfinity || timeout <= 0 || timeout >= Long.MaxValue / 1000.0)
            _reject("timeout_seconds must be finite positive seconds")
          Some(Registration(producer, root, updater, repositories, host, timeout))
        case _ => _reject("enabled must be true or false")
      }
    }
  }

  def prepare(config: CozyProjectConfig, baseDirectory: File, targetDirectory: File, archive: File,
    coordinate: String, runtimeClasspath: Seq[Attributed[File]]): Option[Prepared] = registration(config, baseDirectory).map { binding =>
    if (coordinate != componentCoordinate) _reject("registered producer component differs from published CAR")
    if (!Files.isRegularFile(archive.toPath, LinkOption.NOFOLLOW_LINKS)) _reject("actual CAR archive is unavailable")
    val modules = _runtime_modules(runtimeClasspath)
    val updateid = UUID.randomUUID.toString
    val directory = new File(targetDirectory, "cozy/local-application-update/" + updateid).getAbsoluteFile
    Files.createDirectories(directory.toPath)
    val request = new File(directory, "request.json")
    val result = new File(directory, "runtime-update.json")
    val prepared = Prepared(binding, updateid, request, result, new File(directory, "publication.json"), directory)
    _write(request, Json.obj(
      "kind" -> Json.fromString(REQUEST_KIND), "update_id" -> Json.fromString(updateid),
      "producer" -> Json.fromString(binding.producer.getAbsolutePath),
      "application" -> Json.fromString("sm-workflow"), "root" -> Json.fromString(binding.root.getAbsolutePath),
      "root_coordinate" -> Json.fromString(InstalledRuntimeClasspath.rootCoordinate),
      "repositories" -> Json.arr(binding.repositories.map(Json.fromString): _*),
      "runtime_modules" -> Json.arr(modules: _*),
      "archive" -> Json.fromString(archive.getAbsolutePath),
      "component_identity" -> Json.fromString(componentIdentity), "component_coordinate" -> Json.fromString(coordinate),
      "admission_host" -> Json.fromString(binding.admissionhost.getAbsolutePath),
      "timeout_seconds" -> Json.fromDoubleOrNull(binding.timeoutseconds), "result" -> Json.fromString(result.getAbsolutePath)
    ))
    prepared
  }

  private def _runtime_modules(classpath: Seq[Attributed[File]]): Vector[Json] = {
    val identities = scala.collection.mutable.Set.empty[String]
    val sources = scala.collection.mutable.Set.empty[String]
    val revisions = scala.collection.mutable.Map.empty[(String, String), String]
    val modules = classpath.toVector.map { entry =>
      val module = entry.get(moduleID.key).getOrElse(_reject("runtime moduleID metadata is required"))
      val artifactmetadata = entry.get(artifact.key).getOrElse(_reject("runtime artifact metadata is required"))
      if (!Set("jar", "bundle").contains(artifactmetadata.`type`) || artifactmetadata.extension != "jar")
        _reject("runtime artifact must have jar or bundle packaging and jar extension")
      val parts = Vector(module.organization, module.name) ++
        artifactmetadata.classifier.toVector.flatMap(value => Vector("jar", value)) ++ Vector(module.revision)
      if (!parts.forall(_.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]*")))
        _reject("invalid canonical runtime coordinate")
      val coordinate = parts.mkString(":")
      val key = module.organization -> module.name
      if (revisions.get(key).exists(_ != module.revision)) _reject("conflicting runtime module revision/classifier")
      revisions.update(key, module.revision)
      val path = entry.data.toPath
      if (!path.isAbsolute || path.normalize != path || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
        path.toRealPath() != path || !entry.data.getName.endsWith(".jar"))
        _reject("runtime source must be a canonical absolute regular JAR")
      try {
        if (Files.getAttribute(path, "unix:nlink").asInstanceOf[Number].longValue != 1L)
          _reject("runtime source must be an independent regular JAR")
      } catch { case _: UnsupportedOperationException => () }
      try { val jar = new java.util.jar.JarFile(entry.data); jar.close() }
      catch { case NonFatal(error) => _reject("runtime source is not a readable JAR: " + error) }
      val source = path.toString
      if (!identities.add(coordinate)) _reject("duplicate runtime coordinate")
      if (!sources.add(source)) _reject("duplicate runtime source")
      Json.obj("coordinate" -> Json.fromString(coordinate), "source" -> Json.fromString(source))
    }
    if (!identities.contains(InstalledRuntimeClasspath.rootCoordinate)) _reject("exact runtime root module missing")
    modules
  }

  // Both task labels use this one continuation around the actual existing publisher.
  def publish(prepared: Option[Prepared], coordinate: String, destination: File)(publisher: => Unit): PublishResult = {
    val failure = try { publisher; None } catch {
      case interrupted: InterruptedException => Thread.currentThread.interrupt(); Some(interrupted)
      case NonFatal(error) => Some(error)
    }
    val publication = failure match {
      case None => Json.obj("state" -> Json.fromString("Completed"), "coordinate" -> Json.fromString(coordinate),
        "destination" -> Json.fromString(destination.getAbsolutePath))
      case Some(error) => Json.obj("state" -> Json.fromString("FailedMayHaveWritten"), "reason" -> Json.fromString(error.toString))
    }
    val runtimeupdate = failure match {
      case Some(_) => _state("NotAttempted", "publication did not complete")
      case None => prepared.map(run).getOrElse(_state("NotRegistered", "no enabled local application registration"))
    }
    val result = PublishResult(publication, runtimeupdate, prepared.map(_.composite))
    prepared.foreach { value =>
      _write(value.composite, Json.obj("kind" -> Json.fromString("cncf.local-application-publish.v1"),
        "update_id" -> Json.fromString(value.updateid), "publication" -> publication, "runtime_update" -> runtimeupdate))
    }
    result
  }

  def requireSuccess(result: PublishResult): Unit = if (!result.succeeded) {
    val state = result.publication.hcursor.get[String]("state").getOrElse("Unknown")
    val update = result.runtimeupdate.hcursor.get[String]("state").getOrElse("Unknown")
    sys.error(s"[sbt-cozy] local CAR publication=$state runtime update=$update; result=" +
      result.evidence.map(_.getAbsolutePath).getOrElse("not registered") + "; reason=" +
      result.publication.hcursor.get[String]("reason").toOption
        .orElse(result.runtimeupdate.hcursor.get[String]("reason").toOption).getOrElse(update))
  }

  def command(prepared: Prepared): Vector[String] =
    Vector(prepared.registration.updater.getAbsolutePath, "update", "--request", prepared.request.getAbsolutePath)

  def run(prepared: Prepared): Json =
    _run(prepared, _watchdog_timeout_millis(prepared.registration.timeoutseconds))

  private def _watchdog_timeout_millis(workseconds: Double): Long = {
    val workmillis = math.ceil(workseconds * 1000).toLong
    if (workmillis > Long.MaxValue - _supervision_allowance_millis) Long.MaxValue
    else workmillis + _supervision_allowance_millis
  }

  private def _run(prepared: Prepared, watchdogmillis: Long): Json = {
    val builder = new ProcessBuilder(command(prepared).asJava)
      .directory(prepared.registration.producer)
      .redirectOutput(new File(prepared.directory, "updater.stdout.log"))
      .redirectError(new File(prepared.directory, "updater.stderr.log"))
    val process = try builder.start() catch {
      case NonFatal(error) => return Json.obj("state" -> Json.fromString("FailedBeforeActivation"),
        "stage" -> Json.fromString("helper-start"), "reason" -> Json.fromString(error.toString),
        "retained_selection" -> _selection(prepared.registration.root))
    }
    try {
      if (!process.waitFor(watchdogmillis, TimeUnit.MILLISECONDS)) {
        val observation = _terminate(process)
        _timeout_result(prepared, process, observation)
      } else readResult(prepared, process.exitValue())
    } catch {
      case _: InterruptedException =>
        val observation = _terminate(process)
        Thread.currentThread.interrupt()
        _indeterminate("helper-interrupted", _termination_reason("updater completion interrupted; no automatic retry", observation))
      case NonFatal(error) =>
        val observation = _terminate(process)
        _indeterminate("helper-result", _termination_reason(error.toString, observation))
    }
  }

  def readResult(prepared: Prepared, exitCode: Int): Json =
    _decode_result(prepared, exitCode).fold(identity, identity)

  private def _decode_result(prepared: Prepared, exitcode: Int): Either[Json, Json] = {
    try {
      if (!Files.isRegularFile(prepared.result.toPath, LinkOption.NOFOLLOW_LINKS))
        return Left(_indeterminate("helper-result", "updater started without a retained result"))
      val result = parse(new String(Files.readAllBytes(prepared.result.toPath), StandardCharsets.UTF_8))
        .fold(error => throw new IllegalArgumentException(error.message), identity)
      val cursor = result.hcursor
      cursor.get[String]("state").toOption match {
        case Some("Activated") if exitcode == 0 && cursor.get[String]("update_id").toOption.contains(prepared.updateid) &&
            cursor.get[String]("installation_id").toOption.exists(_is_uuid) => Right(result)
        case Some("FailedBeforeActivation") if exitcode != 0 &&
            cursor.get[String]("stage").toOption.exists(_.nonEmpty) && cursor.get[String]("reason").toOption.exists(_.nonEmpty) &&
            cursor.downField("retained_selection").succeeded => Right(result)
        case Some("Indeterminate") if exitcode != 0 && cursor.get[String]("stage").toOption.exists(_.nonEmpty) &&
            cursor.get[String]("reason").toOption.exists(_.nonEmpty) => Right(result)
        case _ => Left(_indeterminate("helper-result", "updater exit and typed result disagree or result is unsupported"))
      }
    } catch { case NonFatal(error) => Left(_indeterminate("helper-result", error.toString)) }
  }

  private def _timeout_result(prepared: Prepared, process: Process, observation: TerminationObservation): Json = {
    val unknown = _indeterminate("helper-timeout",
      _termination_reason("updater deadline expired; no automatic retry", observation))
    if (!observation.directreaped || !observation.descendantsterminated || observation.forced) unknown
    else try {
      _decode_result(prepared, process.exitValue()) match {
        case Left(_) => unknown
        case Right(result) =>
          val cursor = result.hcursor
          cursor.get[String]("state").toOption match {
            case Some("FailedBeforeActivation") if cursor.get[String]("stage").toOption.exists(_preactivation_stages) &&
                _retained_selection_matches(prepared.registration.root, cursor.get[Json]("retained_selection").toOption) => result
            case Some("Indeterminate") => result
            case _ => unknown
          }
      }
    } catch { case NonFatal(_) => unknown }
  }

  private def _retained_selection_matches(root: File, retained: Option[Json]): Boolean = {
    val selected = new File(root, "selected").toPath
    if (!Files.exists(selected, LinkOption.NOFOLLOW_LINKS)) retained.contains(Json.Null)
    else if (!Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)) false
    else parse(new String(Files.readAllBytes(selected), StandardCharsets.UTF_8)).toOption.exists(retained.contains)
  }

  private def _termination_reason(reason: String, observation: TerminationObservation): String =
    reason + s"; direct_reaped=${observation.directreaped} descendants_terminated=${observation.descendantsterminated} force_used=${observation.forced}"

  private def _terminate(process: Process): TerminationObservation = {
    var interrupted = Thread.interrupted()
    var certain = true
    def _attempt_(action: => Unit): Unit = try action catch { case NonFatal(_) => certain = false }
    def _await_(deadline: Long)(action: Long => Boolean): Boolean = {
      var completed = false
      var waiting = true
      while (waiting) {
        val remaining = math.max(0L, deadline - System.nanoTime())
        try { completed = action(remaining); waiting = false } catch {
          case _: InterruptedException => interrupted = true; if (remaining == 0L) waiting = false
          case NonFatal(_) => certain = false; waiting = false
        }
      }
      completed
    }
    try {
      val children = try {
        val descendants = process.toHandle.descendants()
        try descendants.iterator().asScala.toVector.reverse finally descendants.close()
      } catch { case NonFatal(_) => certain = false; Vector.empty[ProcessHandle] }
      val completions = children.flatMap { child =>
        try Some(child.onExit()) catch { case NonFatal(_) => certain = false; None }
      }
      def _children_done_(deadline: Long): Boolean = completions.map { completion =>
        _await_(deadline) { remaining =>
          try { completion.get(remaining, TimeUnit.NANOSECONDS); true }
          catch { case _: java.util.concurrent.TimeoutException => false }
        }
      }.forall(identity)
      // Let the direct helper handle TERM and own its cleanup before forcing
      // either it or any descendants captured from this helper alone.
      _attempt_ { process.destroy() }
      val gracefuldeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      var directreaped = _await_(gracefuldeadline)(remaining => process.waitFor(remaining, TimeUnit.NANOSECONDS))
      var descendantsterminated = _children_done_(gracefuldeadline)
      val forced = !directreaped || !descendantsterminated
      if (forced) {
        if (!directreaped) _attempt_ { process.destroyForcibly(); () }
        children.foreach { child => _attempt_ { if (child.isAlive) { child.destroyForcibly(); () } } }
        val forceddeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        directreaped = _await_(forceddeadline)(remaining => process.waitFor(remaining, TimeUnit.NANOSECONDS))
        descendantsterminated = _children_done_(forceddeadline)
      }
      TerminationObservation(directreaped, certain && descendantsterminated, forced)
    } finally if (interrupted) Thread.currentThread.interrupt()
  }

  private def _selection(root: File): Json = try {
    parse(new String(Files.readAllBytes(new File(root, "selected").toPath), StandardCharsets.UTF_8)).getOrElse(Json.Null)
  } catch { case NonFatal(_) => Json.Null }

  private def _state(state: String, reason: String): Json =
    Json.obj("state" -> Json.fromString(state), "reason" -> Json.fromString(reason))

  private def _indeterminate(stage: String, reason: String): Json =
    Json.obj("state" -> Json.fromString("Indeterminate"), "stage" -> Json.fromString(stage), "reason" -> Json.fromString(reason))

  private def _is_uuid(value: String): Boolean = try UUID.fromString(value).toString == value catch {
    case _: IllegalArgumentException => false
  }

  private def _absolute(value: String): File = {
    val result = new File(value)
    if (!result.isAbsolute || result.toPath.normalize != result.toPath) _reject("registration paths must be absolute and normalized")
    result
  }

  private def _write(file: File, json: Json): Unit =
    try { Files.write(file.toPath, (json.spaces2 + "\n").getBytes(StandardCharsets.UTF_8)); () }
    catch { case NonFatal(error) => sys.error("[sbt-cozy] cannot retain application update result/request at " + file + ": " + error) }

  private def _reject(reason: String): Nothing = sys.error("[sbt-cozy] local application update preflight: " + reason)
}
