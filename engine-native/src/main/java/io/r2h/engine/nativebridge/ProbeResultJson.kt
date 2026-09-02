package io.r2h.engine.nativebridge

internal fun runtimeProbeResultFromNativeJson(
    json: String,
    elapsedMs: Long,
    fallbackBackend: String,
): RuntimeProbeResult {
    val statusName = json.valueFor("status") ?: RuntimeProbeStatus.SMOKE_FAILED.name
    return RuntimeProbeResult(
        status = RuntimeProbeStatus.entries.firstOrNull { it.name == statusName }
            ?: RuntimeProbeStatus.SMOKE_FAILED,
        backendLabel = json.valueFor("backendLabel") ?: fallbackBackend,
        outputPreview = json.valueFor("outputPreview").orEmpty(),
        elapsedMs = elapsedMs,
        errorCode = json.nullableValueFor("errorCode"),
        errorMessage = json.nullableValueFor("errorMessage"),
    )
}

internal fun modelMissingProbeResult(
    backendLabel: String,
    elapsedMs: Long,
    message: String,
): RuntimeProbeResult =
    RuntimeProbeResult(
        status = RuntimeProbeStatus.MODEL_MISSING,
        backendLabel = backendLabel,
        outputPreview = "",
        elapsedMs = elapsedMs,
        errorCode = "MODEL_MISSING",
        errorMessage = message,
    )

internal fun runtimeMissingProbeResult(
    backendLabel: String,
    elapsedMs: Long,
    code: String,
    message: String,
): RuntimeProbeResult =
    RuntimeProbeResult(
        status = RuntimeProbeStatus.RUNTIME_MISSING,
        backendLabel = backendLabel,
        outputPreview = "",
        elapsedMs = elapsedMs,
        errorCode = code,
        errorMessage = message,
    )

internal fun smokeFailedProbeResult(
    backendLabel: String,
    elapsedMs: Long,
    code: String,
    message: String,
    outputPreview: String = "",
): RuntimeProbeResult =
    RuntimeProbeResult(
        status = RuntimeProbeStatus.SMOKE_FAILED,
        backendLabel = backendLabel,
        outputPreview = outputPreview,
        elapsedMs = elapsedMs,
        errorCode = code,
        errorMessage = message,
    )

private fun String.nullableValueFor(key: String): String? {
    val raw = valueFor(key) ?: return null
    return raw.takeUnless { it == "null" }
}

private fun String.valueFor(key: String): String? {
    val quoted = "\"$key\":"
    val start = indexOf(quoted)
    if (start < 0) return null
    var index = start + quoted.length
    while (index < length && this[index].isWhitespace()) index++
    if (startsWith("null", index)) return "null"
    if (index >= length || this[index] != '"') return null
    index++
    val out = StringBuilder()
    while (index < length) {
        val c = this[index++]
        when (c) {
            '"' -> return out.toString()
            '\\' -> {
                if (index >= length) return out.toString()
                when (val escaped = this[index++]) {
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    else -> out.append(escaped)
                }
            }
            else -> out.append(c)
        }
    }
    return null
}
