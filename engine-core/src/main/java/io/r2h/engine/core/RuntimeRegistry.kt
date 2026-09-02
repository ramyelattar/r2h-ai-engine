package io.r2h.engine.core

interface RuntimeRegistry {
    fun register(
        runtime: BackendRuntime,
        policy: RegistrationPolicy = RegistrationPolicy.REJECT_DUPLICATES,
    ): RegistrationResult

    fun unregister(key: String): UnregistrationResult

    fun getRuntime(key: String): BackendRuntime?

    fun getAllRuntimes(): List<BackendRuntime>

    fun resolveCompatibleRuntimes(model: ModelDescriptor): CompatibilityResolution

    fun resolveRuntimes(
        model: ModelDescriptor,
        requiredCapabilities: Set<ModelCapability>,
        locality: ExecutionLocality? = null,
    ): RuntimeResolution

    fun isRuntimeSupported(
        runtimeKey: String,
        model: ModelDescriptor,
        requiredCapabilities: Set<ModelCapability>,
        locality: ExecutionLocality? = null,
    ): SupportCheckResult
}
