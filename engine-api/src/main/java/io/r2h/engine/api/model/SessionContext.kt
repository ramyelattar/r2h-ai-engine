package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Input to the engine's AI-directed session orchestration path.
 *
 * The engine takes this context, constructs a structured prompt, runs inference
 * against the active model, and returns an [AiDecisionResult] describing what
 * action to take next.
 *
 * [requestId] must be unique per request. The same value is echoed in the
 * returned [AiDecisionResult] so the caller can correlate responses.
 *
 * [appId] is the caller's package name or a stable app identifier. It is
 * included in the prompt so the model has context about which application is
 * making the request.
 *
 * [userMessage] is the raw user input that triggered this orchestration call.
 *
 * [appState] is an optional JSON string describing the current application
 * state (active screen, selected items, feature flags, etc.). The engine
 * includes this in the prompt verbatim. Keep it small (< 2 KB recommended).
 *
 * [modelId] identifies which registered model to use. An empty string means
 * "use whatever model is currently loaded."
 *
 * [conversationHistory] provides the prior turns for multi-turn continuity.
 * The engine truncates history if it would exceed the model's context window.
 *
 * [availableTools] is the full set of actions the model may select from.
 * An empty list instructs the engine to return a TEXT_RESPONSE decision only.
 *
 * [systemInstruction] overrides the default orchestration system prompt when
 * non-empty. Use sparingly — the engine's built-in orchestration prompt is
 * tuned for the tool-selection task.
 *
 * [params] are engine tuning overrides, e.g. "temperature" → "0.3",
 * "engine.timeoutMs" → "30000".
 */
@Parcelize
data class SessionContext(
    val requestId: String,
    val appId: String,
    val userMessage: String,
    val appState: String = "",
    val modelId: String = "",
    val conversationHistory: List<ConversationTurn> = emptyList(),
    val availableTools: List<ToolDefinition> = emptyList(),
    val systemInstruction: String = "",
    val params: Map<String, String> = emptyMap(),
) : Parcelable
