package sbt.cozyfixture

import sbt._
import sbt.std.Transform

/*
 * @since   Oct. 5, 2026
 * @version Oct. 5, 2026
 * @author  ASAMI, Tomoharu
 */
object CozyTaskEvaluation {
  def run[A](task: Task[A]): Result[A] = {
    val engine = new Execute[Task](
      Execute.config(checkCycles = true),
      Execute.noTriggers[Task],
      ExecuteProgress.empty[Task]
    )(Transform(Transform.DummyTaskMap(Nil)))
    val (service, shutdown) = CompletionService[Task[_], Completed](1)
    try engine.run(task)(service)
    finally shutdown()
  }
}
