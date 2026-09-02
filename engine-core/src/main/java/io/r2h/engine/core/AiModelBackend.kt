package io.r2h.engine.core

import io.r2h.engine.api.model.GenerateResponse

/**
 * Abstract port interface defining the contract for any AI model inference backend.
 *
 * Implementations (adapters) handle concrete inference engines like LiteRT, NNAPI,
 * Qualcomm Hexagon, image models, multimodal models, etc. The orchestrator depends
 * on this abstraction, not on specific backend implementations, enabling extensibility
 * without modifying core inference logic.
 *
 * All methods are suspend functions, running on the orchestrator's dispatcher (typically IO).
 * Implementations must be thread-safe; callers may invoke methods concurrently if the
 * orchestrator queue allows it.
 */
interface AiModelBackend {

    /**
     * Human-readable label for this backend (e.g., "LiteRT/CPU", "LiteRT/GPU", "NNAPI").
     * Used for diagnostic logging and UI display.
     */
    fun getLabel(): String

    /**
     * Model type this backend supports (e.g., "TEXT_GENERATION", "VISION", "MULTIMODAL").
     * Used to categorize capabilities and filter models in UI.
     */
    fun getModelType(): String

    /**
     * Loads the model from the given file path into memory. Implementations should:
     *
     * - Validate file integrity and format before loading.
     * - Initialize runtime context and allocate buffers.
     * - Return true on success, false on failure.
     * - On failure, set an error message accessible via getLastError().
     * - May block for extended periods if runtime initialization is slow.
     *
     * Preconditions:
     *   - modelPath must be a valid readable file.
     *   - Backend must not have a model loaded (caller must unload() first if needed).
     *
     * Postconditions (on success):
     *   - getIsLoaded() returns true.
     *   - generate() can be called without additional initialization.
     *   - Memory usage increases by model size.
     */
    suspend fun load(modelPath: String, phase: (String) -> Unit = {}): Boolean

    /**
     * Runs synchronous inference on the given prompt with the loaded model.
     *
     * Preconditions:
     *   - A model must be loaded (getIsLoaded() returns true).
     *   - prompt must not be null or empty.
     *
     * Postconditions (on success):
     *   - Returns non-null GenerateResponse with tokens and finish reason.
     *   - Does not modify model state.
     *
     * May throw GenerateException if the model is not loaded or inference fails.
     */
    suspend fun generate(prompt: String, maxTokens: Int, temperature: Float): GenerateResponse

    /**
     * Cancels an in-progress generate() call if possible.
     * If no generate() is active, this is a no-op.
     * After cancellation, the in-flight generate() may still emit partial results.
     */
    suspend fun cancel()

    /**
     * Unloads the model from memory, releasing allocated buffers and context.
     * After this call, generate() cannot be invoked until load() is called again.
     * This is typically called when switching models or shutting down.
     */
    suspend fun unload()

    /**
     * Returns true if a model is currently loaded in this backend.
     * Safe to call at any time; does not block.
     */
    fun getIsLoaded(): Boolean

    /**
     * Returns a map of backend metadata suitable for diagnostic logging and UI display.
     * Example entries: {"executionTarget": "GPU", "computeCapability": "SM_70", "memory": "4096MB"}.
     * Safe to call at any time; does not block.
     */
    fun getMetadata(): Map<String, String>

    /**
     * Returns the most recent error message if load() or generate() failed.
     * Safe to call at any time; does not block.
     * May return null or empty string if no error has occurred.
     */
    fun getLastError(): String?

    /**
     * Returns the model ID currently loaded, or null if no model is loaded.
     * Safe to call at any time; does not block.
     */
    fun getLoadedModelId(): String?
}

