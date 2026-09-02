// Callback interface delivered by the caller when submitting a generation request.
//
// Declared oneway so the engine thread is never blocked by a slow or unresponsive
// client process. All methods fire-and-forget from the engine's perspective.
//
// Contract:
//   - onToken   : zero or more streaming chunk deliveries, only when streaming=true
//   - onComplete: exactly one terminal success callback per request (even on cancel)
//   - onError   : exactly one terminal failure callback per request
//   onComplete and onError are mutually exclusive. Exactly one fires per request.
package io.r2h.engine.api;

import io.r2h.engine.api.model.GenerateResponse;
import io.r2h.engine.api.model.EngineError;

oneway interface IR2hGenerateCallback {

    // Delivers a single incremental token chunk during streaming generation.
    // Only called when GenerateRequest.streaming == true.
    // Never called after onComplete or onError for the same requestId.
    void onToken(String requestId, String token);

    // Terminal success callback. Called exactly once per request.
    // For non-streaming requests, response.outputText holds the full generation.
    // For streaming requests, response.outputText is empty; aggregate onToken chunks.
    // response.finishReason == CANCELLED when the request was cancelled via cancel().
    void onComplete(String requestId, in GenerateResponse response);

    // Terminal failure callback. Called exactly once per request if a non-recoverable
    // error occurs. Not called if onComplete was already delivered.
    void onError(String requestId, in EngineError error);
}
