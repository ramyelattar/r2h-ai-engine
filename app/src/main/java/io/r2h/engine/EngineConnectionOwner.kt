package io.r2h.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.r2h.engine.api.IR2hEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface EngineBindingListener<S> {
    fun onConnected(service: S)
    fun onDisconnected()
    fun onBindingDied()
}

interface EngineBindingAdapter<S> {
    fun bind(listener: EngineBindingListener<S>): Boolean
    fun unbind(listener: EngineBindingListener<S>)
}

interface EngineConnectionSchedule {
    fun replaceRefreshSequence(action: () -> Unit)
    fun cancelRefreshSequence()
    fun scheduleReconnect(action: () -> Unit)
    fun cancelReconnect()
    fun close()
}

enum class EngineConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    CLOSED,
}

enum class EngineDisconnectReason {
    SERVICE_DISCONNECTED,
    BINDER_DIED,
    MANUAL_RESTART,
}

/**
 * Process-lifetime connection state machine. Repeated Activity lifecycle calls
 * are idempotent and cannot create a second binding or refresh ladder.
 */
class EngineConnectionOwner<S>(
    private val binding: EngineBindingAdapter<S>,
    private val schedule: EngineConnectionSchedule,
    private val onConnected: (S) -> Unit = {},
    private val onDisconnected: (EngineDisconnectReason) -> Unit = {},
    private val onRefresh: () -> Unit = {},
) : AutoCloseable {
    @Volatile
    var state: EngineConnectionState = EngineConnectionState.DISCONNECTED
        private set

    @Volatile
    var service: S? = null
        private set

    private var bound = false

    private val listener = object : EngineBindingListener<S> {
        override fun onConnected(service: S) = handleConnected(service)
        override fun onDisconnected() = handleDisconnected(EngineDisconnectReason.SERVICE_DISCONNECTED)
        override fun onBindingDied() = handleDisconnected(EngineDisconnectReason.BINDER_DIED)
    }

    @Synchronized
    fun ensureConnected() {
        if (state == EngineConnectionState.CLOSED ||
            state == EngineConnectionState.CONNECTING ||
            state == EngineConnectionState.CONNECTED
        ) {
            return
        }
        beginBinding()
    }

    /** Explicitly release the old binder before the UI restarts EngineService. */
    @Synchronized
    fun disconnectForRestart() {
        if (state == EngineConnectionState.CLOSED || state == EngineConnectionState.DISCONNECTED) return
        service = null
        state = EngineConnectionState.DISCONNECTED
        schedule.cancelRefreshSequence()
        schedule.cancelReconnect()
        if (bound) {
            try {
                binding.unbind(listener)
            } catch (_: Throwable) {
                // The service may already have died during the restart request.
            }
            bound = false
        }
        onDisconnected(EngineDisconnectReason.MANUAL_RESTART)
    }

    @Synchronized
    private fun beginBinding() {
        if (state == EngineConnectionState.CLOSED) return
        state = EngineConnectionState.CONNECTING
        val accepted = try {
            binding.bind(listener)
        } catch (_: Throwable) {
            false
        }
        bound = accepted
        if (!accepted) {
            state = EngineConnectionState.DISCONNECTED
            schedule.scheduleReconnect(::reconnect)
        }
    }

    @Synchronized
    private fun reconnect() {
        if (state != EngineConnectionState.DISCONNECTED) return
        beginBinding()
    }

    @Synchronized
    private fun handleConnected(connectedService: S) {
        if (state == EngineConnectionState.CLOSED) return
        bound = true
        service = connectedService
        state = EngineConnectionState.CONNECTED
        schedule.cancelReconnect()
        schedule.replaceRefreshSequence(onRefresh)
        onConnected(connectedService)
    }

    @Synchronized
    private fun handleDisconnected(reason: EngineDisconnectReason) {
        if (state == EngineConnectionState.CLOSED || state == EngineConnectionState.DISCONNECTED) return
        service = null
        state = EngineConnectionState.DISCONNECTED
        schedule.cancelRefreshSequence()
        if (bound) {
            try {
                binding.unbind(listener)
            } catch (_: Throwable) {
                // A dead system binding may already be gone. State still fails closed.
            }
            bound = false
        }
        onDisconnected(reason)
        schedule.scheduleReconnect(::reconnect)
    }

    @Synchronized
    override fun close() {
        if (state == EngineConnectionState.CLOSED) return
        state = EngineConnectionState.CLOSED
        service = null
        if (bound) {
            try {
                binding.unbind(listener)
            } catch (_: Throwable) {
                // Closing remains idempotent even if Android already removed it.
            }
            bound = false
        }
        schedule.close()
    }
}

class CoroutineEngineConnectionSchedule(
    private val scope: CoroutineScope,
    private val refreshDelaysMs: List<Long> = listOf(2_000L, 5_000L, 10_000L, 20_000L),
    private val reconnectDelayMs: Long = 1_000L,
) : EngineConnectionSchedule {
    private var refreshJob: Job? = null
    private var reconnectJob: Job? = null

    @Synchronized
    override fun replaceRefreshSequence(action: () -> Unit) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            refreshDelaysMs.forEach { delayMs ->
                delay(delayMs)
                action()
            }
        }
    }

    @Synchronized
    override fun cancelRefreshSequence() {
        refreshJob?.cancel()
        refreshJob = null
    }

    @Synchronized
    override fun scheduleReconnect(action: () -> Unit) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            action()
        }
    }

    @Synchronized
    override fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    @Synchronized
    override fun close() {
        cancelRefreshSequence()
        cancelReconnect()
    }
}

/** Android binding adapter that retains only the application Context. */
class AndroidEngineBindingAdapter(context: Context) : EngineBindingAdapter<IR2hEngineService> {
    private val appContext = context.applicationContext
    private var activeListener: EngineBindingListener<IR2hEngineService>? = null
    private var activeConnection: ServiceConnection? = null

    @Synchronized
    override fun bind(listener: EngineBindingListener<IR2hEngineService>): Boolean {
        if (activeConnection != null) return false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                listener.onConnected(IR2hEngineService.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                listener.onDisconnected()
            }

            override fun onBindingDied(name: ComponentName) {
                listener.onBindingDied()
            }

            override fun onNullBinding(name: ComponentName) {
                listener.onBindingDied()
            }
        }
        activeListener = listener
        activeConnection = connection
        val accepted = try {
            appContext.bindService(
                Intent(appContext, EngineService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (t: Throwable) {
            activeListener = null
            activeConnection = null
            throw t
        }
        if (!accepted) {
            activeListener = null
            activeConnection = null
        }
        return accepted
    }

    @Synchronized
    override fun unbind(listener: EngineBindingListener<IR2hEngineService>) {
        if (activeListener !== listener) return
        val connection = activeConnection ?: return
        activeListener = null
        activeConnection = null
        appContext.unbindService(connection)
    }
}
