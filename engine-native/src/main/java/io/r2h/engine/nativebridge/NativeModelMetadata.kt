package io.r2h.engine.nativebridge

/**
 * GGUF file metadata extracted by [NativeInferenceEngine.readModelMetadata] without
 * loading the model into an inference context.
 *
 * Instantiated by the C++ JNI layer via NewObject — constructor signature
 * `(JIILjava/lang/String;)V` must not change without a corresponding C++ update.
 *
 * @param parameterCount  Total parameter count reported in the GGUF header.
 *                        0 if the header does not include this field.
 * @param contextLength   Maximum context window length the model was trained with.
 * @param embeddingLength Hidden embedding dimension.
 * @param quantization    Quantization type string as stored in the GGUF metadata
 *                        (e.g., "Q4_K_M"). Empty string if not present.
 */
class NativeModelMetadata(
    val parameterCount: Long,
    val contextLength: Int,
    val embeddingLength: Int,
    val quantization: String,
)
