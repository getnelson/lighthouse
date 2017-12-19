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

import org.http4s.Uri.RegName
import org.xbill.DNS._
import scalaz.{~>, NonEmptyList}
import scalaz.concurrent.Task
import scalaz.syntax.std.list._

/**
 * DNS interpreter that uses [[http://www.xbill.org/dnsjava/ dnsjava]] under the
 * hood.
 */
final class DnsJava(resolver: Resolver) extends (DnsOp ~> Task) {

  def apply[A](op: DnsOp[A]): Task[A] = op match {
    case ResolveSrv(host) => resolveSrv(host)
  }

  /**
   * Resolve a host into a collection of instances.
   *
   * Pretty much everything in here is a side effect that can throw exceptions,
   * so I'm just wrapping the whole thing in Task.delay and embracing the java
   * way.
   */
  def resolveSrv(host: RegName): Task[NonEmptyList[Target]] = Task.delay {
    val lookup = new Lookup(new Name(host.host.value), Type.SRV, DClass.IN)
    lookup.setResolver(resolver)
    // Side-effects!
    lookup.run()

    val code = lookup.getResult
    if (code != Rcode.NOERROR) {
      throw new RuntimeException(s"Unable to resolve SRV: $host => ANSWER: ${Rcode.string(code)}")
    }

    lookup.getAnswers.map{ rec =>
      val srv = rec.asInstanceOf[SRVRecord]
      Target(RegName(srv.getTarget.toString), Port(srv.getPort))
    }.toList
  }.flatMap {
    _.toNel.map(Task.now).getOrElse(
      Task.fail(new RuntimeException(s"No targets found for SRV: $host")))
  }
}
