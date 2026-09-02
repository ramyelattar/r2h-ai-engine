package io.r2h.engine

sealed interface EngineCallbackEvent<out T> {
    data class Progress<T>(val value: T) : EngineCallbackEvent<T>
    data class Terminal<T>(val value: T) : EngineCallbackEvent<T>
    data class Failed(val reason: String) : EngineCallbackEvent<Nothing>
}

/**
 * Owns client callback lifetime by request ID. The sink is the ViewModel state
 * reducer; Activity and View references are never stored here.
 */
class EngineCallbackRegistry<D, T>(
    private val eventSink: (requestId: String, descriptor: D, event: EngineCallbackEvent<T>) -> Unit,
) {
    private val registrations = LinkedHashMap<String, D>()

    val activeCount: Int
        @Synchronized get() = registrations.size

    @Synchronized
    fun activeRequestIds(): Set<String> = registrations.keys.toSet()

    @Synchronized
    fun register(requestId: String, descriptor: D): Boolean {
        if (requestId.isBlank() || registrations.containsKey(requestId)) return false
        registrations[requestId] = descriptor
        return true
    }

    @Synchronized
    fun deliver(requestId: String, event: EngineCallbackEvent.Progress<T>): Boolean {
        val descriptor = registrations[requestId] ?: return false
        eventSink(requestId, descriptor, event)
        return true
    }

    fun complete(requestId: String, value: T): Boolean =
        terminal(requestId, EngineCallbackEvent.Terminal(value))

    fun fail(requestId: String, reason: String): Boolean =
        terminal(requestId, EngineCallbackEvent.Failed(reason))

    @Synchronized
    fun cancel(requestId: String): Boolean = registrations.remove(requestId) != null

    @Synchronized
    fun failAll(reason: String) {
        val active = registrations.toList().also { registrations.clear() }
        active.forEach { (requestId, descriptor) ->
            eventSink(requestId, descriptor, EngineCallbackEvent.Failed(reason))
        }
    }

    @Synchronized
    private fun terminal(requestId: String, event: EngineCallbackEvent<T>): Boolean {
        val descriptor = registrations.remove(requestId) ?: return false
        eventSink(requestId, descriptor, event)
        return true
    }
}
