package io.r2h.engine.client

object EngineUiStateMapper {
    fun fromConnection(state: EngineConnectionState): EngineUiState =
        when (state.status) {
            EngineConnectionStatus.CONNECTED -> EngineUiState(
                EngineUiStatus.READY,
                "AI Engine ready",
                state.humanReadableMessage,
                state.technicalReason,
            )
            EngineConnectionStatus.CONNECTING -> EngineUiState(
                EngineUiStatus.CONNECTING,
                "Connecting",
                state.humanReadableMessage,
                state.technicalReason,
            )
            EngineConnectionStatus.ENGINE_NOT_INSTALLED -> EngineUiState(
                EngineUiStatus.ENGINE_MISSING,
                "AI Engine missing",
                state.humanReadableMessage,
                state.technicalReason,
            )
            EngineConnectionStatus.PERMISSION_MISSING -> EngineUiState(
                EngineUiStatus.PERMISSION_MISSING,
                "Permission missing",
                state.humanReadableMessage,
                state.technicalReason,
            )
            EngineConnectionStatus.API_VERSION_MISMATCH -> EngineUiState(
                EngineUiStatus.API_MISMATCH,
                "API mismatch",
                state.humanReadableMessage,
                state.technicalReason,
            )
            EngineConnectionStatus.BINDER_DIED -> EngineUiState(
                EngineUiStatus.BINDER_DIED_RECONNECTING,
                "Reconnecting",
                state.humanReadableMessage,
                state.technicalReason,
            )
            EngineConnectionStatus.ERROR -> EngineUiState(
                EngineUiStatus.INFERENCE_FAILED,
                "AI Engine error",
                state.humanReadableMessage,
                state.technicalReason,
            )
            EngineConnectionStatus.DISCONNECTED -> EngineUiState(
                EngineUiStatus.DISCONNECTED,
                "Disconnected",
                state.humanReadableMessage,
                state.technicalReason,
            )
        }

    fun fromCapability(detail: EngineCapabilityDetail): EngineUiState =
        when (detail.availability) {
            CapabilityAvailability.AVAILABLE -> EngineUiState(
                EngineUiStatus.READY,
                "Capability ready",
                detail.exactReason,
                detail.technicalReason,
            )
            CapabilityAvailability.MODEL_MISSING -> EngineUiState(
                EngineUiStatus.MODEL_MISSING,
                "Model missing",
                detail.exactReason,
                detail.technicalReason,
            )
            CapabilityAvailability.RUNTIME_LOADING -> EngineUiState(
                EngineUiStatus.RUNTIME_LOADING,
                "Runtime loading",
                detail.exactReason,
                detail.technicalReason,
            )
            CapabilityAvailability.RUNTIME_FAILED -> EngineUiState(
                EngineUiStatus.RUNTIME_FAILED,
                "Runtime failed",
                detail.exactReason,
                detail.technicalReason,
            )
            CapabilityAvailability.UNAVAILABLE -> EngineUiState(
                EngineUiStatus.CAPABILITY_UNAVAILABLE,
                "Capability unavailable",
                detail.exactReason,
                detail.technicalReason,
            )
        }

    fun fromError(error: EngineClientError): EngineUiState =
        when (error.code) {
            EngineClientErrorCode.PERMISSION_MISSING -> EngineUiState(
                EngineUiStatus.PERMISSION_MISSING,
                "Permission missing",
                error.message,
                error.technicalReason,
            )
            EngineClientErrorCode.API_VERSION_MISMATCH -> EngineUiState(
                EngineUiStatus.API_MISMATCH,
                "API mismatch",
                error.message,
                error.technicalReason,
            )
            EngineClientErrorCode.CAPABILITY_UNAVAILABLE -> EngineUiState(
                EngineUiStatus.CAPABILITY_UNAVAILABLE,
                "Capability unavailable",
                error.message,
                error.technicalReason,
            )
            EngineClientErrorCode.MODEL_MISSING -> EngineUiState(
                EngineUiStatus.MODEL_MISSING,
                "Model missing",
                error.message,
                error.technicalReason,
            )
            EngineClientErrorCode.RUNTIME_LOADING -> EngineUiState(
                EngineUiStatus.RUNTIME_LOADING,
                "Runtime loading",
                error.message,
                error.technicalReason,
            )
            EngineClientErrorCode.RUNTIME_FAILED -> EngineUiState(
                EngineUiStatus.RUNTIME_FAILED,
                "Runtime failed",
                error.message,
                error.technicalReason,
            )
            EngineClientErrorCode.BINDER_DIED -> EngineUiState(
                EngineUiStatus.BINDER_DIED_RECONNECTING,
                "Reconnecting",
                error.message,
                error.technicalReason,
            )
            EngineClientErrorCode.RATE_LIMITED -> EngineUiState(
                EngineUiStatus.INFERENCE_FAILED,
                "Rate limited",
                error.message,
                error.technicalReason,
            )
            else -> EngineUiState(
                EngineUiStatus.INFERENCE_FAILED,
                "Inference failed",
                error.message,
                error.technicalReason,
            )
        }
}
