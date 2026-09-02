package io.r2h.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs synchronous Binder reads and local repository reads away from the UI
 * thread. The returned value is delivered back to the caller coroutine.
 */
internal class EngineUiRefreshExecutor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun <T> execute(block: suspend () -> T): T =
        withContext(dispatcher) {
            block()
        }
}