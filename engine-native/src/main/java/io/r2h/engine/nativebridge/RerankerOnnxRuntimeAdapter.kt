package io.r2h.engine.nativebridge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.File
import java.lang.reflect.Array
import java.nio.LongBuffer

class RerankerOnnxRuntimeAdapter {
    fun runSmoke(modelFile: File, tokenizerJson: File): RuntimeProbeResult {
        val started = System.currentTimeMillis()
        if (!modelFile.isFile) {
            return modelMissingProbeResult(BACKEND_LABEL, 0L, modelFile.absolutePath)
        }
        if (!tokenizerJson.isFile) {
            return modelMissingProbeResult(BACKEND_LABEL, 0L, tokenizerJson.absolutePath)
        }

        return try {
            val encoded = PretokenizedXlmRobertaSmoke.encode(tokenizerJson)
            OrtEnvironment.getEnvironment().use { env ->
                OrtSession.SessionOptions().use { options ->
                    env.createSession(modelFile.absolutePath, options).use { session ->
                        val runtimeInputs = mutableMapOf<String, OnnxTensor>()
                        putRuntimeTensor(session, runtimeInputs, env, "input_ids", encoded.ids)
                        putRuntimeTensor(session, runtimeInputs, env, "attention_mask", encoded.attentionMask)
                        putRuntimeTensor(session, runtimeInputs, env, "token_type_ids", encoded.typeIds)

                        if (runtimeInputs.isEmpty()) {
                            return smokeFailedProbeResult(
                                BACKEND_LABEL,
                                System.currentTimeMillis() - started,
                                "NO_SUPPORTED_INPUTS",
                                "Reranker inputs were ${session.inputNames}.",
                            )
                        }

                        runtimeInputs.values.useAll {
                            session.run(runtimeInputs).use { outputs ->
                                val score = if (outputs.size() > 0) firstNumeric(outputs.get(0)) else null
                                if (score != null) {
                                    RuntimeProbeResult(
                                        status = RuntimeProbeStatus.AVAILABLE,
                                        backendLabel = BACKEND_LABEL,
                                        outputPreview = "score=$score tokens=${encoded.ids.size} tokenizer=PRETOKENIZED_SMOKE productionTokenizer=NOT_READY",
                                        elapsedMs = System.currentTimeMillis() - started,
                                        errorCode = null,
                                        errorMessage = null,
                                    )
                                } else {
                                    smokeFailedProbeResult(
                                        BACKEND_LABEL,
                                        System.currentTimeMillis() - started,
                                        "NO_NUMERIC_SCORE",
                                        "Reranker ONNX session did not return a numeric first output.",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (missing: UnsatisfiedLinkError) {
            runtimeMissingProbeResult(
                BACKEND_LABEL,
                System.currentTimeMillis() - started,
                "NATIVE_LIBRARY_MISSING",
                missing.message ?: missing.javaClass.simpleName,
            )
        } catch (missing: NoClassDefFoundError) {
            runtimeMissingProbeResult(
                BACKEND_LABEL,
                System.currentTimeMillis() - started,
                "RUNTIME_CLASS_MISSING",
                missing.message ?: missing.javaClass.simpleName,
            )
        } catch (e: OrtException) {
            smokeFailedProbeResult(
                BACKEND_LABEL,
                System.currentTimeMillis() - started,
                e.javaClass.simpleName,
                e.message ?: e.javaClass.name,
            )
        } catch (t: Throwable) {
            smokeFailedProbeResult(
                BACKEND_LABEL,
                System.currentTimeMillis() - started,
                t.javaClass.simpleName,
                t.message ?: t.javaClass.name,
            )
        }
    }

    private fun putRuntimeTensor(
        session: OrtSession,
        inputs: MutableMap<String, OnnxTensor>,
        env: OrtEnvironment,
        name: String,
        values: LongArray,
    ) {
        if (session.inputNames.contains(name)) {
            inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(values), longArrayOf(1L, values.size.toLong()))
        }
    }

    private fun firstNumeric(value: OnnxValue): Double? =
        firstNumeric(value.value)

    private fun firstNumeric(value: Any?): Double? =
        when (value) {
            is Float -> value.toDouble()
            is Double -> value
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Short -> value.toDouble()
            is Byte -> value.toDouble()
            null -> null
            else -> {
                val type = value.javaClass
                if (!type.isArray || Array.getLength(value) == 0) {
                    null
                } else {
                    firstNumeric(Array.get(value, 0))
                }
            }
        }

    private companion object {
        const val BACKEND_LABEL = "ONNX Runtime Android + pre-tokenized XLM-R smoke"
    }
}

private data class EncodedPair(
    val ids: LongArray,
    val attentionMask: LongArray,
    val typeIds: LongArray,
)

private object PretokenizedXlmRobertaSmoke {
    private val requiredTokens = listOf("<s>", "</s>", "▁test", "▁document")

    fun encode(tokenizerJson: File): EncodedPair {
        val idsByToken = readRequiredIds(tokenizerJson)
        val ids = longArrayOf(
            idsByToken.getValue("<s>"),
            idsByToken.getValue("▁test"),
            idsByToken.getValue("</s>"),
            idsByToken.getValue("</s>"),
            idsByToken.getValue("▁document"),
            idsByToken.getValue("</s>"),
        )
        return EncodedPair(
            ids = ids,
            attentionMask = LongArray(ids.size) { 1L },
            typeIds = LongArray(ids.size) { 0L },
        )
    }

    private fun readRequiredIds(tokenizerJson: File): Map<String, Long> {
        val ids = linkedMapOf<String, Long>()
        var inVocab = false
        var index = 0L
        tokenizerJson.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (!inVocab) {
                    if (line == "\"vocab\": [" || line == "\"vocab\":[") {
                        inVocab = true
                    }
                    continue
                }
                if (line == "\"byte_fallback\": true," || line == "\"byte_fallback\": false,") {
                    break
                }
                if (line.startsWith('"')) {
                    val token = line.substringAfter('"').substringBeforeLast('"')
                    if (token in requiredTokens) {
                        ids[token] = index
                        if (ids.size == requiredTokens.size) {
                            break
                        }
                    }
                    index += 1L
                }
            }
        }
        val missing = requiredTokens.filterNot { ids.containsKey(it) }
        require(missing.isEmpty()) { "Tokenizer smoke tokens missing from tokenizer.json vocab: $missing" }
        return ids
    }
}

private inline fun <T> Collection<AutoCloseable>.useAll(block: () -> T): T =
    try {
        block()
    } finally {
        forEach { it.close() }
    }
