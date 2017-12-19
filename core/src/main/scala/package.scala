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
import lighthouse.LighthouseOp
import lighthouse.ScalazHelpers._

import helm.http4s.Http4sConsulClient
import helm.ConsulOp

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}
import scala.concurrent.duration.DurationInt
import java.io.File
import scala.io.Source
import scalaz.{~>, Free, Kleisli, Monad}
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.DefaultScheduler
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.util.threads.threadFactory
import journal.Logger

package object lighthouse {
  private val log: Logger = Logger[this.type]
  type Lighthouse[F[_]] = LighthouseOp ~> F
  type LighthouseTask[A] = Kleisli[Task, LighthouseContext, A]

  final implicit class LighthouseOpMOps[A](val op: LighthouseOp.OpM[A]) extends AnyVal {
    def runWith[F[_]:Monad](client: Lighthouse[F]): F[A] = Lighthouse.run(client, op)
  }

  object Lighthouse {
    private val defaultThreadFactory: ThreadFactory = threadFactory(
      daemon = true,
      name = i => s"lighthouse-worker-${i}")

    private[lighthouse] val schedulingPool: ScheduledExecutorService =
      Executors.newScheduledThreadPool(2, defaultThreadFactory)

    val defaultStrategy: Strategy =
      Strategy.Executor(schedulingPool)

    def run[F[_]:Monad, A](client: Lighthouse[F], op: LighthouseOp.OpM[A]): F[A] =
      Free.runFC(op)(client)

    def defaultClient(http4sClient: Client, stackName: StackName = fetchStackName.run /*YOLO*/,
                      overrideFile: Option[File] = sys.env.get("NELSON_DISCOVERY_FILE").map(new File(_)),
                      strategy: Strategy = defaultStrategy, executor: ScheduledExecutorService = schedulingPool): Lighthouse[LighthouseTask] = {
      val resolver = new org.xbill.DNS.ExtendedResolver
      resolver.setTCP(true) // side effect!
      val dns = new lighthouse.dns.DnsJava(resolver)
      val consul = consulInterp(http4sClient, overrideFile)
      val opClient = consul or dns
      val key = s"lighthouse/discovery/v1/${stackName.asString}"
      ConsulLighthouse.cachingLighthouse(key, opClient, strategy, executor, afterSuccess = 1.minute, afterFailure = 15.seconds) compose
        new ConsulLighthouse(key)
    }

    def testClient(serviceMap: Map[ServiceType, Uri], networkResourceMap: Map[NetworkResource, List[Endpoint]]): Lighthouse[LighthouseTask] =
      new TestLighthouse(serviceMap, networkResourceMap)

    /**
     * A consul implementation that always returns the contents of `file` no
     * matter which key is requested. This can be useful for testing or running
     * services locally.
     */
    def staticConsul(file: File): ConsulOp ~> Task =
      new (ConsulOp ~> Task) {
        def apply[A](op: ConsulOp[A]): Task[A] = op match {
          case ConsulOp.Get(_) =>
            Task.delay(Some(Source.fromFile(file).mkString))
          case x =>
            Task.fail(new UnsupportedOperationException(s"not supported on static consul: $x"))
        }
      }

    /*
     * Attempts to load the consul location from environment variables:`CONSUL_ADDR`
     * If not present, then we fallback to blindly assuming Consul must be
     * running right alongside the application.
     */
    private def consulInterp(http4sClient: Client,
                             overrideFile: Option[File]): ConsulOp ~> Task = {
      val consulAddr: Option[String] = sys.env.get("CONSUL_ADDR")
      log.debug(s"Value of env var CONSUL_ADDR is: $consulAddr")
      val addr: Uri = consulAddr
        .flatMap(Uri.fromString(_).toOption)
        .getOrElse(Uri.uri("http://localhost:8500"))

      overrideFile.fold[ConsulOp ~> Task](new Http4sConsulClient(addr, http4sClient))(staticConsul(_))
    }

    private def fetchStackName: Task[StackName] =
      Task.delay(sys.env("NELSON_STACKNAME")).map(StackName(_))
  }
}
