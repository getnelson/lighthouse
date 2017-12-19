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

import org.http4s.{EmptyBody, EntityBody, Headers, Method, Request, Uri}
import scalaz.{Free, NonEmptyList}

sealed abstract class LighthouseOp[A] extends Product with Serializable

object LighthouseOp {
   private[lighthouse] final case class LookupResource(resource: NetworkResource) extends LighthouseOp[NonEmptyList[WeightedResourceLocation]]

  /**
   * Create an HTTPS request for a service.
   *
   * Note: this doesn't actually perform the HTTP request; it simply creates
   * a request that can then be executed. The request will have the base of the
   * URL populated.
   */
  final case class HttpsEndpoint(service: ServiceType) extends LighthouseOp[Request]

  final case class LookupInstances(resource: NetworkResource) extends LighthouseOp[NonEmptyList[Endpoint]]

  final case class LookupService(service: ServiceType) extends LighthouseOp[Uri]

  type OpM[A] = Free.FreeC[LighthouseOp, A]

  private[lighthouse] def lookupResources(resource: NetworkResource): OpM[NonEmptyList[WeightedResourceLocation]] =
    Free.liftFC(LookupResource(resource))

  // TODO should we log a warning if there's more than one item in the nel?
  // TODO should this even exist?
  private[lighthouse] def lookupResource(resource: NetworkResource): OpM[WeightedResourceLocation] =
    lookupResources(resource).map(_.head)

  def httpsEndpoint(service: ServiceType): OpM[Request] =
    Free.liftFC(HttpsEndpoint(service))

  def lookupInstances(resource: NetworkResource): OpM[NonEmptyList[Endpoint]] =
    Free.liftFC(LookupInstances(resource))

  def lookupInstance(resource: NetworkResource): OpM[Endpoint] =
    // TODO may want to randomize
    // Counterpoint: if you do, you will introduce non-determinism into TestLighthouse
    lookupInstances(resource).map(_.head)

  def lookupService(service: ServiceType): OpM[Uri] =
    Free.liftFC(LookupService(service))

  def serviceCall(
      service: ServiceType,
      method: Method,
      uriAppend: Uri => Uri,
      extraHeaders: Headers = Headers.empty,
      body: EntityBody = EmptyBody): OpM[Request] =
    httpsEndpoint(service).map(req =>
          req.copy(
            method = method,
            uri = uriAppend(req.uri),
            headers = req.headers ++ extraHeaders,
            body = body))
}
