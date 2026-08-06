package org.goldenport.cozy

import org.goldenport.cncf.component.identity.{
  ComponentId,
  ComponentIdentityProjection,
  ComponentIdentityResult,
  ComponentLocalId,
  ComponentNamespace
}

import scala.collection.JavaConverters._

private[cozy] final case class CarComponentReleaseProjection(
  identity: ComponentIdentityProjection,
  carfilename: String,
  mavencoordinate: String
)

private[cozy] object CarComponentIdentityAdapter {
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
    project(namespace, localId).flatMap { identity =>
      _either(identity.carFilename(release)).flatMap { carfilename =>
        _either(identity.mavenCoordinate(scalaBinaryVersion, release)).map { mavencoordinate =>
          CarComponentReleaseProjection(identity, carfilename, mavencoordinate)
        }
      }
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
    if (result.isSuccess())
      Right(result.value().get())
    else
      Left(result.error().get())
}
