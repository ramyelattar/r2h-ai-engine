package io.r2h.engine.core

internal enum class RequestRegistrationResult {
    REGISTERED,
    DUPLICATE_REQUEST_ID,
    CAPACITY_REACHED,
    CLIENT_UNAVAILABLE,
}

internal data class RequestOwnership<Client>(
    val requestId: String,
    val ownerUid: Int,
    val packageName: String,
    val client: Client,
)

/**
 * Bounded, thread-safe ownership state for accepted legacy generate requests.
 *
 * Ownership is registered before queue publication, removed on every terminal
 * callback/cancellation, and bulk-removed when a callback Binder dies.
 */
internal class RequestOwnershipRegistry<Client>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private val requests = mutableMapOf<String, RequestOwnership<Client>>()
    private val requestIdsByClient = mutableMapOf<Client, MutableSet<String>>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive." }
    }

    fun register(
        requestId: String,
        ownerUid: Int,
        packageName: String,
        client: Client,
    ): RequestRegistrationResult = synchronized(lock) {
        if (requests.containsKey(requestId)) return@synchronized RequestRegistrationResult.DUPLICATE_REQUEST_ID
        if (requests.size >= maxEntries) return@synchronized RequestRegistrationResult.CAPACITY_REACHED

        requests[requestId] = RequestOwnership(requestId, ownerUid, packageName, client)
        requestIdsByClient.getOrPut(client) { mutableSetOf() }.add(requestId)
        RequestRegistrationResult.REGISTERED
    }

    fun isOwnedBy(requestId: String, callerUid: Int): Boolean = synchronized(lock) {
        requests[requestId]?.ownerUid == callerUid
    }

    fun removeIfOwned(requestId: String, callerUid: Int): RequestOwnership<Client>? = synchronized(lock) {
        val ownership = requests[requestId]?.takeIf { it.ownerUid == callerUid }
            ?: return@synchronized null
        removeLocked(ownership)
    }

    fun removeForTerminalEvent(requestId: String, client: Client): RequestOwnership<Client>? = synchronized(lock) {
        val ownership = requests[requestId]?.takeIf { it.client == client }
            ?: return@synchronized null
        removeLocked(ownership)
    }

    fun removeAllForClient(client: Client): List<RequestOwnership<Client>> = synchronized(lock) {
        val requestIds = requestIdsByClient.remove(client)?.toList().orEmpty()
        requestIds.mapNotNull(requests::remove)
    }

    fun hasRequestsForClient(client: Client): Boolean = synchronized(lock) {
        requestIdsByClient[client]?.isNotEmpty() == true
    }

    private fun removeLocked(ownership: RequestOwnership<Client>): RequestOwnership<Client> {
        requests.remove(ownership.requestId)
        requestIdsByClient[ownership.client]?.let { ids ->
            ids.remove(ownership.requestId)
            if (ids.isEmpty()) requestIdsByClient.remove(ownership.client)
        }
        return ownership
    }

    private companion object {
        // The legacy queue holds 10 entries and processes one active request.
        // Extra headroom covers the short reserve-before-enqueue window without
        // allowing attacker-controlled identifiers to grow memory indefinitely.
        const val DEFAULT_MAX_ENTRIES = 32
    }
}
