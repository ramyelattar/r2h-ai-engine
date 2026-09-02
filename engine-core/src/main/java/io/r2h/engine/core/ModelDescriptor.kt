package io.r2h.engine.core

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val version: String = "1.0",
    val modelType: ModelType,
    val capabilities: Set<ModelCapability>,
    val backendKey: String,
    val modality: Modality = Modality.TEXT,
    val supportedTaskTypes: Set<TaskType> = emptySet(),
    val taskTags: Set<String> = emptySet(),
    val source: Source = Source.Unknown,
    val executionTarget: ExecutionTarget = ExecutionTarget.LOCAL,
    val isEnabled: Boolean = true,
    val isSelectable: Boolean = true,
    val maturity: Maturity = Maturity.PRODUCTION,
    val constraints: Constraints = Constraints(),
    val metadata: Map<String, String> = emptyMap(),
) {
    sealed interface Source {
        data class Local(
            val artifactRef: String,
            val format: String,
            val sizeBytes: Long = 0L,
        ) : Source
        data class Remote(val url: String) : Source
        data object Unknown : Source
    }

    enum class ExecutionTarget { LOCAL, REMOTE, HYBRID }

    enum class Maturity { EXPERIMENTAL, BETA, PRODUCTION }

    data class Constraints(
        val maxContextWindow: Int = 2048,
        val maxBatchSize: Int = 1,
    )
}
