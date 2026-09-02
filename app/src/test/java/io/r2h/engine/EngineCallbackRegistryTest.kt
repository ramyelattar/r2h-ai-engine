package io.r2h.engine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCallbackRegistryTest {
    @Test
    fun `one request id has one active callback registration`() {
        val registry = EngineCallbackRegistry<String, String> { _, _, _ -> }

        assertTrue(registry.register("request-1", "chat"))
        assertFalse(registry.register("request-1", "replacement"))
        assertEquals(1, registry.activeCount)
        assertEquals(setOf("request-1"), registry.activeRequestIds())
    }

    @Test
    fun `terminal event is delivered once and removes registration`() {
        val events = mutableListOf<Triple<String, String, EngineCallbackEvent<String>>>()
        val registry = EngineCallbackRegistry<String, String> { id, descriptor, event ->
            events += Triple(id, descriptor, event)
        }
        registry.register("request-1", "chat")

        assertTrue(registry.deliver("request-1", EngineCallbackEvent.Progress("hello")))
        assertTrue(registry.complete("request-1", "done"))
        assertFalse(registry.complete("request-1", "duplicate"))
        assertFalse(registry.deliver("request-1", EngineCallbackEvent.Progress("late")))

        assertEquals(2, events.size)
        assertEquals(0, registry.activeCount)
        assertEquals(EngineCallbackEvent.Terminal("done"), events.last().third)
    }

    @Test
    fun `synchronous dispatch failure removes registration`() {
        val events = mutableListOf<EngineCallbackEvent<String>>()
        val registry = EngineCallbackRegistry<String, String> { _, _, event -> events += event }
        registry.register("request-1", "studio")

        assertTrue(registry.fail("request-1", "dispatch failed"))
        assertEquals(0, registry.activeCount)
        assertEquals(EngineCallbackEvent.Failed("dispatch failed"), events.single())
    }

    @Test
    fun `binder death fails every active callback exactly once`() {
        val events = mutableListOf<Pair<String, EngineCallbackEvent<String>>>()
        val registry = EngineCallbackRegistry<String, String> { id, _, event -> events += id to event }
        registry.register("request-1", "chat")
        registry.register("request-2", "studio")

        registry.failAll("binder died")
        registry.failAll("duplicate death")

        assertEquals(0, registry.activeCount)
        assertEquals(2, events.size)
        assertEquals(setOf("request-1", "request-2"), events.map { it.first }.toSet())
        assertTrue(events.all { it.second == EngineCallbackEvent.Failed("binder died") })
    }

    @Test
    fun `explicit cancellation removes callback before late remote events`() {
        val events = mutableListOf<EngineCallbackEvent<String>>()
        val registry = EngineCallbackRegistry<String, String> { _, _, event -> events += event }
        registry.register("request-1", "chat")

        assertTrue(registry.cancel("request-1"))
        assertFalse(registry.deliver("request-1", EngineCallbackEvent.Progress("late")))

        assertEquals(0, registry.activeCount)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `concurrent terminal delivery cannot overtake in-flight progress`() {
        val progressEntered = CountDownLatch(1)
        val releaseProgress = CountDownLatch(1)
        val terminalAttempted = CountDownLatch(1)
        val terminalEntered = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val registry = EngineCallbackRegistry<String, String> { _, _, event ->
            when (event) {
                is EngineCallbackEvent.Progress -> {
                    progressEntered.countDown()
                    check(releaseProgress.await(5, TimeUnit.SECONDS))
                    events += "progress"
                }
                is EngineCallbackEvent.Terminal -> {
                    events += "terminal"
                    terminalEntered.countDown()
                }
                is EngineCallbackEvent.Failed -> Unit
            }
        }
        registry.register("request-1", "chat")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val progress = executor.submit<Boolean> {
                registry.deliver("request-1", EngineCallbackEvent.Progress("token"))
            }
            assertTrue(progressEntered.await(5, TimeUnit.SECONDS))
            val terminal = executor.submit<Boolean> {
                terminalAttempted.countDown()
                registry.complete("request-1", "done")
            }
            assertTrue(terminalAttempted.await(5, TimeUnit.SECONDS))

            try {
                assertFalse(
                    "Terminal delivery must wait for an already accepted progress event",
                    terminalEntered.await(500, TimeUnit.MILLISECONDS),
                )
            } finally {
                releaseProgress.countDown()
            }

            assertTrue(progress.get(5, TimeUnit.SECONDS))
            assertTrue(terminal.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("progress", "terminal"), events)
            assertEquals(0, registry.activeCount)
        } finally {
            releaseProgress.countDown()
            executor.shutdownNow()
        }
    }
}
