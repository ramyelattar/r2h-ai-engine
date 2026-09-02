package io.r2h.engine.core

import io.r2h.engine.api.model.ClientSessionRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSessionOwnershipTest {

    @Test
    fun `only registering uid can heartbeat and unregister a session`() {
        var now = 1_000L
        val registry = ClientSessionRegistry(clock = { now }, staleTimeoutMs = 30_000L)
        val sessionId = registry.register(registration(), "io.r2h.owner", ownerUid = 1001)

        now = 2_000L
        assertFalse(registry.heartbeat(sessionId, callerUid = 2002))
        assertEquals(1_000L, registry.snapshotLiveSessions().single().lastSeenEpochMs)

        assertTrue(registry.heartbeat(sessionId, callerUid = 1001))
        assertEquals(2_000L, registry.snapshotLiveSessions().single().lastSeenEpochMs)

        assertFalse(registry.unregister(sessionId, callerUid = 2002))
        assertEquals(1, registry.snapshotLiveSessions().size)
        assertTrue(registry.unregister(sessionId, callerUid = 1001))
        assertTrue(registry.snapshotLiveSessions().isEmpty())
    }

    @Test
    fun `unknown session mutations return the same safe result as wrong owner`() {
        val registry = ClientSessionRegistry()
        val sessionId = registry.register(registration(), "io.r2h.owner", ownerUid = 1001)

        assertFalse(registry.heartbeat("unknown", callerUid = 2002))
        assertFalse(registry.heartbeat(sessionId, callerUid = 2002))
        assertFalse(registry.unregister("unknown", callerUid = 2002))
        assertFalse(registry.unregister(sessionId, callerUid = 2002))
    }

    private fun registration() = ClientSessionRegistration(
        packageName = "caller-controlled-name",
        displayName = "Owner",
        protocolVersion = 3,
        capabilities = listOf("TEXT_GENERATION"),
    )
}
