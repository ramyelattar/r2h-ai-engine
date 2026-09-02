package io.r2h.engine.model

enum class LocalModelRole {
    TEXT,
    VISION,
    RERANKER,
    EMBEDDING,
    UNKNOWN,
}

data class LocalModelCandidate(
    val displayName: String,
    val uri: String,
    val fileName: String,
    val extension: String,
    val sizeBytes: Long,
    val guessedRole: LocalModelRole,
    val readable: Boolean,
)

object LocalModelCandidateClassifier {
    private val supportedExtensions = setOf("gguf", "onnx")

    fun isSupported(fileName: String): Boolean =
        fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase() in supportedExtensions

    fun create(
        displayName: String,
        fileName: String,
        uri: String,
        sizeBytes: Long,
        readable: Boolean,
        relativePath: String = fileName,
    ): LocalModelCandidate? {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension !in supportedExtensions) return null

        val hint = "$relativePath $fileName".lowercase()
        val role = when {
            "rerank" in hint -> LocalModelRole.RERANKER
            "embed" in hint -> LocalModelRole.EMBEDDING
            listOf("vision", "image", "yolo", "detect", "segment", "ocr").any(hint::contains) ->
                LocalModelRole.VISION
            extension == "gguf" -> LocalModelRole.TEXT
            else -> LocalModelRole.UNKNOWN
        }
        return LocalModelCandidate(
            displayName = displayName.ifBlank { fileName.substringBeforeLast('.') },
            uri = uri,
            fileName = fileName,
            extension = extension,
            sizeBytes = sizeBytes.coerceAtLeast(0L),
            guessedRole = role,
            readable = readable,
        )
    }
}
