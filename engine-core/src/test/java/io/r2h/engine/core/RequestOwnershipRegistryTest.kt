package io.r2h.engine.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestOwnershipRegistryTest {

    @Test
    fun `owner cancels own request by taking its ownership record`() {
        val registry = RequestOwnershipRegistry<String>()

        assertEquals(
            RequestRegistrationResult.REGISTERED,
            registry.register("request-1", ownerUid = 1001, packageName = "io.r2h.owner", client = "binder-1"),
        )
        assertTrue(registry.isOwnedBy("request-1", 1001))
        assertNotNull(registry.removeIfOwned("request-1", 1001))
        assertFalse(registry.isOwnedBy("request-1", 1001))
    }

    @Test
    fun `different uid cancel is rejected without removing ownership`() {
        val registry = RequestOwnershipRegistry<String>()
        registry.register("request-1", ownerUid = 1001, packageName = "io.r2h.owner", client = "binder-1")

        assertNull(registry.removeIfOwned("request-1", 2002))
        assertTrue(registry.isOwnedBy("request-1", 1001))
    }

    @Test
    fun `unknown request cancel is a safe no-op`() {
        val registry = RequestOwnershipRegistry<String>()

        assertNull(registry.removeIfOwned("unknown", 1001))
    }

    @Test
    fun `terminal cleanup removes ownership and client association`() {
        val registry = RequestOwnershipRegistry<String>()
        registry.register("request-1", ownerUid = 1001, packageName = "io.r2h.owner", client = "binder-1")

        val removed = registry.removeForTerminalEvent("request-1", "binder-1")

        assertEquals("io.r2h.owner", removed?.packageName)
        assertFalse(registry.hasRequestsForClient("binder-1"))
        assertTrue(registry.removeAllForClient("binder-1").isEmpty())
    }

    @Test
    fun `registry rejects entries beyond its fixed bound`() {
        val registry = RequestOwnershipRegistry<String>(maxEntries = 2)
        assertEquals(RequestRegistrationResult.REGISTERED, registry.register("r1", 1, "p1", "b1"))
        assertEquals(RequestRegistrationResult.REGISTERED, registry.register("r2", 2, "p2", "b2"))

        assertEquals(RequestRegistrationResult.CAPACITY_REACHED, registry.register("r3", 3, "p3", "b3"))
    }

    @Test
    fun `concurrent registration creates exactly one owner for a request id`() {
        val registry = RequestOwnershipRegistry<String>()
        val executor = Executors.newFixedThreadPool(32)
        val ready = CountDownLatch(32)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(32)
        val registered = AtomicInteger(0)
        try {
            repeat(32) { index ->
                executor.execute {
                    ready.countDown()
                    start.await()
                    if (registry.register("shared", 10_000 + index, "p$index", "b$index") ==
                        RequestRegistrationResult.REGISTERED
                    ) {
                        registered.incrementAndGet()
                    }
                    finished.countDown()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))

            assertEquals(1, registered.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
