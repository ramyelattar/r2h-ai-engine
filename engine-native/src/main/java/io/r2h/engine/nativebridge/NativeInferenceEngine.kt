package io.r2h.engine.nativebridge

/**
 * Single point of contact for all JNI calls into the native inference runtime.
 *
 * Design invariants (enforced by engine-core, not by this object):
 *   1. Only one thread may call [generate] on a given [contextHandle] at a time.
 *      The native inference context is not thread-safe.
 *   2. [cancel] may be called from any thread while [generate] is in progress.
 *      The implementation sets an atomic flag checked by the generate loop.
 *   3. [destroyContext] must only be called after [generate] has returned.
 *      Calling [destroyContext] during an active generation is undefined behaviour.
 *   4. A contextHandle of 0L indicates a failed [createContext] call. All functions
 *      validate the handle and return an error code without crashing.
 *
 * The library is loaded lazily on first access via [NativeLibraryLoader].
 *
 * All external functions here correspond to JNI implementations in r2h_native.cpp.
 * See that file for the native-side contracts.
 */
object NativeInferenceEngine {

    init {
        NativeLibraryLoader.load()
    }

    /**
     * Allocates a native llama.cpp context from the GGUF file at [modelPath].
     *
     * @param modelPath       Absolute path to a validated GGUF model file.
     * @param maxContextLength Maximum token context window to allocate (KV cache size).
     *                        Must be > 0 and ≤ the model's trained context length.
     * @param threads         Number of CPU threads for inference. Typically nproc/2.
     * @return Native context handle. 0L on allocation failure. The caller must
     *         call [destroyContext] with any non-zero return value when done.
     */
    external fun createContext(modelPath: String, maxContextLength: Int, threads: Int): Long

    /**
     * Allocates a native llama.cpp context configured for embedding extraction.
     * The native side enables embeddings and last-token pooling for decoder-only
     * embedding models such as Qwen3-Embedding.
     */
    external fun createEmbeddingContext(modelPath: String, maxContextLength: Int, threads: Int): Long

    /**
     * Releases all native memory held by the context and invalidates the handle.
     *
     * Must not be called while [generate] is executing on the same handle.
     * No-op if [contextHandle] is 0L.
     */
    external fun destroyContext(contextHandle: Long)

    /**
     * Runs inference synchronously on the calling thread.
     *
     * Blocks until generation completes, is cancelled, or fails. Each produced token
     * is delivered to [tokenCallback] on the calling thread before the next token is
     * evaluated. The callback must return quickly to avoid stalling the inference loop.
     *
     * @param contextHandle   Handle returned by [createContext]. Must be non-zero.
     * @param prompt          Fully-constructed prompt string. No template is applied
     *                        in native; the caller is responsible for all formatting.
     * @param maxTokens       Hard cap on tokens to generate. Must be > 0.
     * @param temperature     Sampling temperature. Clamped to [0.0, 2.0] in native.
     * @param tokenCallback   Invoked for each streaming token. Pass a no-op lambda
     *                        for non-streaming requests; native always streams internally.
     * @return [NativeGenerateResult] describing success or failure. Never null.
     */
    external fun generate(
        contextHandle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        tokenCallback: TokenCallback,
    ): NativeGenerateResult

    /**
     * Runs a synchronous embedding pass and returns one pooled vector. Returns null
     * on native failure; callers must treat that as a failed runtime proof.
     */
    external fun embedText(contextHandle: Long, text: String): FloatArray?

    /**
     * Sets a cancellation flag that the [generate] loop checks between tokens.
     *
     * Thread-safe. May be called from any thread at any time, including before
     * [generate] starts (in which case [generate] exits immediately with CANCELLED).
     * No-op if [contextHandle] is 0L or no generation is in progress.
     */
    external fun cancel(contextHandle: Long)

    external fun getBackendLabel(contextHandle: Long): String?

    /**
     * Reads GGUF metadata from [modelPath] without allocating an inference context.
     *
     * Used by model-manager to populate [ModelInfo] without the memory cost of a
     * full model load. Returns null if the file is not a valid GGUF or cannot be read.
     *
     * @param modelPath Absolute path to the GGUF file.
     */
    external fun readModelMetadata(modelPath: String): NativeModelMetadata?
}
