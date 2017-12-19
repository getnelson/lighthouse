+++
layout = "index"
title  = "Home"
+++

<h1 id="overview" class="page-header">Overview</h1>

*Lighthouse* is a library for looking up arbitrary network resources such as databases and service components; it is a building block that can be quickly integrated with a variety of applications for a variety of service discovery use-cases. The figure below details the high-level workflow for applications that use lighthouse:

<div class="clearing">
  <img src="images/workflow.png" />
  <small><em>Figure 1.0: Lighthouse request workflow</em></small>
</div>

The workflow depicted in figure 1.0 shows the *Lighthouse* workflow when calling another remote service (other typical use cases are depicted in the [User Guide](#user-guide):

+ **A**: When the application is running, it makes one or more invocations of the *Lighthouse* API, which will fetch data from the backing [Consul](https://www.consul.io) storage system. This data (shown later in the [protocol section](#protocol)) informs the application how to turn a logical name (e.g. `my-cassandra`) into a Consul `service` name.

+ **B**: In this example, traffic is routed to the local [Envoy](https://lyft.github.io/envoy/) side-car, which maintains TLS connections to outbound services, implements rate limiting and circuit breaking. Other non-envoy lighthouse workflows are discussed in the [user guide](#user-guide).

+ **C**: Envoy is dynamically aware of the locations of network dependencies for this particular container, and so when receiving a request from the application will automatically conduct load balancing to the remote IP:PORT destinations.

With this frame, we can see that *Lighthouse* is actually a very thin layer that translates human-friendly names into names resolvable in Consul's service catalog, which the runtime routing infrastructure will later use to find the runtime locations of addresses that service that particular network system.

<h1 id="quickstart" class="page-header">QuickStart</h1>

Getting started with *Lighthouse* is straight forward. Update your build file to include the dependency, and then modify your project code pertaining to your use case.

<h2 id="quickstart-setup" data-subheading-of="quickstart">Project Setup</h2>

Various transports are available for *Lighthouse*, but it is highly recommended that most users leverage the `blaze` transport, like so:

```tut:invisible
def dependency(module: String): String = s""""io.verizon.lighthouse" %% "$module" % "${io.verizon.lighthouse.BuildInfo.version}""""
```

```tut:evaluated
println(s"""libraryDependencies += ${dependency("blaze")}""")
```

This will pull in the Consul-backed lighthouse.

<h3 id="quickstart-setup-client" class="linkable">Creating the Client</h3>

To create the default client, one needs to wire together the [http4s](http://http4s.org/) client and the *Lighthouse* client. The idea here is that the transport client (http4s in this example) can then be tuned for the workload the implementing application will be conducting. *Lighthouse* comes with some reasonable defaults, but having the ability to tune if required is often an important aspect.

```tut:reset:silent
import org.http4s.client.Client
import lighthouse._, blazeclient.{ClientConfig, defaultHttp4sClient}

final case class ApplicationConfig(
  http4sClient: Client,
  lighthouse: Lighthouse[LighthouseTask])

def initializeConfig(): ApplicationConfig = {
  val http4sClient = defaultHttp4sClient(ClientConfig.default())
  val lighthouse = Lighthouse.defaultClient(http4sClient)
  ApplicationConfig(http4sClient, lighthouse)
}
```

This example assumes your application is structured in a functional manner, where you have a typed configuration class and are passing that configuration around in your application (e.g. with `Reader` monad or similar). Even if you do not have this kind of structure, you can use *Lighthouse*, but the initialization would look slightly different.

<h2 id="quickstart-service" data-subheading-of="quickstart">Service Resource</h2>

The primary intended use-case for *Lighthouse* is to call over service systems. To that end, *Lighthouse* prioritizes that and provides a convenient set of functional idioms to make doing that easy. Here are some examples of how you might make service calls using *Lighthouse* and http4s.

```tut:reset:silent
import lighthouse._
import lighthouse.LighthouseOp.serviceCall

import org.http4s.Method
import org.http4s.client.Client
import scalaz.concurrent.Task

object Accounts {
  import JsonSupport._ // defined below

  final case class ApplicationConfig(http4sClient: Client, lighthouse: Lighthouse[LighthouseTask])
  final case class AccountContext(app: ApplicationConfig, userCtx: LighthouseContext)

  final case class User(id: String, name: String, age: Int)
  final case class NewUser(name: String, age: Int)

  def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")

  // The type of service that is being called.
  // For the Nelson infrastructure, this must match up with what's declared in
  // its .nelson.yml.
  val accountService: ServiceType = ServiceType("accounts-http")

  // a fairly simple GET call that simply returns a String
  def fetchUserName(userId: String, ctx: AccountContext): Task[String] = {
    val op = serviceCall(accountService, Method.GET,
      _ / "users" / urlEncode(userId) / "name")

    for {
      req <- op.runWith(ctx.app.lighthouse).run(ctx.userCtx)
      name <- ctx.app.http4sClient.expect[String](req)
    } yield name
  }

  // a call that POSTs a JSON body and decodes a response JSON body
  def createUser(newUser: NewUser, ctx: AccountContext): Task[User] = {
    val op = serviceCall(accountService, Method.POST,
      _ / "users")

    for {
      req <- op.runWith(ctx.app.lighthouse).run(ctx.userCtx)
      withBody <- req.withBody(newUser)
      user <- ctx.app.http4sClient.expect[User](withBody)
    } yield user
  }

  object JsonSupport {
    import argonaut._
    import org.http4s.{EntityDecoder, EntityEncoder}
    import org.http4s.argonaut.{jsonOf, jsonEncoderOf}

    implicit val userCodec: CodecJson[User] =
      CodecJson.casecodec3(User.apply, User.unapply)("id", "name", "age")

    implicit val userDecoder: EntityDecoder[User] = jsonOf[User]

    implicit val newUserCodec: CodecJson[NewUser] =
      CodecJson.casecodec2(NewUser.apply, NewUser.unapply)("name", "age")

    implicit val newUserEncoder: EntityEncoder[NewUser] = jsonEncoderOf[NewUser]
  }
}
```

This is a fairly complete example, which is why it is fairly long. This includes all the JSON encoders and everything, but as can be seen, the *Lighthouse* section is short.

<h2 id="quickstart-arbitrary" data-subheading-of="quickstart">Arbitrary Resources</h2>

*Lighthouse* can be used to look up addresses of a network system (such members of a database cluster). If you don't know the name of the resource you need (`cassandra-ads` in the example below), then you can simply run `nelson units list -ns dev` to see what units are available to depend on within the `dev` namespace.

```tut:reset:silent
import scalaz.NonEmptyList
import scalaz.concurrent.Task

object Example {
  import lighthouse._, LighthouseOp.lookupInstances

  val cassandraResource = NetworkResource(ServiceType("cassandra-ads"), PortName.Default)

  /**
   * Find an instances in the Cassandra cluster.
   *
   * The returned `Endpoint` has the host name, port number, protocol, etc.
   */
  def cassandra(lighthouse: Lighthouse[LighthouseTask]): Task[NonEmptyList[Endpoint]] =
    lookupInstances(cassandraResource)
      .runWith(lighthouse)
      .run(LighthouseContext.System)
}
```

In this example the `cassandra` function will return a list of all the known Cassandra `Endpoint`'s for the `cassandra-ads` system. If we wanted to initialize a Cassandra driver with this set of endpoints, we can simply extract the authority information like so:

```
/**
 * The URI (in string form) with the host and port 10.10.123.241:5436
 */
def endpointAuthority(endpoint: Endpoint): String =
  endpoint.authority.renderString
```

These functions can be `map`ed over the list of `Endpoint` to return a list of addresses, such as is typically used to seed a database driver for a database like Cassandra, or a message queue like Kafka. If for some reason you needed the addresses complete with the protocol, then you can use a function like:

```
/**
 * The URI (in string form) for an endpoint, complete with protocol, host and port.
 */
def endpointUriString(endpoint: Endpoint): String =
  endpoint.httpUri.renderString
```

In the event you want to discover the URL for a single instance of the service, but not have lighthouse call it, one could do that using the following idiom:

```tut:reset:silent
import org.http4s.Uri
import scalaz.concurrent.Task

object Example {
  import lighthouse._, LighthouseOp.lookupService

  def foo(lighthouse: Lighthouse[LighthouseTask]): Task[Uri] =
    lookupService(ServiceType("foo-http"))
      .runWith(lighthouse)
      .run(LighthouseContext.System)
}
```

This will return a `Uri` that is already preformatted to route to the local routing infrastructure ([see the overview for information on that](#overview)), but it puts no constraints on what software you use to call the URI. This is typically not advised, but is useful in certain edge cases such as interacting with legacy or third-party libraries.

<h1 id="user-guide" class="page-header">User Guide</h1>

Before diving into this section, be sure to have read the [quickstart section](#quickstart), which is what most users are probably looking for. This section primarily covers the specific *Lighthouse* operations and how applications using *Lighthouse* can enable themselves for testing with *Lighthouse* protocol fixtures.

<h2 id="user-guide-operations" data-subheading-of="user-guide">Operations</h2>

Whilst *Lighthouse* has a limited set of operations in its algebra, these operations should form the building blocks of service discovery in a variety of use cases: irrespective if you're discovering a database, messaging system, another service, or some other internal components... if its a network system, *Lighthouse* should have you covered.

<table class="table">
  <thead>
    <tr>
      <td><strong>Operation</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tr>
    <td><code>serviceCall</code></td>
    <td>When making remote calls to another network system, <em>Lighthouse</em> will take the configured client and prepare a <code>Request</code> that is ready for execution. This model allows users to configure their outbound request in whichever manner makes sense for them, whilst not burdening the caller with the underlying details of discovery, routing or performance.</td>
  </tr>
  <tr>
    <td><code>lookupInstances</code></td>
    <td>Get a list of all the IP:PORT combinations for the requested network system. Using <code>lookupInstances</code> will return every address that is registered to handle this particular application.</td>
  </tr>
  <tr>
    <td><code>lookupInstance</code></td>
    <td>Essentially the same as <code>lookupInstances</code>, except that only a single address is returned. Ordered is non-deterministic, so consumers should not always expect to receive the same address at the head of the sequence.</td>
  </tr>
  <tr>
    <td><code>lookupService</code></td>
    <td>Returns a URI to call, instead of actually preparing a request or any kind of prepared client. This is a relatively primitive operation and should not be used by the majority of users.</td>
  </tr>
</table>

<h2 id="user-guide-testing" data-subheading-of="user-guide">Testing</h2>

*Lighthouse* is implemented as a [Free algebra](https://en.wikipedia.org/wiki/Free_algebra). If you're not familiar, the high-level ramification of this means that the use of *Lighthouse* operations and the execution of those operations are separate, and the *how* (the interpreter) of those operations can be swapped out without affecting your calling code. This rather useful feature means that one can swap out the production interpreter with one that is more appropriate for a testing environment. *Lighthouse* provides a test interpreter based on simple maps.

```tut:reset:silent
import scalaz.concurrent.Task
import org.http4s.Uri
import lighthouse._

val inventory = ServiceType("inventory")
val cassandra = NetworkResource(ServiceType("ads-cassandra"), PortName.Default)

val testLighthouse = Lighthouse.testClient(
  serviceMap = Map(inventory -> Uri.uri("http://127.0.0.1:4477/prod/inventory")),
  networkResourceMap = Map(cassandra -> List(Endpoint(Uri.RegName("cassandra.service.dc1.example.com"), Port(9042))))
)
```

This test lighthouse client is capable of the same operations as the default lighthouse client:

```tut
import lighthouse.LighthouseOp._

lookupService(inventory).runWith(testLighthouse).run(LighthouseContext.System).run
httpsEndpoint(inventory).runWith(testLighthouse).run(LighthouseContext.System).run
lookupInstances(cassandra).runWith(testLighthouse).run(LighthouseContext.System).run
lookupInstance(cassandra).runWith(testLighthouse).run(LighthouseContext.System).run
```

<h1 id="developers" class="page-header">Developers</h1>

Extending this Scala implementation of *Lighthouse* or implementing *Lighthouse* support in another language is relatively straight forward. This section contains the information one would need to do to modify or extend *Lighthouse*. This section is not required to simply use an existing implementation of *Lighthouse*.

<h2 id="developers-languages" data-subheading-of="developers">Languages</h2>

*Lighthouse* already has implementations in several languages:

+ [Scala](https://github.com/getnelson/lighthouse)

If you require something that is not currently available, please consider contributing or talking with the the Nelson team.

<h2 id="developers-protocol" data-subheading-of="developers">Protocol</h2>

The *Lighthouse* protocol is a simplistic JSON document that - for a given Consul service name - has corresponding data held in the Consul Key-Value storage. For example, the following data would be held in the Consul KV store at `lighthouse/discovery/v1/howdy-http--1-0-388--aeiq8irl`:

```
{
  "namespaces": [
    {
      "routes": [
        {
          "port": "default",
          "targets": [
            {
              "weight": 100,
              "protocol": "http",
              "port": 9000,
              "stack": "howdy-http--1-0-388--aeiq8irl"
            }
          ],
          "service": "howdy-http"
        }
      ],
      "name": "dev"
    }
  ],
  "domain": "your.consul-tld.com",
  "defaultNamespace": "dev"
}
```

<h2 id="developers-contributing" data-subheading-of="developers">Contributing</h2>

Contributing to *Lighthouse* is simple! If there is something you think needs to be fixed, either open an issue on GitHub or, better yet, just send a pull request with a patch. Whilst a fair amount of effort has been put into making Lighthouse easy-to-use, sometimes it helps to know more of the internal details, and the author encourages the reader to view the source code to understand the internals.

<h1 id="credits" class="page-header">Credits</h1>

*Lighthouse* was designed and created by the following good people:

* [Cody Allen](https://github.com/ceedubs)
* [Timothy Perrett](https://github.com/timperrett)
* [Stew O'Connor](https://github.com/stew)

In addition, the following people are honourably mentioned for their contributions, advice and early adoption of *Lighthouse*:

* Eduardo Jimenez
* Andrew Morhland
* Greg Flanagan
* Vincent Marquez
