package io.r2h.engine

import io.r2h.engine.api.model.EngineTruthSnapshot
import io.r2h.engine.api.model.ModelInfo
import io.r2h.engine.model.LocalModelScanResult
import io.r2h.engine.tools.ToolRiskLevel
import java.nio.charset.StandardCharsets

enum class ProductSection(val label: String, val subtitle: String) {
    CHAT("Chat", "Private on-device AI runtime."),
    STUDIO("Studio", "Local capability workspace."),
    AGENT("Agent", "On-device task execution."),
    SETTINGS("Settings", "Models, status, apps, and diagnostics."),
    MODELS("Models", "Catalog, repository, load state, and readiness."),
    STATUS("Status", "Readable runtime dashboard with technical details separated."),
    CONNECTED_APPS("Connected Apps", "Client integrations and live sessions."),
    AGENT_PERMISSIONS("Agent Permissions", "Permission Center for local tools and special access."),
    TOOL_AUDIT_LOG("Tool Audit Log", "Recent local tool calls, approvals, and raw details."),
    DIAGNOSTICS("Diagnostics", "Developer tools, model import, raw snapshot, and logs."),
}

data class RuntimeUiState(
    val connected: Boolean = false,
    val apiVersion: Int? = null,
    val loadedModelId: String? = null,
    val truth: EngineTruthSnapshot? = null,
    val serviceModels: List<ModelInfo> = emptyList(),
    val localModels: List<ModelInfo> = emptyList(),
    val loadState: String? = null,
    val loadModelId: String? = null,
    val loadProgress: Int? = null,
    val loadError: String? = null,
    val errorMessage: String? = null,
)

enum class ApprovalStatus {
    PENDING,
    EXECUTING,
}

enum class ApprovalActionType {
    TORCH_ON,
    TORCH_OFF,
    NOTIFICATION_SUMMARY,
    CALENDAR_INSERT,
    CONTACT_LOOKUP,
    ACCESSIBILITY_PREVIEW,
}

data class ApprovalActionDescriptor(
    val type: ApprovalActionType,
    val argument: String = "",
)

data class PendingApprovalState(
    val approvalId: String,
    val toolId: String,
    val title: String,
    val detail: String,
    val risk: ToolRiskLevel,
    val action: ApprovalActionDescriptor,
    val status: ApprovalStatus = ApprovalStatus.PENDING,
)

enum class StudioDocumentActionType {
    ANALYZE_IMAGE,
    OCR,
    ANALYZE_AUDIO,
    ANALYZE_VIDEO,
}

/** Controls only process SavedState encoding; live ViewModel/UI state is unchanged. */
enum class UiContentPersistence {
    PERSISTABLE,
    VOLATILE,
}

data class PendingStudioDocumentState(
    val requestId: String,
    val title: String,
    val destinationName: String,
    val actionType: StudioDocumentActionType,
)

data class MainUiState(
    val currentSection: ProductSection = ProductSection.CHAT,
    val selectedModelId: String? = null,
    val showTechnicalDetails: Boolean = false,
    val lastPromptText: String = "",
    val lastGenerationText: String = "",
    val lastGenerationMeta: String = "",
    val lastGenerationError: String? = null,
    val lastStudioWorkflowTitle: String = "Studio Workspace",
    val lastStudioResultPreview: String = "Run a sample workflow to show a local result preview here.",
    val activeStudioWorkflowTitle: String? = null,
    val lastAgentTask: String = "Calculate 17 * 23 and explain the result.",
    val lastAgentTimeline: List<String> = listOf("Ready for a local task."),
    val lastAgentToolSummary: String = "Calculator, engine status inspector, and text planner are available.",
    val lastAgentResult: String = "Run a local task to show the final result in this workspace.",
    val lastAgentResultPersistence: UiContentPersistence = UiContentPersistence.PERSISTABLE,
    val lastAgentError: String? = null,
    val lastAgentMissingPermissionId: String? = null,
    val pendingApproval: PendingApprovalState? = null,
    val pendingRuntimePermissionId: String? = null,
    val pendingStudioDocument: PendingStudioDocumentState? = null,
    val diagnosticsText: String = "",
    val localModelManagerMessage: String? = null,
    val localModelScanResult: LocalModelScanResult = LocalModelScanResult.NoFolderSelected,
    val localModelManagerBusy: Boolean = false,
    val isGenerating: Boolean = false,
    val isAgentRunning: Boolean = false,
    val torchEnabled: Boolean = false,
    val runtime: RuntimeUiState = RuntimeUiState(errorMessage = "Starting engine service..."),
)

object MainStateLimits {
    const val TRANSCRIPT_CHARS = 32_768
    const val PROMPT_CHARS = 8_192
    const val RESULT_CHARS = 16_384
    const val APPROVAL_TITLE_CHARS = 512
    const val APPROVAL_DETAIL_CHARS = 4_096
    const val APPROVAL_ARGUMENT_CHARS = 4_096
    const val IDENTIFIER_CHARS = 512
    const val SAVED_STATE_TARGET_BYTES = 128 * 1024
    const val SAVED_STATE_HARD_BYTES = 192 * 1024
}

data class EncodedMainSavedState(
    val values: Map<String, String>,
    val serializedSizeBytes: Int,
)

/**
 * Fixed-schema persistence adapter used by MainActivityViewModel's SavedStateHandle.
 * Live engine/callback/view objects are intentionally absent from this schema.
 */
class MainSavedStateCodec {
    fun encode(state: MainUiState): EncodedMainSavedState {
        val values = linkedMapOf(
            KEY_SECTION to state.currentSection.name,
            KEY_SELECTED_MODEL to fit(state.selectedModelId.orEmpty(), MainStateLimits.IDENTIFIER_CHARS, 2 * 1024),
            KEY_TECHNICAL_DETAILS to state.showTechnicalDetails.toString(),
            KEY_PROMPT to fit(state.lastPromptText, MainStateLimits.PROMPT_CHARS, 16 * 1024),
            KEY_TRANSCRIPT to fit(state.lastGenerationText, MainStateLimits.TRANSCRIPT_CHARS, 64 * 1024, keepTail = true),
            KEY_GENERATION_META to fit(state.lastGenerationMeta, MainStateLimits.RESULT_CHARS, 4 * 1024),
            KEY_GENERATION_ERROR to fit(state.lastGenerationError.orEmpty(), MainStateLimits.RESULT_CHARS, 4 * 1024),
            KEY_STUDIO_TITLE to fit(state.lastStudioWorkflowTitle, 2_048, 2 * 1024),
            KEY_STUDIO_RESULT to fit(state.lastStudioResultPreview, MainStateLimits.RESULT_CHARS, 12 * 1024),
            KEY_STUDIO_ACTIVE_TITLE to fit(state.activeStudioWorkflowTitle.orEmpty(), 2_048, 2 * 1024),
            KEY_AGENT_TASK to fit(state.lastAgentTask, MainStateLimits.PROMPT_CHARS, 8 * 1024),
            KEY_AGENT_TIMELINE to fit(state.lastAgentTimeline.joinToString(TIMELINE_SEPARATOR), MainStateLimits.RESULT_CHARS, 4 * 1024),
            KEY_AGENT_TOOL_SUMMARY to fit(state.lastAgentToolSummary, MainStateLimits.RESULT_CHARS, 8 * 1024),
            KEY_AGENT_RESULT to if (state.lastAgentResultPersistence == UiContentPersistence.PERSISTABLE) {
                fit(state.lastAgentResult, MainStateLimits.RESULT_CHARS, 12 * 1024)
            } else {
                ""
            },
            KEY_AGENT_ERROR to fit(state.lastAgentError.orEmpty(), MainStateLimits.RESULT_CHARS, 4 * 1024),
            KEY_AGENT_PERMISSION to fit(state.lastAgentMissingPermissionId.orEmpty(), MainStateLimits.IDENTIFIER_CHARS, 2 * 1024),
            KEY_RUNTIME_PERMISSION to fit(state.pendingRuntimePermissionId.orEmpty(), MainStateLimits.IDENTIFIER_CHARS, 2 * 1024),
            KEY_DIAGNOSTICS to fit(state.diagnosticsText, MainStateLimits.RESULT_CHARS, 8 * 1024),
            KEY_LOCAL_MODEL_MESSAGE to fit(state.localModelManagerMessage.orEmpty(), MainStateLimits.RESULT_CHARS, 4 * 1024),
            KEY_APPROVAL_ID to fit(state.pendingApproval?.approvalId.orEmpty(), MainStateLimits.IDENTIFIER_CHARS, 2 * 1024),
            KEY_APPROVAL_TOOL to fit(state.pendingApproval?.toolId.orEmpty(), MainStateLimits.IDENTIFIER_CHARS, 2 * 1024),
            KEY_APPROVAL_TITLE to fit(state.pendingApproval?.title.orEmpty(), MainStateLimits.APPROVAL_TITLE_CHARS, 2 * 1024),
            KEY_APPROVAL_DETAIL to fit(state.pendingApproval?.detail.orEmpty(), MainStateLimits.APPROVAL_DETAIL_CHARS, 8 * 1024),
            KEY_APPROVAL_RISK to state.pendingApproval?.risk?.name.orEmpty(),
            KEY_APPROVAL_ACTION to state.pendingApproval?.action?.type?.name.orEmpty(),
            KEY_APPROVAL_ARGUMENT to fit(state.pendingApproval?.action?.argument.orEmpty(), MainStateLimits.APPROVAL_ARGUMENT_CHARS, 8 * 1024),
            KEY_APPROVAL_STATUS to state.pendingApproval?.status?.name.orEmpty(),
            KEY_DOCUMENT_ID to fit(state.pendingStudioDocument?.requestId.orEmpty(), MainStateLimits.IDENTIFIER_CHARS, 2 * 1024),
            KEY_DOCUMENT_TITLE to fit(state.pendingStudioDocument?.title.orEmpty(), 2_048, 2 * 1024),
            KEY_DOCUMENT_DESTINATION to fit(state.pendingStudioDocument?.destinationName.orEmpty(), MainStateLimits.IDENTIFIER_CHARS, 2 * 1024),
            KEY_DOCUMENT_ACTION to state.pendingStudioDocument?.actionType?.name.orEmpty(),
        )

        trimToTarget(values)
        var size = serializedSize(values)
        if (size > MainStateLimits.SAVED_STATE_HARD_BYTES) {
            // Fail closed on persistence size: retain only minimal navigation and
            // approval identity/action state, never an oversized Bundle payload.
            val keep = setOf(
                KEY_SECTION,
                KEY_SELECTED_MODEL,
                KEY_APPROVAL_ID,
                KEY_APPROVAL_TOOL,
                KEY_APPROVAL_TITLE,
                KEY_APPROVAL_RISK,
                KEY_APPROVAL_ACTION,
                KEY_APPROVAL_STATUS,
            )
            values.keys.forEach { key -> if (key !in keep) values[key] = "" }
            size = serializedSize(values)
        }
        check(size <= MainStateLimits.SAVED_STATE_HARD_BYTES) {
            "Saved UI state exceeds the ${MainStateLimits.SAVED_STATE_HARD_BYTES}-byte hard ceiling"
        }
        return EncodedMainSavedState(values.toMap(), size)
    }

    fun decode(values: Map<String, Any?>): MainUiState {
        fun value(key: String): String = values[key] as? String ?: ""
        val pendingApproval = decodeApproval(::value)
        val pendingDocument = decodeDocument(::value)
        return MainUiState(
            currentSection = enumOrNull<ProductSection>(value(KEY_SECTION)) ?: ProductSection.CHAT,
            selectedModelId = value(KEY_SELECTED_MODEL).ifBlank { null },
            showTechnicalDetails = value(KEY_TECHNICAL_DETAILS).toBooleanStrictOrNull() ?: false,
            lastPromptText = fit(value(KEY_PROMPT), MainStateLimits.PROMPT_CHARS, 16 * 1024),
            lastGenerationText = fit(value(KEY_TRANSCRIPT), MainStateLimits.TRANSCRIPT_CHARS, 64 * 1024, keepTail = true),
            lastGenerationMeta = fit(value(KEY_GENERATION_META), MainStateLimits.RESULT_CHARS, 4 * 1024),
            lastGenerationError = value(KEY_GENERATION_ERROR).ifBlank { null },
            lastStudioWorkflowTitle = value(KEY_STUDIO_TITLE).ifBlank { "Studio Workspace" },
            lastStudioResultPreview = value(KEY_STUDIO_RESULT).ifBlank {
                "Run a sample workflow to show a local result preview here."
            },
            activeStudioWorkflowTitle = value(KEY_STUDIO_ACTIVE_TITLE).ifBlank { null },
            lastAgentTask = value(KEY_AGENT_TASK).ifBlank { "Calculate 17 * 23 and explain the result." },
            lastAgentTimeline = value(KEY_AGENT_TIMELINE).takeIf { it.isNotBlank() }
                ?.split(TIMELINE_SEPARATOR)
                ?: listOf("Ready for a local task."),
            lastAgentToolSummary = value(KEY_AGENT_TOOL_SUMMARY).ifBlank {
                "Calculator, engine status inspector, and text planner are available."
            },
            lastAgentResult = value(KEY_AGENT_RESULT).ifBlank {
                "Run a local task to show the final result in this workspace."
            },
            lastAgentError = value(KEY_AGENT_ERROR).ifBlank { null },
            lastAgentMissingPermissionId = value(KEY_AGENT_PERMISSION).ifBlank { null },
            pendingApproval = pendingApproval,
            pendingRuntimePermissionId = value(KEY_RUNTIME_PERMISSION).ifBlank { null },
            pendingStudioDocument = pendingDocument,
            diagnosticsText = value(KEY_DIAGNOSTICS),
            localModelManagerMessage = value(KEY_LOCAL_MODEL_MESSAGE).ifBlank { null },
            // Live execution and engine truth are deliberately never restored.
            isGenerating = false,
            isAgentRunning = false,
            runtime = RuntimeUiState(errorMessage = "Starting engine service..."),
        )
    }

    private fun decodeApproval(value: (String) -> String): PendingApprovalState? {
        val id = value(KEY_APPROVAL_ID)
        val tool = value(KEY_APPROVAL_TOOL)
        val title = value(KEY_APPROVAL_TITLE)
        val risk = enumOrNull<ToolRiskLevel>(value(KEY_APPROVAL_RISK))
        val action = enumOrNull<ApprovalActionType>(value(KEY_APPROVAL_ACTION))
        val status = enumOrNull<ApprovalStatus>(value(KEY_APPROVAL_STATUS))
        if (id.isBlank() || tool.isBlank() || title.isBlank() || risk == null || action == null || status == null) {
            return null
        }
        return PendingApprovalState(
            approvalId = id,
            toolId = tool,
            title = title,
            detail = value(KEY_APPROVAL_DETAIL),
            risk = risk,
            action = ApprovalActionDescriptor(action, value(KEY_APPROVAL_ARGUMENT)),
            status = status,
        )
    }

    private fun decodeDocument(value: (String) -> String): PendingStudioDocumentState? {
        val id = value(KEY_DOCUMENT_ID)
        val title = value(KEY_DOCUMENT_TITLE)
        val destination = value(KEY_DOCUMENT_DESTINATION)
        val action = enumOrNull<StudioDocumentActionType>(value(KEY_DOCUMENT_ACTION))
        if (id.isBlank() || title.isBlank() || destination.isBlank() || action == null) return null
        return PendingStudioDocumentState(id, title, destination, action)
    }

    private fun trimToTarget(values: MutableMap<String, String>) {
        val trimOrder = listOf(
            KEY_DIAGNOSTICS,
            KEY_GENERATION_META,
            KEY_GENERATION_ERROR,
            KEY_LOCAL_MODEL_MESSAGE,
            KEY_AGENT_TIMELINE,
            KEY_AGENT_TOOL_SUMMARY,
            KEY_STUDIO_RESULT,
            KEY_AGENT_RESULT,
            KEY_AGENT_ERROR,
            KEY_PROMPT,
            KEY_APPROVAL_DETAIL,
            KEY_APPROVAL_ARGUMENT,
            KEY_TRANSCRIPT,
        )
        for (key in trimOrder) {
            val excess = serializedSize(values) - MainStateLimits.SAVED_STATE_TARGET_BYTES
            if (excess <= 0) return
            val current = values.getValue(key)
            val currentBytes = utf8Bytes(current)
            val targetBytes = (currentBytes - excess).coerceAtLeast(0)
            values[key] = fit(current, current.length, targetBytes, keepTail = key == KEY_TRANSCRIPT)
        }
        if (serializedSize(values) > MainStateLimits.SAVED_STATE_TARGET_BYTES) {
            // This is a schema-growth guard. Emptying optional text keeps the
            // fixed identity/navigation fields while guaranteeing the target.
            values.keys.forEach { key ->
                if (key !in REQUIRED_KEYS) values[key] = ""
            }
        }
    }

    private fun serializedSize(values: Map<String, String>): Int =
        SERIALIZED_HEADER_BYTES + values.entries.sumOf { (key, value) ->
            SERIALIZED_ENTRY_OVERHEAD_BYTES + utf8Bytes(key) + utf8Bytes(value)
        }

    private fun fit(value: String, maxChars: Int, maxBytes: Int, keepTail: Boolean = false): String {
        if (maxChars <= 0 || maxBytes <= 0 || value.isEmpty()) return ""
        var bounded = if (value.length <= maxChars) value else if (keepTail) value.takeLast(maxChars) else value.take(maxChars)
        bounded = repairSurrogateBoundary(bounded, keepTail)
        if (utf8Bytes(bounded) <= maxBytes) return bounded

        var low = 0
        var high = bounded.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            val candidate = if (keepTail) bounded.takeLast(mid) else bounded.take(mid)
            val repaired = repairSurrogateBoundary(candidate, keepTail)
            if (utf8Bytes(repaired) <= maxBytes) low = mid else high = mid - 1
        }
        val result = if (keepTail) bounded.takeLast(low) else bounded.take(low)
        return repairSurrogateBoundary(result, keepTail)
    }

    private fun repairSurrogateBoundary(value: String, keepTail: Boolean): String {
        if (value.isEmpty()) return value
        return if (keepTail && value.first().isLowSurrogate()) {
            value.drop(1)
        } else if (!keepTail && value.last().isHighSurrogate()) {
            value.dropLast(1)
        } else {
            value
        }
    }

    private fun utf8Bytes(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    companion object {
        private const val SERIALIZED_HEADER_BYTES = 32
        private const val SERIALIZED_ENTRY_OVERHEAD_BYTES = 64
        private const val TIMELINE_SEPARATOR = "\u001e"

        private const val KEY_SECTION = "section"
        private const val KEY_SELECTED_MODEL = "selectedModelId"
        private const val KEY_TECHNICAL_DETAILS = "showTechnicalDetails"
        private const val KEY_PROMPT = "lastPromptText"
        private const val KEY_TRANSCRIPT = "lastGenerationText"
        private const val KEY_GENERATION_META = "lastGenerationMeta"
        private const val KEY_GENERATION_ERROR = "lastGenerationError"
        private const val KEY_STUDIO_TITLE = "lastStudioWorkflowTitle"
        private const val KEY_STUDIO_RESULT = "lastStudioResultPreview"
        private const val KEY_STUDIO_ACTIVE_TITLE = "activeStudioWorkflowTitle"
        private const val KEY_AGENT_TASK = "lastAgentTask"
        private const val KEY_AGENT_TIMELINE = "lastAgentTimeline"
        private const val KEY_AGENT_TOOL_SUMMARY = "lastAgentToolSummary"
        private const val KEY_AGENT_RESULT = "lastAgentResult"
        private const val KEY_AGENT_ERROR = "lastAgentError"
        private const val KEY_AGENT_PERMISSION = "lastAgentMissingPermissionId"
        private const val KEY_RUNTIME_PERMISSION = "pendingRuntimePermissionId"
        private const val KEY_DIAGNOSTICS = "diagnosticsText"
        private const val KEY_LOCAL_MODEL_MESSAGE = "localModelManagerMessage"
        private const val KEY_APPROVAL_ID = "pendingApprovalId"
        private const val KEY_APPROVAL_TOOL = "pendingApprovalToolId"
        private const val KEY_APPROVAL_TITLE = "pendingApprovalTitle"
        private const val KEY_APPROVAL_DETAIL = "pendingApprovalDetail"
        private const val KEY_APPROVAL_RISK = "pendingApprovalRisk"
        private const val KEY_APPROVAL_ACTION = "pendingApprovalAction"
        private const val KEY_APPROVAL_ARGUMENT = "pendingApprovalArgument"
        private const val KEY_APPROVAL_STATUS = "pendingApprovalStatus"
        private const val KEY_DOCUMENT_ID = "pendingDocumentId"
        private const val KEY_DOCUMENT_TITLE = "pendingDocumentTitle"
        private const val KEY_DOCUMENT_DESTINATION = "pendingDocumentDestination"
        private const val KEY_DOCUMENT_ACTION = "pendingDocumentAction"

        val PERSISTED_KEYS: Set<String> = linkedSetOf(
            KEY_SECTION,
            KEY_SELECTED_MODEL,
            KEY_TECHNICAL_DETAILS,
            KEY_PROMPT,
            KEY_TRANSCRIPT,
            KEY_GENERATION_META,
            KEY_GENERATION_ERROR,
            KEY_STUDIO_TITLE,
            KEY_STUDIO_RESULT,
            KEY_STUDIO_ACTIVE_TITLE,
            KEY_AGENT_TASK,
            KEY_AGENT_TIMELINE,
            KEY_AGENT_TOOL_SUMMARY,
            KEY_AGENT_RESULT,
            KEY_AGENT_ERROR,
            KEY_AGENT_PERMISSION,
            KEY_RUNTIME_PERMISSION,
            KEY_DIAGNOSTICS,
            KEY_LOCAL_MODEL_MESSAGE,
            KEY_APPROVAL_ID,
            KEY_APPROVAL_TOOL,
            KEY_APPROVAL_TITLE,
            KEY_APPROVAL_DETAIL,
            KEY_APPROVAL_RISK,
            KEY_APPROVAL_ACTION,
            KEY_APPROVAL_ARGUMENT,
            KEY_APPROVAL_STATUS,
            KEY_DOCUMENT_ID,
            KEY_DOCUMENT_TITLE,
            KEY_DOCUMENT_DESTINATION,
            KEY_DOCUMENT_ACTION,
        )

        private val REQUIRED_KEYS = setOf(
            KEY_SECTION,
            KEY_SELECTED_MODEL,
            KEY_APPROVAL_ID,
            KEY_APPROVAL_TOOL,
            KEY_APPROVAL_TITLE,
            KEY_APPROVAL_RISK,
            KEY_APPROVAL_ACTION,
            KEY_APPROVAL_STATUS,
        )
    }
}

/** Thread-safe exactly-once gate for the app-owned approval record. */
class ApprovalStateMachine(initial: PendingApprovalState? = null) {
    private var approval: PendingApprovalState? = initial

    @Synchronized
    fun current(): PendingApprovalState? = approval

    @Synchronized
    fun replace(value: PendingApprovalState?) {
        approval = value
    }

    @Synchronized
    fun claim(approvalId: String): PendingApprovalState? {
        val current = approval ?: return null
        if (current.approvalId != approvalId || current.status != ApprovalStatus.PENDING) return null
        return current.copy(status = ApprovalStatus.EXECUTING).also { approval = it }
    }

    @Synchronized
    fun cancel(approvalId: String): PendingApprovalState? {
        val current = approval ?: return null
        if (current.approvalId != approvalId || current.status != ApprovalStatus.PENDING) return null
        approval = null
        return current
    }

    @Synchronized
    fun complete(approvalId: String): Boolean {
        val current = approval ?: return false
        if (current.approvalId != approvalId || current.status != ApprovalStatus.EXECUTING) return false
        approval = null
        return true
    }
}
