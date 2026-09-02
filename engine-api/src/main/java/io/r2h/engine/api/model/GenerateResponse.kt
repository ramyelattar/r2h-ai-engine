package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Terminal result delivered via [io.r2h.engine.api.IR2hGenerateCallback.onComplete]
 * when a generation request finishes (successfully, at token limit, or after cancel).
 *
 * For **non-streaming** requests: [outputText] contains the complete generated text.
 * For **streaming** requests: [outputText] is empty; the caller should reconstruct
 * the full text by concatenating all prior [io.r2h.engine.api.IR2hGenerateCallback.onToken]
 * chunks. The token/count fields are always populated regardless of streaming mode.
 *
 * @param requestId           Echoes the [GenerateRequest.requestId] this response
 *                            belongs to.
 * @param outputText          Complete generated text. Empty for streaming requests.
 * @param finishReason        Why the engine stopped generating.
 * @param promptTokenCount    Number of tokens in the evaluated prompt. Useful for
 *                            context window accounting on the caller side.
 * @param generatedTokenCount Number of tokens the engine produced. Does not include
 *                            the prompt token count.
 */
@Parcelize
data class GenerateResponse(
    val requestId: String,
    val outputText: String,
    val finishReason: FinishReason,
    val promptTokenCount: Int,
    val generatedTokenCount: Int,
) : Parcelable
