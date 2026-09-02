package io.r2h.engine.core

import android.os.RemoteException
import android.util.Log
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.api.ISessionOrchestratorCallback
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.ErrorStage
import io.r2h.engine.api.model.SessionContext
import io.r2h.engine.api.model.ToolDefinition
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

private const val TAG = "SessionOrchestrator"

// Max tokens for the orchestration response. The model only needs to produce
// a JSON object — 512 tokens is generous even for multi-step plans.
private const val ORCHESTRATION_MAX_TOKENS = 512

// Temperature for orchestration. Lower than default to favour deterministic
// tool selection over creative variation.
private const val ORCHESTRATION_TEMPERATURE = "0.3"

/**
 * Coordinates the AI-directed session orchestration pipeline.
 *
 * Given a [SessionContext], this class:
 *  1. Resolves the effective tool catalog by merging [ToolRegistry] built-ins
 *     with the session-specific tools declared in [SessionContext.availableTools].
 *  2. Builds a structured prompt via [SessionPromptBuilder].
 *  3. Runs inference through [BackendRuntime].
 *  4. Parses the model's response into a typed [AiDecision] via [AiDecisionParser].
 *  5. Returns the decision (or an error) via [ISessionOrchestratorCallback].
 *
 * All inference is performed on the caller's coroutine context. The caller
 * ([EngineServiceImpl]) is responsible for launching this on [Dispatchers.IO].
 */
class SessionOrchestrator(
    private val toolRegistry: ToolRegistry,
    private val promptBuilder: SessionPromptBuilder = SessionPromptBuilder(),
    private val decisionParser: AiDecisionParser = AiDecisionParser(),
) {

    /**
     * Runs the full orchestration pipeline for [context] using [runtime] and
     * [descriptor], delivering the result via [callback].
     *
     * This is a suspending function — callers must launch it on an appropriate
     * [kotlinx.coroutines.CoroutineScope].
     */
    suspend fun orchestrate(
        context: SessionContext,
        runtime: BackendRuntime,
        descriptor: ModelDescriptor,
        callback: ISessionOrchestratorCallback,
    ) {
        val tools: List<ToolDefinition> = toolRegistry.resolveForSession(context.availableTools)

        val prompt = promptBuilder.build(context, tools)

        Log.d(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.AGENT,
                status = DiagnosticStatus.STARTED,
                requestId = context.requestId,
                inputContent = prompt,
                recordCount = tools.size,
            ),
        )

        // Build InferenceInput. The orchestration prompt is treated as a single
        // TEXT part so that any model backend can handle it.
        val inferenceInput = InferenceInput(
            requestId = context.requestId,
            task = InferenceInput.Task.Chat,
            parts = listOf(InferenceInput.Part.Text(prompt)),
            metadata = buildMetadata(context),
        )

        val responseBuilder = StringBuilder()

        try {
            runtime.execute(descriptor, inferenceInput)
                .onEach { output ->
                    // Accumulate response text; deliver thinking chunks for streaming UX
                    output.items
                        .filterIsInstance<InferenceOutput.Item.Text>()
                        .forEach { textItem ->
                            responseBuilder.append(textItem.text)
                            safeDeliverThinkingChunk(callback, textItem.text)
                        }
                }
                .collect()
        } catch (t: Throwable) {
            Log.e(
                TAG,
                PrivacySafeDiagnostics.failureEvent(
                    operation = DiagnosticOperation.AGENT,
                    requestId = context.requestId,
                    errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                    failure = t,
                    inputContent = prompt,
                ),
            )
            safeDeliverError(
                callback,
                EngineError(
                    ErrorStage.INFERENCE,
                    ErrorCode.INFERENCE_FAILED,
                    t.message ?: "Orchestration inference failed.",
                ),
            )
            return
        }

        val rawOutput = responseBuilder.toString()
        Log.d(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.AGENT,
                status = DiagnosticStatus.UPDATED,
                requestId = context.requestId,
                outputContent = rawOutput,
            ),
        )

        val decision = decisionParser.parse(rawOutput)
        val result = decision.toResult(context.requestId)

        Log.i(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.AGENT,
                status = DiagnosticStatus.COMPLETED,
                requestId = context.requestId,
                outputContent = rawOutput,
            ),
        )

        try {
            callback.onDecision(result)
        } catch (_: RemoteException) {
            Log.w(
                TAG,
                PrivacySafeDiagnostics.contentEvent(
                    operation = DiagnosticOperation.AGENT,
                    status = DiagnosticStatus.FAILED,
                    requestId = context.requestId,
                    errorCode = DiagnosticErrorCode.REMOTE_FAILURE,
                ),
            )
        }
    }

    private fun buildMetadata(context: SessionContext): Map<String, String> {
        val base = mutableMapOf(
            "engine.orchestration" to "true",
            "engine.maxTokens" to ORCHESTRATION_MAX_TOKENS.toString(),
            "engine.temperature" to ORCHESTRATION_TEMPERATURE,
        )
        // Caller-supplied params override defaults (e.g. custom temperature)
        base.putAll(context.params)
        return base
    }

    private fun safeDeliverThinkingChunk(
        callback: ISessionOrchestratorCallback,
        chunk: String,
    ) {
        try {
            callback.onThinkingChunk(chunk)
        } catch (_: RemoteException) {
            // Client died mid-stream — stop delivering chunks but continue collecting
            // so the parser can still produce a decision from the accumulated text.
        }
    }

    private fun safeDeliverError(
        callback: ISessionOrchestratorCallback,
        error: EngineError,
    ) {
        try {
            callback.onError(error)
        } catch (_: RemoteException) {
            Log.w(TAG, "Client died before error delivery")
        }
    }
}
