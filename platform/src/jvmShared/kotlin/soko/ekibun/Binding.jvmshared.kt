package soko.ekibun

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/** JVM 族的归属线程：构造期记下执行器上的那条线程。 */
internal open class ThreadDispatcherImpl(
  name: String,
  delegate: ExecutorCoroutineDispatcher,
) : ThreadDispatcher(name, delegate) {
  val thread: Thread = runBlocking(delegate) { Thread.currentThread() }

  override fun isOnCurrentThread(): Boolean = Thread.currentThread() === thread
}

actual fun createThreadDispatcher(threadName: String): ThreadDispatcher =
  ThreadDispatcherImpl(
    threadName,
    Executors
      .newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName)
      }.asCoroutineDispatcher(),
  )
