package io.r2h.engine.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import io.r2h.engine.api.IR2hEngineService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class EngineServiceConnector(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxReconnectAttempts: Int = 5,
    private val initialReconnectDelayMs: Long = 500L,
) {
    private val appContext = context.applicationContext
    private val _connectionState = MutableStateFlow(EngineConnectionState.disconnected("SDK created."))
    val connectionState: StateFlow<EngineConnectionState> = _connectionState.asStateFlow()

    internal val accessor: EngineServiceAccessor = object : EngineServiceAccessor {
        override val connectionState: StateFlow<EngineConnectionState>
            get() = this@EngineServiceConnector.connectionState

        override suspend fun <T> withService(block: suspend (EngineServiceFacade) -> T): EngineClientResult<T> =
            withServiceInternal(block)
    }

    private var serviceFacade: EngineServiceFacade? = null
    private var serviceConnection: ServiceConnection? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private val reconnectScheduled = AtomicBoolean(false)
    private val reconnectPolicy = EngineReconnectPolicy(maxReconnectAttempts, initialReconnectDelayMs)

    fun connect() {
        val current = _connectionState.value.status
        if (current == EngineConnectionStatus.CONNECTED || current == EngineConnectionStatus.CONNECTING) {
            return
        }
        if (!isEngineInstalled()) {
            updateState(
                EngineConnectionStatus.ENGINE_NOT_INSTALLED,
                "R2H AI Engine is not installed.",
                "Package ${EngineContract.ENGINE_PACKAGE} was not found.",
            )
            return
        }
        if (!hasBindPermission()) {
            updateState(
                EngineConnectionStatus.PERMISSION_MISSING,
                "AI Engine bind permission is missing.",
                "Client must declare and be granted ${EngineContract.BIND_PERMISSION}.",
            )
            return
        }

        updateState(EngineConnectionStatus.CONNECTING, "Connecting to R2H AI Engine.", null)
        val connection = createServiceConnection()
        serviceConnection = connection
        val intent = Intent().setComponent(
            ComponentName(EngineContract.ENGINE_PACKAGE, EngineContract.ENGINE_SERVICE_CLASS),
        )
        val bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (security: SecurityException) {
            updateState(
                EngineConnectionStatus.PERMISSION_MISSING,
                "AI Engine bind permission was denied.",
                security.message,
            )
            false
        } catch (throwable: Throwable) {
            updateState(
                EngineConnectionStatus.ERROR,
                "Could not bind to R2H AI Engine.",
                throwable.message,
            )
            false
        }
        if (!bound && _connectionState.value.status == EngineConnectionStatus.CONNECTING) {
            updateState(
                EngineConnectionStatus.ERROR,
                "R2H AI Engine service could not be bound.",
                "bindService returned false for ${EngineContract.ENGINE_SERVICE_CLASS}.",
            )
        }
    }

    fun disconnect() {
        reconnectScheduled.set(false)
        reconnectPolicy.reset()
        stopCurrentBinding("Client requested disconnect.")
        updateState(EngineConnectionStatus.DISCONNECTED, "AI Engine is disconnected.", "Client requested disconnect.")
    }

    private suspend fun <T> withServiceInternal(block: suspend (EngineServiceFacade) -> T): EngineClientResult<T> {
        val service = serviceFacade
            ?: return EngineClientResult.Failure(
                EngineClientError(
                    code = errorCodeForConnection(_connectionState.value.status),
                    message = _connectionState.value.humanReadableMessage,
                    technicalReason = _connectionState.value.technicalReason,
                ),
            )
        return withContext(ioDispatcher) {
            try {
                EngineClientResult.Success(block(service))
            } catch (remote: RemoteException) {
                EngineClientResult.Failure(
                    EngineClientError(
                        code = EngineClientErrorCode.BINDER_DIED,
                        message = "AI Engine Binder call failed.",
                        technicalReason = remote.message,
                    ),
                )
            } catch (security: SecurityException) {
                EngineClientResult.Failure(
                    EngineClientError(
                        code = EngineClientErrorCode.PERMISSION_MISSING,
                        message = "AI Engine permission denied.",
                        technicalReason = security.message,
                    ),
                )
            } catch (throwable: Throwable) {
                EngineClientResult.Failure(
                    EngineClientError(
                        code = EngineClientErrorCode.INTERNAL_ERROR,
                        message = "AI Engine client call failed.",
                        technicalReason = throwable.message,
                    ),
                )
            }
        }
    }

    internal fun handleBinderDiedForTest() {
        handleBinderDied("Synthetic Binder death for test.")
    }

    private fun createServiceConnection(): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val api = IR2hEngineService.Stub.asInterface(service)
                val facade = BinderEngineServiceFacade(api, service)
                val actualVersion = try {
                    facade.getApiVersion()
                } catch (throwable: Throwable) {
                    updateState(
                        EngineConnectionStatus.ERROR,
                        "AI Engine API version could not be read.",
                        throwable.message,
                    )
                    stopCurrentBinding("API version read failed.")
                    return
                }
                if (actualVersion < EngineContract.API_VERSION) {
                    updateState(
                        EngineConnectionStatus.API_VERSION_MISMATCH,
                        "AI Engine API version is too old.",
                        "Required API ${EngineContract.API_VERSION}, actual API $actualVersion.",
                    )
                    stopCurrentBinding("API version mismatch.")
                    return
                }

                val recipient = IBinder.DeathRecipient {
                    handleBinderDied("Binder death recipient fired.")
                }
                try {
                    service.linkToDeath(recipient, 0)
                    deathRecipient = recipient
                } catch (throwable: Throwable) {
                    updateState(
                        EngineConnectionStatus.BINDER_DIED,
                        "AI Engine Binder died during connection.",
                        throwable.message,
                    )
                    scheduleReconnect()
                    return
                }

                serviceFacade = facade
                reconnectPolicy.reset()
                reconnectScheduled.set(false)
                updateState(EngineConnectionStatus.CONNECTED, "R2H AI Engine is connected.", null)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                serviceFacade = null
                updateState(
                    EngineConnectionStatus.DISCONNECTED,
                    "AI Engine service disconnected.",
                    "ServiceConnection.onServiceDisconnected for $name.",
                )
                scheduleReconnect()
            }

            override fun onBindingDied(name: ComponentName) {
                handleBinderDied("ServiceConnection.onBindingDied for $name.")
            }

            override fun onNullBinding(name: ComponentName) {
                serviceFacade = null
                updateState(
                    EngineConnectionStatus.ERROR,
                    "AI Engine returned a null Binder.",
                    "ServiceConnection.onNullBinding for $name.",
                )
                scheduleReconnect()
            }
        }

    private fun handleBinderDied(reason: String) {
        serviceFacade = null
        updateState(EngineConnectionStatus.BINDER_DIED, "AI Engine Binder died; reconnecting.", reason)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delayMs = reconnectPolicy.nextDelayMs()
        if (delayMs == null) {
            updateState(
                EngineConnectionStatus.ERROR,
                "AI Engine reconnect attempts exhausted.",
                "Attempts: ${reconnectPolicy.attemptCount}.",
            )
            return
        }
        if (!reconnectScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(delayMs)
            reconnectScheduled.set(false)
            if (_connectionState.value.status != EngineConnectionStatus.CONNECTED) {
                stopCurrentBinding("Preparing reconnect attempt ${reconnectPolicy.attemptCount}.")
                connect()
            }
        }
    }

    private fun stopCurrentBinding(reason: String) {
        val binder = serviceFacade?.binder
        val recipient = deathRecipient
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        deathRecipient = null
        serviceFacade = null
        val connection = serviceConnection
        if (connection != null) {
            runCatching { appContext.unbindService(connection) }
        }
        serviceConnection = null
        if (reason.isNotBlank()) {
            _connectionState.value = _connectionState.value.copy(technicalReason = reason)
        }
    }

    private fun hasBindPermission(): Boolean =
        appContext.checkSelfPermission(EngineContract.BIND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun isEngineInstalled(): Boolean =
        try {
            appContext.packageManager.getPackageInfo(EngineContract.ENGINE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun updateState(status: EngineConnectionStatus, message: String, reason: String?) {
        _connectionState.value = EngineConnectionState(
            status = status,
            humanReadableMessage = message,
            technicalReason = reason,
        )
    }

    private fun errorCodeForConnection(status: EngineConnectionStatus): EngineClientErrorCode =
        when (status) {
            EngineConnectionStatus.PERMISSION_MISSING -> EngineClientErrorCode.PERMISSION_MISSING
            EngineConnectionStatus.API_VERSION_MISMATCH -> EngineClientErrorCode.API_VERSION_MISMATCH
            EngineConnectionStatus.BINDER_DIED -> EngineClientErrorCode.BINDER_DIED
            else -> EngineClientErrorCode.ENGINE_UNAVAILABLE
        }
}
