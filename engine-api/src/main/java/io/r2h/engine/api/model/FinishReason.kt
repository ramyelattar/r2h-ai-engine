package io.r2h.engine.api.model

/**
 * Describes why the engine stopped generating tokens for a request.
 * Carried inside [GenerateResponse] and delivered via
 * [io.r2h.engine.api.IR2hGenerateCallback.onComplete].
 */
enum class FinishReason {

    /** The model generated an end-of-sequence token and stopped naturally. */
    COMPLETE,

    /** Generation was halted because [SessionConfig.maxTokens] was reached. */
    MAX_TOKENS,

    /**
     * The request was cancelled by the caller via
     * [io.r2h.engine.api.IR2hEngineService.cancel] before generation completed.
     * onComplete is still delivered (not onError) when the reason is CANCELLED.
     * For streaming requests, tokens delivered before cancellation remain valid.
     */
    CANCELLED,
}
