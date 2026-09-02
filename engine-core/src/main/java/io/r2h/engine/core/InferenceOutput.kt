package io.r2h.engine.core

data class InferenceOutput(
    val requestId: String,
    val phase: Phase,
    val items: List<Item>,
    val completion: CompletionStatus,
    val latencyMs: Long = 0L,
) {
    enum class Phase { Streaming, Final }

    enum class FinishReason { COMPLETED, CANCELLED, ERROR, LENGTH, STOP_SEQUENCE }

    sealed interface Item {
        data class Text(val text: String) : Item
        data class Labels(val values: List<LabelScore>) : Item
        data class Embedding(val values: FloatArray) : Item
        data class AudioChunk(val bytes: ByteArray, val mimeType: String = "audio/pcm") : Item
        data class ImageBytes(val bytes: ByteArray, val mimeType: String = "image/jpeg") : Item
    }

    data class LabelScore(val label: String, val score: Float)

    sealed interface CompletionStatus {
        data class Terminal(val reason: FinishReason) : CompletionStatus
        data object InProgress : CompletionStatus
    }
}
