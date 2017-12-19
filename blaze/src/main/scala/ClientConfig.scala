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
package lighthouse.blazeclient

import org.http4s.util.threads
import org.http4s.client.blaze.{BlazeClientConfig, PooledHttp1Client}
import org.http4s.headers.{AgentProduct, `User-Agent`}
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.{Executors, ExecutorService}
import javax.net.ssl.SSLContext
import scala.concurrent.duration.{Duration, DurationInt}

final case class ClientConfig(
  maxTotalConnections: Int,
  blazeConfig: BlazeClientConfig)

object ClientConfig {
  object Defaults {
    val maxTotalConnections: Int = 1000
    val idleTimeout: Duration = 5.seconds
    val requestTimeout: Duration = 3.seconds
    val bufferSize: Int = 8*1024
    val userAgent = Some(`User-Agent`(AgentProduct("http4s-blaze", Some(org.http4s.BuildInfo.version))))
    def clientEC() = {
      val maxThreads = math.max(4, (Runtime.getRuntime.availableProcessors * 1.5).ceil.toInt)
      val threadFactory = threads.threadFactory(name = (i => s"http4s-blaze-client-$i"), daemon = true)
      Executors.newFixedThreadPool(maxThreads, threadFactory)
    }
    val endpointAuthentication: Boolean = true
    val hostnameVerification: Boolean = true
    val maxResponseLineSize: Int = 4 * 1024
    val maxHeaderLength: Int = 40 * 1024
    val maxChunkSize: Int = Integer.MAX_VALUE
  }

  def default(maxTotalConnections: Int = Defaults.maxTotalConnections, blazeConfig: BlazeClientConfig = defaultBlazeConfig()): ClientConfig =
    ClientConfig(maxTotalConnections, blazeConfig)

  def defaultBlazeConfig(
              idleTimeout: Duration = Defaults.idleTimeout,
              requestTimeout: Duration = Defaults.requestTimeout,
              userAgent: Option[`User-Agent`] = Defaults.userAgent,
              sslContext: Option[SSLContext] = None,
              endpointAuthentication: Boolean = Defaults.endpointAuthentication,
              bufferSize: Int = Defaults.bufferSize,
              maxResponseLineSize: Int = Defaults.maxResponseLineSize,
              maxHeaderLength: Int = Defaults.maxHeaderLength,
              maxChunkSize: Int = Defaults.maxChunkSize,
              executor: ExecutorService = Defaults.clientEC(),
              lenientParser: Boolean = false,
              group: Option[AsynchronousChannelGroup] = None): BlazeClientConfig =
    BlazeClientConfig(
      idleTimeout = idleTimeout,
      requestTimeout = requestTimeout,
      userAgent = userAgent,
      sslContext = sslContext,
      endpointAuthentication = endpointAuthentication,
      maxResponseLineSize = maxResponseLineSize,
      maxHeaderLength = maxHeaderLength,
      maxChunkSize = maxChunkSize,
      lenientParser = lenientParser,
      bufferSize = bufferSize,
      customExecutor = Some(executor),
      group = group)
}
