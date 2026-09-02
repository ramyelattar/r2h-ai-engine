package io.r2h.engine.core

data class InferenceInput(
    val requestId: String,
    val task: Task,
    val parts: List<Part>,
    val metadata: Map<String, String> = emptyMap(),
) {
    data class ArtifactRef(val ref: String, val mimeType: String)

    sealed interface Part {
        data class Text(val content: String) : Part
        data class Image(val artifact: ArtifactRef) : Part
        data class Audio(val artifact: ArtifactRef) : Part
        data class Video(val artifact: ArtifactRef) : Part
    }

    sealed interface Task {
        data object Chat : Task
        data object TextGeneration : Task
        data object Summarization : Task
        data object Rewriting : Task
        data object Extraction : Task
        data object QuestionAnswering : Task
        data object Embedding : Task
        data object Reranking : Task
        data object Classification : Task
        data object Detection : Task
        data object Segmentation : Task
        data object Ocr : Task
        data object ImageUnderstanding : Task
        data object ImageTextMultimodal : Task
        data object ImageGeneration : Task
        data object VideoFullAnalysis : Task
        data object AudioAnalysis : Task
        data object AudioTagging : Task
        data object SpeechToText : Task
        data object TextToSpeech : Task
    }
}
