package io.r2h.engine.model

class CompatibilityValidator {
    fun isSupported(record: LocalModelRecord): Boolean =
        record.absolutePath.endsWith(".gguf", ignoreCase = true) &&
            record.sizeBytes > 0L &&
            record.sha256.length == 64
}
