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

import sbt._

object Dependencies {
  object helm {
    val org = "io.verizon.helm"
    val version = "1.4.78-scalaz-7.1"

    val http4s = org %% "http4s" % version
  }

  object journal {
    val org = "io.verizon.journal"
    val version = "3.0.18"

    val core = org %% "core" % version
  }

  object knobs {
    val version = "3.11.22"
    val org = "io.verizon.knobs"

    val core = org %% "core" % version
  }

  object http4s {
    val org = "org.http4s"
    val version = "0.15.5"

    val client = org %% "http4s-client" % version
    val argonaut = org %% "http4s-argonaut" % version
    val blazeClient = org %% "http4s-blaze-client" % version
    val dsl = org %% "http4s-dsl" % version
  }
}
