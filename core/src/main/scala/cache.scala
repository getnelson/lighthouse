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

import journal.Logger

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.duration.FiniteDuration
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream._

object cache {
  private val log: Logger = Logger[this.type]

  /**
   * An infinite and discrete stream that returns the results of running the
   * provided task periodically. When the task fails an error is logged but the
   * process does not terminate.
   *
   * @param task the task to periodically run
   * @param afterSuccess wait this long before running the task again each time
   *   that the task succeeds
   * @param afterFailure wait this long before running the task again each time
   *   that the task fails
   */
  def periodicRefresh[A](task: Task[A], afterSuccess: FiniteDuration, afterFailure: FiniteDuration, strategy: Strategy, executor: ScheduledExecutorService): Process[Task, A] = {
    val attemptAndPause = Process.eval(task.attempt).flatMap(
      _.fold(
        err =>
          Process.eval_(Task.delay(log.error(s"Error during periodic refresh. Will try again in $afterSuccess.", err))) ++
          time.sleep(afterFailure)(strategy, executor),
        a => Process.emit(a) ++ time.sleep(afterSuccess)(strategy, executor)))
    attemptAndPause.repeat
  }
}
