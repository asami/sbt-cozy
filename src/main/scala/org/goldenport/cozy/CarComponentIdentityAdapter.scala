package org.goldenport.cozy

import org.goldenport.cncf.component.identity.{
  ComponentId,
  ComponentIdentityProjection,
  ComponentIdentityResult,
  ComponentLocalId,
  ComponentNamespace,
  ComponentReleaseCoordinate
}

import scala.collection.JavaConverters._

private[cozy] final case class CarComponentReleaseProjection(
  identity: ComponentIdentityProjection,
  coordinate: ComponentReleaseCoordinate,
  scalamavencoordinate: String
) {
  def _release: String = coordinate.release()
  def _dependency_key: String = coordinate.dependencyKey()
  def _maven_release_key: String = coordinate.mavenReleaseKey()
  def _group_path: String = coordinate.groupPath()
  def _car_filename: String = coordinate.carFilename()
  def _car_repository_relative_path: String = coordinate.carRepositoryRelativePath()
  def _car_cache_relative_path: String = coordinate.carCacheRelativePath()
  def carfilename: String = _car_filename
  def mavencoordinate: String = scalamavencoordinate
}

private[cozy] object CarComponentIdentityAdapter {
  private val _namespace_required = "component.identity.namespace.required"

  def project(
    namespace: String,
    localId: String
  ): Either[ComponentIdentityResult.Error, ComponentIdentityProjection] =
    _component_id(namespace, localId).map(ComponentIdentityProjection.of)

  def projectRelease(
    namespace: String,
    localId: String,
    scalaBinaryVersion: String,
    release: String
  ): Either[ComponentIdentityResult.Error, CarComponentReleaseProjection] =
    _component_id(namespace, localId).flatMap { componentid =>
      _either(ComponentReleaseCoordinate.create(componentid, release)).flatMap { coordinate =>
        val identity = ComponentIdentityProjection.of(componentid)
        _either(identity.mavenCoordinate(scalaBinaryVersion, release)).map { maven =>
          CarComponentReleaseProjection(identity, coordinate, maven)
        }
      }
    }

  def _require_release(dependency: CarDependency): CarComponentReleaseProjection =
    dependency.namespace match {
      case Some(namespace) =>
        projectRelease(namespace, dependency.localId, "3", dependency.version).
          fold(error => sys.error(s"[sbt-cozy] ${error.code()}"), identity)
      case None =>
        sys.error(s"[sbt-cozy] ${_namespace_required}")
    }

  def validateNoScopedCollisions(
    identities: Vector[(String, String)]
  ): Either[ComponentIdentityResult.Error, Vector[ComponentIdentityProjection]] =
    _component_ids(identities).flatMap { componentids =>
      _either(ComponentIdentityProjection.validateNoScopedCollisions(componentids.asJava)).map { admitted =>
        admitted.asScala.toVector.map(ComponentIdentityProjection.of)
      }
    }

  private def _component_id(
    namespace: String,
    localid: String
  ): Either[ComponentIdentityResult.Error, ComponentId] =
    _either(ComponentNamespace.parse(namespace)).flatMap { parsednamespace =>
      _either(ComponentLocalId.parse(localid)).map { parsedlocalid =>
        ComponentId.of(parsednamespace, parsedlocalid)
      }
    }

  private def _component_ids(
    identities: Vector[(String, String)]
  ): Either[ComponentIdentityResult.Error, Vector[ComponentId]] =
    identities.foldLeft[Either[ComponentIdentityResult.Error, Vector[ComponentId]]](Right(Vector.empty)) {
      case (Right(componentids), (namespace, localid)) =>
        _component_id(namespace, localid).map(componentids :+ _)
      case (failure @ Left(_), _) => failure
    }

  private def _either[A](
    result: ComponentIdentityResult[A]
  ): Either[ComponentIdentityResult.Error, A] =
    if (result.isSuccess()) Right(result.value().get()) else Left(result.error().get())
}
