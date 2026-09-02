package io.r2h.engine.api.model

/**
 * Operational state of the engine process.
 * Carried inside [EngineStatus] and returned by
 * [io.r2h.engine.api.IR2hEngineService.getEngineStatus].
 */
enum class EngineStateCode {

    /** Engine is running, a model is loaded, and no generation is in progress. */
    IDLE,

    /** Engine is actively processing one or more generation requests. */
    GENERATING,

    /** A model load or hot-swap is in progress. Generation requests will be queued. */
    LOADING_MODEL,

    /**
     * Engine process started but is not yet ready to accept requests, or an
     * unrecoverable internal failure has left the engine unable to serve requests.
     * Clients should retry [io.r2h.engine.api.IR2hEngineService.getEngineStatus]
     * after a short delay.
     */
    UNAVAILABLE,
}
