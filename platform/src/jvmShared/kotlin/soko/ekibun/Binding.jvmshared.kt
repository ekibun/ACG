package soko.ekibun

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * 「JVM 族」的归属线程 —— Android 与桌面 JVM 共用这一份。
 *
 * [ThreadDispatcher] 只管归属线程那套语义，「怎么起一条独占线程」与「怎么认出当前就是这条
 * 线程」全在这两条实现里。`CoroutineDispatcher` 没有反查自己线程的接口，所以 [thread] 的
 * 唯一取法是构造期往执行器上投递一次。
 *
 * 要加一族平台（`nativeMain` 之类）就复制**这个文件**：把 `Thread.currentThread()` 换成
 * 那个平台的执行单元即可，`commonMain` 一个字不动。
 */
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
