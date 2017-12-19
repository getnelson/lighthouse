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

import LighthouseOp._

import org.http4s.{Request, Uri}
import scalaz.concurrent.Task
import scalaz.{Kleisli, NonEmptyList}

private[lighthouse] class TestLighthouse(serviceMap: Map[ServiceType, Uri], networkResourceMap: Map[NetworkResource, List[Endpoint]]) extends Lighthouse[LighthouseTask] {
  def apply[A](op: LighthouseOp[A]): LighthouseTask[A] =
    Kleisli.kleisli { _ =>
      op match {
        case HttpsEndpoint(service) => uriOrDie(service).map(uri => Request(uri = uri))
        case LookupService(service) => uriOrDie(service)
        case LookupInstances(resource) => endpointsOrDie(resource)
        case LookupResource(_) => Task.fail(new UnsupportedOperationException("LookupResources not supported by TestLighthouse"))
      }
    }

  private def uriOrDie(service: ServiceType): Task[Uri] =
    serviceMap.get(service) match {
      case Some(uri) => Task.now(uri)
      case None => Task.fail(new NoSuchElementException(s"Key not defined in serviceMap: $service"))
    }

  private def endpointsOrDie(resource: NetworkResource): Task[NonEmptyList[Endpoint]] =
    networkResourceMap.get(resource) match {
      case Some(head :: endpoints) => Task.now(NonEmptyList.nel(head, endpoints))
      case _ => Task.fail(LighthouseException.ResourceNotFound(resource))
    }
}
