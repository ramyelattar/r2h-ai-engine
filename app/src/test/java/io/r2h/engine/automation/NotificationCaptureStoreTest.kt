package io.r2h.engine.automation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCaptureStoreTest {
    @Test
    fun `notification with no active scope is discarded without materializing content`() {
        val fixture = Fixture()
        val providerCalls = AtomicInteger()

        val captured = fixture.store.capture(
            packageName = "example.package",
            title = { providerCalls.incrementAndGet(); "title" },
            text = { providerCalls.incrementAndGet(); "text" },
        )

        assertEquals(0, captured)
        assertEquals(0, providerCalls.get())
        assertEquals(0, fixture.store.activeScopeCount)
    }

    @Test
    fun `opened scope exposes only its bounded requested snapshot`() {
        val fixture = Fixture(nowMs = 1_000L)
        assertTrue(fixture.open("scope-1"))

        assertEquals(
            1,
            fixture.store.capture(
                packageName = "example.package",
                title = { "A title" },
                text = { "A body" },
            ),
        )

        assertEquals(
            listOf(CapturedNotification("example.package", "A title", "A body")),
            fixture.store.snapshot("scope-1"),
        )
        assertEquals(
            NotificationCaptureScopeInfo(
                scopeId = "scope-1",
                startedAtElapsedMs = 1_000L,
                expiresAtElapsedMs = 61_000L,
                requestedFields = NotificationCaptureField.entries.toSet(),
                maxRecords = NotificationCaptureStore.MAX_RECORDS_PER_SCOPE,
                active = true,
            ),
            fixture.store.scopeInfo("scope-1"),
        )
    }

    @Test
    fun `close removes content and notifications after close are discarded`() {
        val fixture = Fixture()
        assertTrue(fixture.open("scope-1"))
        fixture.capture("before close", "before close body")

        assertTrue(fixture.store.closeScope("scope-1"))
        assertNull(fixture.store.scopeInfo("scope-1"))
        assertTrue(fixture.store.snapshot("scope-1").isEmpty())

        val calls = AtomicInteger()
        assertEquals(
            0,
            fixture.store.capture(
                packageName = "example.package",
                title = { calls.incrementAndGet(); "after close" },
                text = { calls.incrementAndGet(); "after close body" },
            ),
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun `scheduled monotonic expiry clears content without a real-time sleep`() {
        val fixture = Fixture(nowMs = 5_000L)
        assertTrue(fixture.open("scope-1"))
        fixture.capture("expiring title", "expiring body")

        fixture.advanceBy(NotificationCaptureStore.DEFAULT_SCOPE_TTL_MS - 1L)
        assertEquals(1, fixture.store.snapshot("scope-1").size)

        fixture.advanceBy(1L)
        assertNull(fixture.store.scopeInfo("scope-1"))
        assertTrue(fixture.store.snapshot("scope-1").isEmpty())
        assertEquals(0, fixture.store.activeScopeCount)
    }

    @Test
    fun `record count and every retained field are bounded`() {
        val fixture = Fixture()
        assertTrue(fixture.open("scope-1"))

        repeat(12) { index ->
            fixture.store.capture(
                packageName = "p".repeat(400),
                title = { "title-$index-" + "t".repeat(200) },
                text = { "text-$index-" + "x".repeat(400) },
            )
        }

        val snapshot = fixture.store.snapshot("scope-1")
        assertEquals(NotificationCaptureStore.MAX_RECORDS_PER_SCOPE, snapshot.size)
        assertTrue(snapshot.first().title.startsWith("title-11-"))
        snapshot.forEach { item ->
            assertTrue(item.packageName.length <= NotificationCaptureStore.MAX_PACKAGE_CHARS)
            assertTrue(item.title.length <= NotificationCaptureStore.MAX_TITLE_CHARS)
            assertTrue(item.text.length <= NotificationCaptureStore.MAX_TEXT_CHARS)
        }
    }

    @Test
    fun `only requested fields are materialized`() {
        val fixture = Fixture()
        assertTrue(
            fixture.store.openScope(
                scopeId = "scope-1",
                requestedFields = setOf(NotificationCaptureField.PACKAGE_NAME, NotificationCaptureField.TITLE),
                maxRecords = 1,
                ttlMs = NotificationCaptureStore.DEFAULT_SCOPE_TTL_MS,
            ),
        )
        val textCalls = AtomicInteger()

        fixture.store.capture(
            packageName = "example.package",
            title = { "needed title" },
            text = { textCalls.incrementAndGet(); "unrequested text" },
        )

        assertEquals(0, textCalls.get())
        assertEquals("", fixture.store.snapshot("scope-1").single().text)
    }

    @Test
    fun `second scope is rejected until the exact active scope closes`() {
        val fixture = Fixture()
        assertTrue(fixture.open("scope-1"))
        assertFalse(fixture.open("scope-2"))
        assertEquals(1, fixture.store.activeScopeCount)

        assertFalse(fixture.store.closeScope("wrong-scope"))
        assertTrue(fixture.store.closeScope("scope-1"))
        assertTrue(fixture.open("scope-2"))
    }

    @Test
    fun `concurrent close and arrival cannot leave retained orphan content`() {
        repeat(50) { index ->
            val fixture = Fixture()
            val scopeId = "scope-$index"
            assertTrue(fixture.open(scopeId))
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val capture = executor.submit<Int> {
                    start.await()
                    fixture.store.capture("example.package", { "title" }, { "text" })
                }
                val close = executor.submit<Boolean> {
                    start.await()
                    fixture.store.closeScope(scopeId)
                }
                start.countDown()
                capture.get(2, TimeUnit.SECONDS)
                assertTrue(close.get(2, TimeUnit.SECONDS))
                assertEquals(0, fixture.store.activeScopeCount)
                assertTrue(fixture.store.snapshot(scopeId).isEmpty())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private class Fixture(nowMs: Long = 100L) {
        private var elapsedMs = nowMs
        private val scheduler = FakeExpiryScheduler { elapsedMs }
        val store = NotificationCaptureStore(
            clock = NotificationMonotonicClock { elapsedMs },
            expiryScheduler = scheduler,
        )

        fun open(scopeId: String): Boolean = store.openScope(
            scopeId = scopeId,
            requestedFields = NotificationCaptureField.entries.toSet(),
            maxRecords = NotificationCaptureStore.MAX_RECORDS_PER_SCOPE,
            ttlMs = NotificationCaptureStore.DEFAULT_SCOPE_TTL_MS,
        )

        fun capture(title: String, text: String) {
            store.capture("example.package", { title }, { text })
        }

        fun advanceBy(deltaMs: Long) {
            elapsedMs += deltaMs
            scheduler.runDue()
        }
    }

    private class FakeExpiryScheduler(
        private val nowMs: () -> Long,
    ) : NotificationExpiryScheduler {
        private data class Task(
            val dueAtMs: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, action: () -> Unit): NotificationExpiryCancellation {
            val task = Task(nowMs() + delayMs, action)
            synchronized(tasks) { tasks += task }
            return NotificationExpiryCancellation { task.cancelled = true }
        }

        fun runDue() {
            val due = synchronized(tasks) {
                tasks.filter { !it.cancelled && it.dueAtMs <= nowMs() }
                    .also { tasks.removeAll(it.toSet()) }
            }
            due.forEach { it.action() }
        }
    }
}
