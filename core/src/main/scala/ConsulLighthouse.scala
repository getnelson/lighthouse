//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package lighthouse

import ConsulLighthouse._
import LighthouseOp._
import LighthouseJson._
import LighthouseException._
import ScalazHelpers._
import helm.{ConsulOp, Key}
import ConsulOp.ConsulOpF
import journal.Logger
import java.util.concurrent.ScheduledExecutorService

import org.http4s.util.CaseInsensitiveString

import scala.concurrent.duration._
import org.http4s.{Header, Headers, Request, Uri}
import Uri.uri

import scalaz._
import scalaz.std.anyVal._
import scalaz.syntax.std.option._
import scalaz.syntax.monad._
import scalaz.syntax.traverse._
import scalaz.concurrent.{Strategy, Task}
import scala.util.control.NonFatal

private[lighthouse] final class ConsulLighthouse(resourceMapKey: Key)
    extends (LighthouseOp ~> Lh) {

  /*
   * TIM: gross hack, but this is really an internal implementation detail.
   * anything else would change the signitures and cause a breaking change
   * that I dont want to take right now (Feb 22nd 2017).
   *
   * Using `dc1` as the default, as this is the default datacenter consul comes with.
   */
  val runtimeDatacenter = sys.env.get("NELSON_DATACENTER").map(_.trim).getOrElse("dc1")

  val contextNamespace: Lh[Namespace] =
    for {
      ctx <- lh.ask
      // silly to match on only one possibility, but we want a compile error
      // when other types of contexts are added so we remember to update this
      ns <- ctx match {
        case LighthouseContext.System => withResourceMap.map(_.defaultNamespace)
      }
    } yield ns

  /**
   * If the state already contains a resource map, return it.
   * Otherwise, fetch the resource map, put it in the state, and return it.
   */
  val withResourceMap: Lh[ResourceMap] =
    OptionT[Lh, ResourceMap](lh.get).getOrElseF(
      fetchResourceMapOp(resourceMapKey).liftM[LhF] >>! (rm => lh.put(rm.some)))

  def apply[A](op: LighthouseOp[A]): Lh[A] = op match {
    case LookupResource(resource) => lookupResource(resource)
    case HttpsEndpoint(service) => serviceEndpoint(service)
    case LookupInstances(resource) => lookupInstances(resource)
    case LookupService(service) =>
      contextNamespace map { namespace =>
        localhost / namespace.asString / service.asString
      }
  }

  def lookupResource(resource: NetworkResource): Lh[NonEmptyList[WeightedResourceLocation]] =
    for {
      namespace <- contextNamespace
      resourceMap <- withResourceMap
      resources <- Lh.fromDisjunction(resourceMap.namespaces.get(namespace).toRightDisjunction(NamespaceNotFound(namespace)))
      locations <- Lh.fromDisjunction(resources.get(resource).toRightDisjunction(ResourceNotFound(resource)))
    } yield locations.map(l =>
      l.location.host match {
        case Uri.RegName(host) => l.copy(
          location = l.location.copy(host=Uri.RegName(host = CaseInsensitiveString(s"${host}.service.${runtimeDatacenter}.${resourceMap.domain.asString}"))))
        case x => l
      })

  // TODO probably need to add at least one header based on the context (ex: experimentation)
  def serviceEndpoint(service: ServiceType): Lh[Request] =
    contextNamespace map { namespace =>
      Request(
        uri = localhost,
        headers = Headers(Header("X-Nelson-Stack", s"default.${service.asString}")))
    }

  def lookupInstances(resource: NetworkResource): Lh[NonEmptyList[Endpoint]] =
    for {
      rs <- lookupResource(resource)
      withNames <- Lh.fromDisjunction(
        rs.traverseU{ l =>
          l.location.host match {
            case n: Uri.RegName => \/.right(n)
            case x => \/.left(configurationError(s"expected a host name but found [$x] when looking up $resource"))
          }
        })
      instances <- withNames.traverseM{ name =>
        val endpoints = dns.DnsOp.resolveSrv(name).map(_.map(target =>
          Endpoint(target.host, target.port)))
        Lh.fromOpM(OpM.injectFC(endpoints))
      }
    } yield instances
}

private[lighthouse] object ConsulLighthouse {
  private val log: Logger = Logger[this.type]
  import lighthouse.dns.DnsOp

  /** Lighthouse can perform both consul and DNS operations */
  type Op[A] = Coproduct[ConsulOp, DnsOp, A]

  /**
   * The operations are lifted into a free monad so we can write programs
   * without tying ourselves to a particular effect type
   */
  type OpM[A] = Free.FreeC[Op, A]

  /**
   * We need to be able to raise errors in a way that isn't tied to our effect
   * type.
   */
  type ErrOp[A] = EitherT[OpM, LighthouseException, A]

  /**
   * a `LhF[F, A]` ('Lh' is short for Lighthouse) takes a `LighthouseContext` as
   * input and returns an `ErrOp[A]` with effects of type `F`. It may also
   * reference/update an `Option[ResourceMap]` "state" component which represents
   * a cache of the resources available on the network (this will usually come
   * from consul).
   *
   * This is a partially-applied version of [[Lh]] that is the right shape for
   * `liftM`.
   *
   * I'm so sorry, these types really got out of hand. I'm not sure what happened.
   */
  type LhF[F[_], A] = ReaderWriterStateT[F, LighthouseContext, Unit, Option[ResourceMap], A]

  /**
   * An [[LhF]] where the effect type is [[ErrOp]].
   */
  type Lh[A] = LhF[ErrOp, A]

  object OpM {

    /** A version of [[injectFC]] that returns a natural transformation (NT) */
    def injectFCNT[F[_]](implicit I: Inject[F, Op]): Free.FreeC[F, ?] ~> OpM =
      ScalazHelpers.injectFC

    /**
     * Lift a `FreeC` of a specific type of operation (such as ConsulOp or DnsOp)
     * into an `OpM`, that is a `FreeC` of the coproduct of the possible
     * operation types.
     */
    def injectFC[F[_], A](fa: Free.FreeC[F, A])(implicit I: Inject[F, Op]):  OpM[A] =
      injectFCNT.apply(fa)
  }

  type OpC[A] = Coyoneda[Op, A]
  implicit val monadOpM: Monad[OpM] = Free.freeMonad[OpC]
  val lh = ReaderWriterStateT.rwstMonad[ErrOp, LighthouseContext, Unit, Option[ResourceMap]]
  val consulInj: Inject[ConsulOp, Op] = implicitly[Inject[ConsulOp, Op]]

  def liftOp[F[_],A](fa: F[A])(implicit I: Inject[F,Op]): OpM[A] = Free.liftFC(I.inj(fa))

  object Lh {
    def fromDisjunction[A](dis: LighthouseException \/ A): Lh[A] =
      EitherT(dis.point[OpM]).liftM[LhF]

    def fromOpM[A](fa: OpM[A]): Lh[A] = EitherT.right[OpM, LighthouseException, A](fa).liftM[LhF]
  }

  val localhost: Uri = uri("http://127.0.0.1:4477")

  def errOpToTask(client: Op ~> Task): ErrOp ~> Task = new (ErrOp ~> Task) {
    def apply[A](errOp: ErrOp[A]): Task[A] =
      Free.runFC(errOp.run)(client).flatMap(Task.fromDisjunction)
  }

  def lhToLighthouseTask(client: Op ~> Task): Lh ~> LighthouseTask = new (Lh ~> LighthouseTask) {
    val toTask = errOpToTask(client)

    def apply[A](lha: Lh[A]): LighthouseTask[A] = Kleisli{ ctx =>
      toTask(lha.eval(ctx, None).map(_._2))
    }
  }

  def cachingLighthouse(resourceMapKey: Key, client: Op ~> Task,
      strategy: Strategy, executor: ScheduledExecutorService, afterSuccess: FiniteDuration,
      afterFailure: FiniteDuration): Lh ~> LighthouseTask = new (Lh ~> LighthouseTask) {
    val toTask = errOpToTask(client)
    val fetchResources = toTask(fetchResourceMapOp(resourceMapKey))

    // TODO we should probably have some sort of retry/circuit-breaker logic here
    val currentResources: Task[Option[ResourceMap]] =
      cache.periodicRefresh(fetchResources, afterSuccess, afterFailure, strategy, executor).
        toSignal(strategy).continuous.once.runLast.timed(1.second)(executor) handle {
          case NonFatal(e) =>
            log.error(s"Error when attempting to fetch the lighthouse cache. Continuing without the cache for the next request.", e)
            None
        }

    def apply[A](lha: Lh[A]): LighthouseTask[A] = Kleisli{ ctx =>
      for {
        cachedResources <- currentResources
        result <- toTask(lha.eval(ctx, cachedResources))
      } yield result._2
    }
  }

  def taskConsulLighthouse(resourceMapKey: Key, consulClient: ConsulOp ~> Task, dnsClient: DnsOp ~> Task): Lighthouse[LighthouseTask] = {
    val opClient = consulClient or dnsClient
    lhToLighthouseTask(opClient) compose new ConsulLighthouse(resourceMapKey)
  }

  def fetchResourceMapOp(resourceMapKey: Key): ErrOp[ResourceMap] =
    EitherT(fetchResourceMap(resourceMapKey)).trans(OpM.injectFCNT)

  def fetchResourceMap(resourceMapKey: Key): ConsulOpF[LighthouseException \/ ResourceMap] =
    ConsulOp.getJson[ResourceMap](resourceMapKey).map(
      _.leftMap(err =>
        configurationError(s"failed to decode resource map: $err")
      ).flatMap(
        _.toRightDisjunction(configurationError(s"key not found in consul: $resourceMapKey")))
    )
}
