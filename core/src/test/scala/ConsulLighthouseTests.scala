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

import helm._
import ConsulLighthouseTest._
import LighthouseOp._
import lighthouse.dns._

import scala.io.Source
import org.http4s.{Header, Method, Request, Uri}

import scalaz.{Id, NonEmptyList, \/, ~>}
import Id.Id
import scalaz.std.string._
import scalaz.concurrent.Task
import scalaz.stream.Process
import _root_.argonaut._
import _root_.argonaut.Argonaut._
import scodec.bits.ByteVector
import org.scalatest._
import Matchers._
import org.http4s.util.CaseInsensitiveString
import org.scalactic.TypeCheckedTripleEquals

class ConsulLighthouseTest extends FlatSpec with Matchers with TypeCheckedTripleEquals {
  it should "look up a single resource" in {
    val lookupCassandra = Lighthouse.run(testLh, lookupResource(cassandra)).run(LighthouseContext.System)
    val discoveredCassandra = lookupCassandra.attemptRun
    discoveredCassandra should === (
      \/.right(WeightedResourceLocation(ResourceLocation(Uri.RegName("ads-cassandra-asdf.service.dc1.your.tld.com")), Weight(5))))
  }


  it should "discover a service URI for making a connection" in {
    val lookup = Lighthouse.run(testLh, lookupService(ServiceType("inventory"))).run(LighthouseContext.System)
    val discoveredService = lookup.attemptRun
    discoveredService should === (
      \/.right(Uri.uri("http://127.0.0.1:4477/prod/inventory"))
    )
  }

  it should "handle the http protocol" in {
    val lookupSearchDefault = Lighthouse.run(testLh, lookupResource(NetworkResource(search, PortName.Default))).run(LighthouseContext.System)
    lookupSearchDefault.attemptRun should === (
      \/.right(WeightedResourceLocation(ResourceLocation(Uri.RegName("search-api-ning-1.2.4-asdf.service.dc1.your.tld.com")), Weight(100))))
  }

  it should "create an HTTPS request" in {
    val searchRequest = httpsEndpoint(search).runWith(testLh).run(LighthouseContext.System)

    val actualResult = searchRequest.attemptRun.toEither.right.get
    actualResult.uri.toString should ===("http://127.0.0.1:4477")
    actualResult.headers.size should ===(1)
    actualResult.headers.get(CaseInsensitiveString("X-Nelson-Stack")) should ===(Some(Header("X-Nelson-Stack", s"default.${search.asString}")))
  }

  it should "create a service call" in {
    val body = Process.emit(ByteVector.encodeUtf8("""{"description": "this is a utf8 body €"}""").right.get) // YOLO
    val searchRequest = LighthouseOp.serviceCall(
      search, Method.POST, _ / "get" / "program" +? ("from", 0) +? ("limit", 3), body = body
    )

    val actualResult = searchRequest.runWith(testLh).run(LighthouseContext.System).attemptRun.toEither.right.get
    actualResult.uri.toString should ===("http://127.0.0.1:4477/get/program?from=0&limit=3")
    actualResult.headers.size should ===(1)
    actualResult.headers.get(CaseInsensitiveString("X-Nelson-Stack")) should ===(Some(Header("X-Nelson-Stack", s"default.${search.asString}")))

  }

  it should "look up instances" in {
    val lookupCassandra = Lighthouse.run(testLh, lookupInstances(cassandra)).run(LighthouseContext.System)
    val discoveredInstances = lookupCassandra.attemptRun
    discoveredInstances should === (
      \/.right(
        NonEmptyList(
          Endpoint(Uri.RegName("ads-cassandra-abcd.service.dc1.your.tld.com"), Port(123)),
          Endpoint(Uri.RegName("ads-cassandra-efgh.service.dc1.your.tld.com"), Port(124)),
          Endpoint(Uri.RegName("ads-cassandra-ijkl.service.dc1.your.tld.com"), Port(125)),
          Endpoint(Uri.RegName("ads-cassandra-mnop.service.dc1.your.tld.com"), Port(126)))))
  }

  it should "fail if there is no SRV record for an instance" in {
    val testDns: DnsOp ~> Task = MapDns(
      Map(
        "some-other-key.service.dc1.your.tld.com" ->
          NonEmptyList(
            Target(Uri.RegName("ads-cassandra-abcd.service.dc1.your.tld.com"), Port(123)),
            Target(Uri.RegName("ads-cassandra-efgh.service.dc1.your.tld.com"), Port(124)))))
    val testLh: Lighthouse[LighthouseTask] =
      ConsulLighthouse.taskConsulLighthouse("doesn't matter", testConsul, testDns)
    val lookupCassandra = Lighthouse.run(testLh, lookupInstances(cassandra)).run(LighthouseContext.System)
    val discoveredInstances = lookupCassandra.attemptRun
    discoveredInstances.leftMap(_.getMessage) should === (
      \/.left("host not found in SRV mapping: [ads-cassandra-asdf.service.dc1.your.tld.com]"))
  }

  it should "return configuration error if key doesn't exist" in {
    val testConsul = new (ConsulOp ~> Id) {
      def apply[A](fa: ConsulOp[A]): A = fa match {
        case ConsulOp.Get(key) => None
        case _ => throw new UnsupportedOperationException("not implemented in mock consul")
      }
    }

    val op = ConsulLighthouse.fetchResourceMap("foo")
    val res = helm.run(testConsul, op)
    res should === (\/.left(LighthouseException.ConfigurationError("key not found in consul: foo")))
  }

  it should "return configuration error if values JSON isn't right" in {
    val testConsul = new (ConsulOp ~> Id) {
      def apply[A](fa: ConsulOp[A]): A = fa match {
        case ConsulOp.Get(key) => Some("bar")
        case _ => throw new UnsupportedOperationException("not implemented in mock consul")
      }
    }

    val op = ConsulLighthouse.fetchResourceMap("foo")
    val res = helm.run(testConsul, op)
    res should === (\/.left(LighthouseException.ConfigurationError("failed to decode resource map: Unexpected content found: bar")))
  }
}

object ConsulLighthouseTest {

  val testConsul: ConsulOp ~> Task = Lighthouse.staticConsul(
    new java.io.File(getClass().getResource("/namespace.json").toURI))

  val testDns: DnsOp ~> Task = MapDns(
    Map(
      "ads-cassandra-asdf.service.dc1.your.tld.com" ->
        NonEmptyList(
          Target(Uri.RegName("ads-cassandra-abcd.service.dc1.your.tld.com"), Port(123)),
          Target(Uri.RegName("ads-cassandra-efgh.service.dc1.your.tld.com"), Port(124))),
      "ads-cassandra-fdsa.service.dc1.your.tld.com" ->
        NonEmptyList(
          Target(Uri.RegName("ads-cassandra-ijkl.service.dc1.your.tld.com"), Port(125)),
          Target(Uri.RegName("ads-cassandra-mnop.service.dc1.your.tld.com"), Port(126)))))

  val testLh: Lighthouse[LighthouseTask] =
    ConsulLighthouse.taskConsulLighthouse("doesn't matter", testConsul, testDns)

  val cassandra: NetworkResource = NetworkResource(ServiceType("ads-cassandra"), PortName.Default)

  val search: ServiceType = ServiceType("search-api-ning")
}
