package io.r2h.engine.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded FIFO queue for [InferenceRequest] objects.
 *
 * Capacity is fixed at [MAX_CAPACITY]. Callers that exceed capacity receive
 * a [tryEnqueue] return value of `false`; they must convert this immediately
 * to an [io.r2h.engine.api.model.EngineError] with code QUEUE_FULL and deliver
 * it via the request's callback — the request is never silently dropped.
 *
 * Thread safety: all public methods are safe to call from multiple threads
 * simultaneously. The underlying [Channel] and [ConcurrentHashMap] are both
 * thread-safe. [pendingCount] is maintained with an [AtomicInteger].
 */
internal class RequestQueue {

    companion object {
        const val MAX_CAPACITY = 10
    }

    private val channel = Channel<InferenceRequest>(MAX_CAPACITY)
    private val cancelledIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingCounter = AtomicInteger(0)

    /** Approximate count of requests currently queued (not yet consumed). */
    val pendingCount: Int get() = pendingCounter.get()

    /**
     * Attempts to add [request] to the tail of the queue.
     *
     * @return `true` if the request was accepted; `false` if the queue is full.
     */
    fun tryEnqueue(request: InferenceRequest): Boolean {
        val accepted = channel.trySend(request).isSuccess
        if (accepted) pendingCounter.incrementAndGet()
        return accepted
    }

    /**
     * Marks [requestId] as cancelled.
     *
     * If the request is currently queued it will be skipped when consumed.
     * If it is actively being processed, the orchestrator must additionally
     * call [io.r2h.engine.nativebridge.NativeInferenceEngine.cancel].
     */
    fun cancel(requestId: String) {
        cancelledIds.add(requestId)
    }

    /**
     * Returns `true` and removes the entry from the cancelled set if [requestId]
     * was previously cancelled. Returns `false` for unknown IDs.
     *
     * The remove-on-check pattern prevents the set from growing unboundedly.
     */
    fun isCancelledAndClear(requestId: String): Boolean = cancelledIds.remove(requestId)

    /**
     * Decrements the pending counter. Must be called once per request consumed
     * from [receiveChannel], regardless of whether the request was skipped.
     */
    fun markConsumed() {
        pendingCounter.decrementAndGet()
    }

    /**
     * The underlying [ReceiveChannel] consumed by [InferenceOrchestrator].
     * Exposed as internal to keep it within the module boundary.
     */
    internal val receiveChannel: ReceiveChannel<InferenceRequest> = channel
}
