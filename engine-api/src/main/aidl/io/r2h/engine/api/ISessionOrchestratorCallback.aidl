// Callback interface for the session orchestration path.
//
// All methods are one-way (fire-and-forget from the engine's perspective).
// Implementations must not block — the engine calls these from its IO thread pool.
package io.r2h.engine.api;

import io.r2h.engine.api.model.AiDecisionResult;
import io.r2h.engine.api.model.EngineError;

interface ISessionOrchestratorCallback {

    // Delivers the AI decision produced for the session context.
    // Called exactly once per orchestrateSession() invocation on success.
    oneway void onDecision(in AiDecisionResult result);

    // Delivers a partial thinking chunk while the model is reasoning.
    // May be called zero or more times before onDecision(). Callers may
    // use this to show a "thinking..." indicator in the UI.
    oneway void onThinkingChunk(String chunk);

    // Delivers an error. Called exactly once per orchestrateSession() invocation
    // on failure. onDecision() is not called when onError() is called.
    oneway void onError(in EngineError error);
}
