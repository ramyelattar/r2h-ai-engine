package io.r2h.engine.core

import io.mockk.mockk
import io.r2h.engine.api.IR2hGenerateCallback
import io.r2h.engine.api.model.GenerateRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestQueueTest {

    private fun makeRequest(id: String): InferenceRequest = InferenceRequest(
        request = GenerateRequest(
            requestId = id,
            modelId = "test-model",
            prompt = "Hello",
            streaming = false,
            sessionConfig = null,
        ),
        callback = mockk<IR2hGenerateCallback>(relaxed = true),
        callerUid = 1000,
    )

    @Test
    fun `tryEnqueue succeeds when queue has capacity`() {
        val queue = RequestQueue()
        assertTrue(queue.tryEnqueue(makeRequest("r1")))
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun `tryEnqueue returns false when queue is at MAX_CAPACITY`() {
        val queue = RequestQueue()
        repeat(RequestQueue.MAX_CAPACITY) { i ->
            assertTrue("slot $i should be accepted", queue.tryEnqueue(makeRequest("r$i")))
        }
        assertFalse(queue.tryEnqueue(makeRequest("overflow")))
    }

    @Test
    fun `pendingCount increments on enqueue`() {
        val queue = RequestQueue()
        queue.tryEnqueue(makeRequest("r1"))
        queue.tryEnqueue(makeRequest("r2"))
        assertEquals(2, queue.pendingCount)
    }

    @Test
    fun `markConsumed decrements pendingCount`() {
        val queue = RequestQueue()
        queue.tryEnqueue(makeRequest("r1"))
        assertEquals(1, queue.pendingCount)
        queue.markConsumed()
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `cancel then isCancelledAndClear returns true and removes entry`() {
        val queue = RequestQueue()
        queue.cancel("r1")
        assertTrue(queue.isCancelledAndClear("r1"))
        // Second call returns false — entry was removed
        assertFalse(queue.isCancelledAndClear("r1"))
    }

    @Test
    fun `isCancelledAndClear returns false for unknown id`() {
        val queue = RequestQueue()
        assertFalse(queue.isCancelledAndClear("does-not-exist"))
    }

    @Test
    fun `cancelled request rejected from queue does not affect other requests`() {
        val queue = RequestQueue()
        queue.tryEnqueue(makeRequest("r1"))
        queue.cancel("r2") // cancel something that was never enqueued
        assertTrue(queue.isCancelledAndClear("r2"))
        assertFalse(queue.isCancelledAndClear("r1")) // r1 was not cancelled
    }
}
