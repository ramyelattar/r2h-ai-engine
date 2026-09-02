package io.r2h.engine

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class EngineUiRefreshExecutorTest {
    @Test
    fun `blocking engine snapshot reads execute on the supplied background dispatcher`() = runBlocking {
        val createdThreads = mutableListOf<Thread>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable).also { createdThreads.add(it) }
        }
        val dispatcher = executor.asCoroutineDispatcher()

        try {
            val callerThread = Thread.currentThread()
            // Capture the executing Thread identity itself. Comparing thread
            // objects (not names) is immune to kotlinx-coroutines appending
            // diagnostic decorations such as " @coroutine#1" to the thread name.
            val executionThread = EngineUiRefreshExecutor(dispatcher).execute {
                Thread.currentThread()
            }

            assertSame(
                "Engine snapshot reads must execute on the supplied dispatcher's thread",
                createdThreads.single(),
                executionThread,
            )
            assertNotSame("Engine snapshot reads must not run on the caller thread", callerThread, executionThread)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
