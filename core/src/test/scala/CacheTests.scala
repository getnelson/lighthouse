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

import scala.concurrent.duration.DurationInt
import scalaz.\/
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.DefaultScheduler
import org.scalatest._, Matchers._
import org.scalactic.TypeCheckedTripleEquals

class CacheTest extends FlatSpec with Matchers with TypeCheckedTripleEquals {
  import CacheTest._

  val strategy = Strategy.DefaultStrategy
  val scheduler = DefaultScheduler

  "periodic refresh" should "handle failures without terminating" in {

    val task = counter().flatMap { i =>
      if (i == 2) failedTask
      else Task.now(i)
    }

    val p = cache.periodicRefresh(task, 100.millis, 50.millis, strategy, scheduler)
    p.take(5).runLog.attemptRun should ===(\/.right(Vector(0, 1, 3, 4, 5)))
  }

  it should "wait at least the specified time after success" in {
    val start = System.currentTimeMillis
    val elapsed = Task.delay{System.currentTimeMillis - start}

    val p = cache.periodicRefresh(elapsed, 100.millis, 50.millis, strategy, scheduler)
    p.take(5).runLast.attemptRun.map(_.exists(_ >= 400.millis.toMillis)) should ===(\/.right(true))
  }

  it should "wait at least the specified time after failure" in {
    val start = System.currentTimeMillis
    val elapsed = counter().flatMap { i =>
      if (i == 1 || i == 4) Task.now(System.currentTimeMillis - start)
      else failedTask
    }

    val p = cache.periodicRefresh(elapsed, 1.millis, 100.milli, strategy, scheduler)
    p.take(2).runLast.attemptRun.map(_.exists(_ >= 300.millis.toMillis)) should ===(\/.right(true))
  }
}

object CacheTest {
  def counter(initial: Int = 0): Task[Int] = {
    var i = initial - 1
    Task.delay{i = i + 1; i}
  }

  def failedTask[A]: Task[A] = Task.fail(new RuntimeException("blargh"))
}
