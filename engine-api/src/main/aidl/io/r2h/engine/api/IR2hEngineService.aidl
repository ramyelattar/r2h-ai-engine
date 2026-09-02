// Primary IPC interface between client apps and the R2H AI Engine service.
//
// Version history:
//   API_VERSION 1 — initial release (generate, cancel, warmup, status, listModels)
//   API_VERSION 2 — multimodal inference, client sessions, truth snapshot, orchestration
//   API_VERSION 3 — explicit named capability entry points over the local service API
//
// Versioning rules:
//   - Adding new methods is allowed. New methods must be added at the END of the
//     interface so that existing clients linked against older versions remain binary
//     compatible (Binder assigns method ordinals by declaration order).
//   - Removing or reordering existing methods is a BREAKING CHANGE and requires
//     incrementing API_VERSION and a migration path for all bound clients.
//   - Clients should call getApiVersion() before using any method added after V1.
//
// Binding permission: r2h.permission.BIND_ENGINE (signature-level).
// All methods require a UID whose resolved package signer identity is trusted.
// Synchronous methods may throw SecurityException; oneway inference methods
// return PERMISSION_DENIED through their callback.
package io.r2h.engine.api;

import io.r2h.engine.api.IR2hGenerateCallback;
import io.r2h.engine.api.IModalInferenceCallback;
import io.r2h.engine.api.ISessionOrchestratorCallback;
import io.r2h.engine.api.model.AiDecisionResult;
import io.r2h.engine.api.model.ClientSessionRegistration;
import io.r2h.engine.api.model.EngineDashboardSnapshot;
import io.r2h.engine.api.model.EngineError;
import io.r2h.engine.api.model.EngineStatus;
import io.r2h.engine.api.model.EngineTruthSnapshot;
import io.r2h.engine.api.model.GenerateRequest;
import io.r2h.engine.api.model.ModalInferenceRequest;
import io.r2h.engine.api.model.ModelInfo;
import io.r2h.engine.api.model.ModelLoadStatus;
import io.r2h.engine.api.model.SessionContext;

interface IR2hEngineService {

    // ── V1 methods ─────────────────────────────────────────────────────────────
    // Do NOT reorder or remove anything in the V1 block.

    // The integer API version baked into this build of engine-api.
    const int API_VERSION = 3;

    // Returns API_VERSION. Safe to call immediately after binding.
    int getApiVersion();

    // Returns a snapshot of the engine's current operational state.
    EngineStatus getEngineStatus();

    // Returns known model files from model-manager. Empty list if none installed.
    List<ModelInfo> listInstalledModels();

    // Submits a text generation request (legacy path). Queued; results via callback.
    // Queue capacity = 10; excess requests receive EngineError(QUEUE_FULL).
    oneway void generate(in GenerateRequest request, IR2hGenerateCallback callback);

    // Requests cancellation of a queued or in-progress generate() request.
    // Delivers onComplete(CANCELLED) as the terminal event. No-op if unknown.
    oneway void cancel(String requestId);

    // Warms up (pre-loads) a model. Fire-and-forget; errors surface on first use.
    oneway void warmup(String modelId);

    // ── V2 methods ─────────────────────────────────────────────────────────────
    // Call getApiVersion() >= 2 before calling any method below this line.

    // Liveness check. Returns SystemClock.elapsedRealtime() from the engine process.
    long ping();

    // Returns the current model-load status: state, progress percent, error message.
    ModelLoadStatus getModelLoadStatus();

    // Returns a dashboard snapshot: engine status + load status + models + integrations.
    EngineDashboardSnapshot getEngineDashboard();

    // Registers a client session. Returns an opaque session ID for use in heartbeat
    // and unregister calls. Repeated registration from the same package is allowed;
    // each call creates a new independent session record.
    String registerClientSession(in ClientSessionRegistration registration);

    // Updates the last-seen timestamp for a client session. Call periodically
    // (recommended: every 15 seconds) to keep the session marked as live.
    oneway void heartbeatClientSession(String sessionId);

    // Removes a client session record. No-op if sessionId is unknown.
    oneway void unregisterClientSession(String sessionId);

    // Returns the full authoritative engine state snapshot. Includes runtime info,
    // per-model states, active integrations, live sessions, and per-modality states.
    EngineTruthSnapshot getEngineTruthSnapshot();

    // Submits a multimodal inference request. Supports text, image, audio, and
    // video inputs depending on the loaded model's declared capabilities.
    // Results are delivered token-by-token (streaming) via callback.onResult().
    oneway void inferModal(in ModalInferenceRequest request, IModalInferenceCallback callback);

    // Registers a model file that was imported by the user (e.g. via file picker).
    // Returns the model ID assigned by model-manager. Refreshes the model catalog.
    String registerImportedModel(String absolutePath);

    // ── V2 orchestration ───────────────────────────────────────────────────────

    // Submits a session context to the AI orchestration layer. The engine:
    //   1. Builds a structured prompt from the session context and tool catalog
    //   2. Runs inference against the active model (or context.modelId if set)
    //   3. Parses the model's response into a typed AiDecisionResult
    //   4. Delivers the result via callback.onDecision()
    //
    // If the model produces intermediate thinking output, zero or more
    // callback.onThinkingChunk() calls may precede the final onDecision().
    //
    // On failure, callback.onError() is called and onDecision() is NOT called.
    oneway void orchestrateSession(in SessionContext context, ISessionOrchestratorCallback callback);

    // ── V3 explicit capability entry points ──────────────────────────────────
    // These methods expose stable named API paths for each engine capability.
    // They do not imply readiness; each path still validates descriptors,
    // loaded local runtimes, input refs, and model task compatibility before
    // reaching any backend.

    // Text generation alias for the legacy generate() path.
    oneway void generateText(in GenerateRequest request, IR2hGenerateCallback callback);

    // Modal capabilities. Results are returned through the existing modal callback.
    oneway void analyzeImage(in ModalInferenceRequest request, IModalInferenceCallback callback);
    oneway void transcribeSpeech(in ModalInferenceRequest request, IModalInferenceCallback callback);
    oneway void synthesizeSpeech(in ModalInferenceRequest request, IModalInferenceCallback callback);
    oneway void analyzeAudio(in ModalInferenceRequest request, IModalInferenceCallback callback);
    oneway void analyzeVideo(in ModalInferenceRequest request, IModalInferenceCallback callback);
    oneway void generateMultimodal(in ModalInferenceRequest request, IModalInferenceCallback callback);
    oneway void runOcr(in ModalInferenceRequest request, IModalInferenceCallback callback);
    oneway void createEmbedding(in ModalInferenceRequest request, IModalInferenceCallback callback);

    // Agent/tool orchestration alias for orchestrateSession().
    oneway void runAgent(in SessionContext context, ISessionOrchestratorCallback callback);
}
