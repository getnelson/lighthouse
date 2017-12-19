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
package dns

import DnsOp._

import scalaz.{~>, NonEmptyList}
import scalaz.concurrent.Task

final case class MapDns(srv: Map[String, NonEmptyList[Target]]) extends (DnsOp ~> Task) {
  def apply[A](op: DnsOp[A]): Task[A] = op match {
    case ResolveSrv(host) =>
      srv.get(host.value).map(Task.now).getOrElse(Task.fail(new RuntimeException(s"host not found in SRV mapping: [$host]")))
  }
}
