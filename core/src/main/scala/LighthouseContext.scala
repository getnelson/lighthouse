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

sealed abstract class LighthouseContext extends Product with Serializable

// TODO there should be a user context that has experiment data, but we don't
// know quite what that looks like yet, so for now we just have System.
object LighthouseContext {

  /**
   * A context for Lighthouse operations that are not tied to a specific user
   * but instead happen as part of some system process such as a batch job,
   * service bootstrap, etc.
   *
   * When the `System` context is used, Lighthouse will look up system
   * information (such as an environment variable) to figure out which namespace
   * (ex: prod, qa, dev) should be used.
   */
  case object System extends LighthouseContext
}
