package io.r2h.engine.core

import java.util.concurrent.ConcurrentHashMap

class DefaultRuntimeRegistry : RuntimeRegistry {
    private val runtimes = ConcurrentHashMap<String, BackendRuntime>()

    override fun register(runtime: BackendRuntime, policy: RegistrationPolicy): RegistrationResult {
        val key = runtime.descriptor.key
        return when (policy) {
            RegistrationPolicy.REJECT_DUPLICATES -> {
                val previous = runtimes.putIfAbsent(key, runtime)
                if (previous == null) RegistrationResult.Success(runtime, RegistrationResult.Action.ADDED)
                else RegistrationResult.Rejected("Runtime key '$key' is already registered.")
            }
            RegistrationPolicy.REPLACE_ON_DUPLICATE -> {
                val previous = runtimes.put(key, runtime)
                RegistrationResult.Success(
                    runtime,
                    if (previous == null) RegistrationResult.Action.ADDED else RegistrationResult.Action.REPLACED,
                )
            }
            RegistrationPolicy.ALLOW_DUPLICATES ->
                RegistrationResult.Rejected("Duplicate runtime keys are not supported in production registry.")
        }
    }

    override fun unregister(key: String): UnregistrationResult =
        if (runtimes.remove(key) == null) UnregistrationResult.NotFound else UnregistrationResult.Success

    override fun getRuntime(key: String): BackendRuntime? = runtimes[key]

    override fun getAllRuntimes(): List<BackendRuntime> = runtimes.values.sortedBy { it.descriptor.key }

    override fun resolveCompatibleRuntimes(model: ModelDescriptor): CompatibilityResolution {
        val all = getAllRuntimes()
        if (all.isEmpty()) return CompatibilityResolution.RegistryEmpty
        val compatible = all.filter { it.assessCompatibility(model) is CompatibilityResult.Compatible }
        return if (compatible.isEmpty()) {
            CompatibilityResolution.NoneCompatible(all.map { it.descriptor.key })
        } else {
            CompatibilityResolution.Resolved(compatible, compatible.first())
        }
    }

    override fun resolveRuntimes(
        model: ModelDescriptor,
        requiredCapabilities: Set<ModelCapability>,
        locality: ExecutionLocality?,
    ): RuntimeResolution {
        val all = getAllRuntimes()
        if (all.isEmpty()) return RuntimeResolution.RegistryEmpty
        val runtime = all.firstOrNull {
            isRuntimeSupported(it.descriptor.key, model, requiredCapabilities, locality) is SupportCheckResult.Supported
        }
        return runtime?.let { RuntimeResolution.Resolved(it, model) }
            ?: RuntimeResolution.NoneFound("No runtime supports model '${model.id}'.")
    }

    override fun isRuntimeSupported(
        runtimeKey: String,
        model: ModelDescriptor,
        requiredCapabilities: Set<ModelCapability>,
        locality: ExecutionLocality?,
    ): SupportCheckResult {
        val runtime = runtimes[runtimeKey] ?: return SupportCheckResult.RuntimeNotFound
        val descriptor = runtime.descriptor
        if (model.backendKey != descriptor.key) {
            return SupportCheckResult.Unsupported("Backend key mismatch.")
        }
        if (model.modelType !in descriptor.supportedModelTypes) {
            return SupportCheckResult.Unsupported("Model type '${model.modelType}' is not supported.")
        }
        val missing = requiredCapabilities - descriptor.supportedCapabilities
        if (missing.isNotEmpty()) return SupportCheckResult.PartiallySupported(missing)
        if (locality != null && locality !in descriptor.supportedLocalities) {
            return SupportCheckResult.Unsupported("Locality '$locality' is not supported.")
        }
        return SupportCheckResult.Supported
    }
}
