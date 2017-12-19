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

import org.http4s.Uri, Uri.{Authority, Host}
import org.http4s.util.CaseInsensitiveString

// TODO prevent negative etc
final case class Port(asInt: Int) extends AnyVal {
  override def toString: String = asInt.toString
}

// TODO enforce boundaries
final case class Weight(asInt: Int) extends AnyVal {
  override def toString: String = asInt + "%"
}

final case class StackName(asString: String) extends AnyVal

final case class ResourceLocation(host: Host)

final case class WeightedResourceLocation(location: ResourceLocation, weight: Weight)

/**
 * @param host A host name, such as `search-cassandra-1.2.3-d3adb00b.service.dc1.your.tld.com`
 */
final case class Endpoint(host: Host, port: Port) {
  def authority: Authority = Authority(host = host, port = Some(port.asInt))

  def httpUri: Uri = Uri(
    scheme = Some(CaseInsensitiveString("http")),
    authority = Some(authority))
}
