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

import org.http4s.Uri
import scalaz.{Apply, NonEmptyList}
import argonaut._
import Argonaut._

private object LighthouseJson {
  implicit val decodePort: DecodeJson[Port] =
    IntDecodeJson.map(Port(_))

  implicit val decodeWeight: DecodeJson[Weight] =
    IntDecodeJson.map(Weight(_))

  implicit val decodeResourceLocation: DecodeJson[ResourceLocation] = DecodeJson(c =>
    (c --\ "stack").as[String].map(stack =>
      ResourceLocation(Uri.RegName(stack))))

  implicit val decodeWeightedResourceLocation: DecodeJson[WeightedResourceLocation] = DecodeJson(c =>
    Apply[DecodeResult].apply2(
      decodeResourceLocation(c),
      (c --\ "weight").as[Weight]
    )(WeightedResourceLocation.apply))

  implicit val decodePortName: DecodeJson[PortName] =
    StringDecodeJson.map(PortName(_))

  implicit val decodeServiceType: DecodeJson[ServiceType] =
    StringDecodeJson.map(ServiceType(_))

  implicit val decodeNamespace: DecodeJson[Namespace] =
    StringDecodeJson.map(Namespace(_))

  implicit val decodeDomain: DecodeJson[Domain] =
    StringDecodeJson.map(Domain(_))

  private final case class Route(service: ServiceType, port: PortName, targets: NonEmptyList[WeightedResourceLocation])
  private final case class NamespaceRoutes(namespace: Namespace, routes: List[Route])

  private implicit val decodeRoute: DecodeJson[Route] =
    DecodeJson.jdecode3L(Route.apply)("service", "port", "targets")

  private implicit val decodeNamespaceRoutes: DecodeJson[NamespaceRoutes] =
    DecodeJson.jdecode2L(NamespaceRoutes.apply)("name", "routes")

  private val decodeNamespaces: DecodeJson[ResourceMap.Namespaces] =
    implicitly[DecodeJson[List[NamespaceRoutes]]].map(namespaces =>
      namespaces.map(namespace =>
        namespace.namespace ->
          namespace.routes.map(route =>
            NetworkResource(route.service, route.port) -> route.targets).toMap
      ).toMap
    )


  private[lighthouse] implicit val decodeResourceMap: DecodeJson[ResourceMap] =
    DecodeJson.jdecode3L(ResourceMap.apply)("defaultNamespace", "domain", "namespaces")(implicitly, implicitly, decodeNamespaces)
}
