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

import scalaz.{Free, NonEmptyList}
import org.http4s.Uri.{RegName, Host}

final case class Target(host: Host, port: Port)

sealed abstract class DnsOp[A] extends Product with Serializable

object DnsOp {
  final case class ResolveSrv(host: RegName) extends DnsOp[NonEmptyList[Target]]

  type OpM[A] = Free.FreeC[DnsOp, A]

  def resolveSrv(host: RegName): OpM[NonEmptyList[Target]] =
    Free.liftFC(ResolveSrv(host))
}
