package io.r2h.engine.core

import io.r2h.engine.api.model.ClientSessionRegistration
import io.r2h.engine.api.model.ConnectionState
import io.r2h.engine.api.model.EngineError
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LiveSessionRecord(
    val sessionId: String,
    val ownerUid: Int,
    val packageName: String,
    val displayName: String,
    val protocolVersion: Int,
    val capabilities: List<String>,
    val connectedAtEpochMs: Long,
    var lastSeenEpochMs: Long,
    var activeRequestCount: Int = 0,
    var lastError: EngineError? = null,
)

data class PackageHistoryRecord(
    val packageName: String,
    var displayName: String,
    var connectionState: ConnectionState,
    var lastSeenEpochMs: Long?,
    var protocolVersion: Int?,
    var capabilities: List<String>,
    var lastError: EngineError?,
)

class ClientSessionRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleTimeoutMs: Long = 30_000L,
) {
    private val sessions = ConcurrentHashMap<String, LiveSessionRecord>()
    private val packageHistory = ConcurrentHashMap<String, PackageHistoryRecord>()

    fun register(
        registration: ClientSessionRegistration,
        verifiedPackageName: String,
        ownerUid: Int,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val now = clock()
        val session = LiveSessionRecord(
            sessionId = sessionId,
            ownerUid = ownerUid,
            packageName = verifiedPackageName,
            displayName = registration.displayName,
            protocolVersion = registration.protocolVersion,
            capabilities = registration.capabilities,
            connectedAtEpochMs = now,
            lastSeenEpochMs = now,
        )
        sessions[sessionId] = session
        updateHistory(
            packageName = verifiedPackageName,
            displayName = registration.displayName,
            protocolVersion = registration.protocolVersion,
            capabilities = registration.capabilities,
            connectionState = ConnectionState.CONNECTED,
            lastSeenEpochMs = now,
        )
        return sessionId
    }

    fun heartbeat(sessionId: String, callerUid: Int): Boolean {
        var updated = false
        sessions.computeIfPresent(sessionId) { _, session ->
            if (session.ownerUid == callerUid) {
                session.lastSeenEpochMs = clock()
                updated = true
            }
            session
        }
        return updated
    }

    fun unregister(sessionId: String, callerUid: Int): Boolean {
        var removed: LiveSessionRecord? = null
        sessions.computeIfPresent(sessionId) { _, session ->
            if (session.ownerUid == callerUid) {
                removed = session
                null
            } else {
                session
            }
        }
        removed?.let { session ->
            updateHistory(
                packageName = session.packageName,
                displayName = session.displayName,
                connectionState = ConnectionState.STALE,
                lastSeenEpochMs = session.lastSeenEpochMs,
            )
        }
        return removed != null
    }

    fun recordCallerActivity(
        packageName: String,
        displayName: String = packageName,
        capability: String,
        protocolVersion: Int? = null,
    ) {
        updateHistory(
            packageName = packageName,
            displayName = displayName,
            connectionState = ConnectionState.CONNECTED,
            lastSeenEpochMs = clock(),
            protocolVersion = protocolVersion,
        )
    }

    fun recordRequestStarted(packageName: String) {
        sessions.values
            .filter { it.packageName == packageName }
            .forEach { it.activeRequestCount++ }
    }

    fun recordRequestFinished(packageName: String) {
        sessions.values
            .filter { it.packageName == packageName }
            .forEach { if (it.activeRequestCount > 0) it.activeRequestCount-- }
    }

    fun snapshotLiveSessions(): List<LiveSessionRecord> {
        val now = clock()
        return sessions.values
            .filter { now - it.lastSeenEpochMs <= staleTimeoutMs }
            .toList()
    }

    fun snapshotPackageHistory(): Map<String, PackageHistoryRecord> = packageHistory.toMap()

    private fun updateHistory(
        packageName: String,
        displayName: String = packageName,
        connectionState: ConnectionState,
        lastSeenEpochMs: Long? = null,
        protocolVersion: Int? = null,
        capabilities: List<String>? = null,
    ) {
        packageHistory.compute(packageName) { _, existing ->
            existing?.apply {
                this.displayName = displayName
                this.connectionState = connectionState
                lastSeenEpochMs?.let { this.lastSeenEpochMs = it }
                protocolVersion?.let { this.protocolVersion = it }
                capabilities?.let { this.capabilities = it }
            } ?: PackageHistoryRecord(
                packageName = packageName,
                displayName = displayName,
                connectionState = connectionState,
                lastSeenEpochMs = lastSeenEpochMs,
                protocolVersion = protocolVersion,
                capabilities = capabilities ?: emptyList(),
                lastError = null,
            )
        }
    }
}
