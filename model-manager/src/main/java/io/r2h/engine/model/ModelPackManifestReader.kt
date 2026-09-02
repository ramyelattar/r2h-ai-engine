package io.r2h.engine.model

import android.content.Context

private const val MODEL_PACK_MANIFEST_ASSET = "model-pack/model-pack-manifest.json"

data class ModelPackManifest(
    val schemaVersion: Int,
    val packId: String,
    val generatedAtUtc: String?,
    val totalFiles: Int,
    val totalBytes: Long,
    val models: List<ModelPackModel>,
) {
    fun findArtifact(path: String): ModelPackArtifact? =
        models.asSequence().flatMap { it.artifacts.asSequence() }.firstOrNull { it.path == path }
}

data class ModelPackModel(
    val id: String,
    val family: String,
    val runtime: String,
    val capabilities: List<String>,
    val status: String,
    val artifacts: List<ModelPackArtifact>,
)

data class ModelPackArtifact(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val extension: String?,
)

class ModelPackManifestReader(private val context: Context) {
    fun read(): ModelPackManifest =
        context.assets.open(MODEL_PACK_MANIFEST_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            parse(reader.readText())
        }

    companion object {
        fun parse(json: String): ModelPackManifest {
            val root = JsonParser(json).parse().asObject("root")
            val models = root.requiredList("models").map { modelValue ->
                val model = modelValue.asObject("model")
                ModelPackModel(
                    id = model.requiredString("id"),
                    family = model.requiredString("family"),
                    runtime = model.requiredString("runtime"),
                    capabilities = model.requiredList("capabilities").map { it.asString("capability") },
                    status = model.requiredString("status"),
                    artifacts = model.requiredList("artifacts").map { artifactValue ->
                        val artifact = artifactValue.asObject("artifact")
                        ModelPackArtifact(
                            path = artifact.requiredString("path"),
                            sizeBytes = artifact.requiredLong("sizeBytes"),
                            sha256 = artifact.requiredString("sha256"),
                            extension = artifact.optionalString("extension"),
                        )
                    },
                )
            }

            return ModelPackManifest(
                schemaVersion = root.requiredLong("schemaVersion").toInt(),
                packId = root.requiredString("packId"),
                generatedAtUtc = root.optionalString("generatedAtUtc"),
                totalFiles = root.requiredLong("totalFiles").toInt(),
                totalBytes = root.requiredLong("totalBytes"),
                models = models,
            )
        }
    }
}

private class JsonParser(private val source: String) {
    private var index = 0

    fun parse(): Any? {
        val value = parseValue()
        skipWhitespace()
        require(index == source.length) { "Unexpected trailing JSON at index $index." }
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        require(index < source.length) { "Unexpected end of JSON." }
        return when (source[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected JSON token '${source[index]}' at index $index.")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val values = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (peek('}')) {
            index++
            return values
        }
        while (true) {
            val key = parseString()
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            when {
                peek(',') -> index++
                peek('}') -> {
                    index++
                    return values
                }
                else -> error("Expected ',' or '}' at index $index.")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val values = mutableListOf<Any?>()
        skipWhitespace()
        if (peek(']')) {
            index++
            return values
        }
        while (true) {
            values.add(parseValue())
            skipWhitespace()
            when {
                peek(',') -> index++
                peek(']') -> {
                    index++
                    return values
                }
                else -> error("Expected ',' or ']' at index $index.")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < source.length) {
            val c = source[index++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    require(index < source.length) { "Unterminated escape at index $index." }
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(index + 4 <= source.length) { "Invalid unicode escape at index $index." }
                            val hex = source.substring(index, index + 4)
                            out.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> error("Invalid escape '$escaped' at index $index.")
                    }
                }
                else -> out.append(c)
            }
        }
        error("Unterminated string.")
    }

    private fun parseNumber(): Number {
        val start = index
        if (peek('-')) index++
        while (index < source.length && source[index].isDigit()) index++
        val isDecimal = index < source.length && source[index] == '.'
        if (isDecimal) {
            index++
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            index++
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            while (index < source.length && source[index].isDigit()) index++
            return source.substring(start, index).toDouble()
        }
        val raw = source.substring(start, index)
        return if (isDecimal) raw.toDouble() else raw.toLong()
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        require(source.startsWith(literal, index)) { "Expected '$literal' at index $index." }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        require(index < source.length && source[index] == expected) {
            "Expected '$expected' at index $index."
        }
        index++
    }

    private fun peek(expected: Char): Boolean = index < source.length && source[index] == expected
}

private fun Any?.asObject(label: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this as? Map<String, Any?> ?: error("Expected JSON object for $label.")
}

private fun Any?.asString(label: String): String =
    this as? String ?: error("Expected JSON string for $label.")

private fun Map<String, Any?>.requiredString(key: String): String =
    this[key].asString(key)

private fun Map<String, Any?>.optionalString(key: String): String? =
    this[key] as? String

private fun Map<String, Any?>.requiredLong(key: String): Long {
    val value = this[key] ?: error("Missing JSON number '$key'.")
    return when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        else -> error("Expected JSON number for '$key'.")
    }
}

private fun Map<String, Any?>.requiredList(key: String): List<Any?> {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? List<Any?> ?: error("Expected JSON array for '$key'.")
}
