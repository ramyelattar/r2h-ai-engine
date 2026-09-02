package io.r2h.engine.core

import io.r2h.engine.nativebridge.NativeGenerateResult
import io.r2h.engine.nativebridge.NativeInferenceEngine
import io.r2h.engine.nativebridge.TokenCallback

internal interface NativeRuntimePort {
    fun createContext(
        modelPath: String,
        maxContextLength: Int,
        threads: Int
    ): Long

    fun createEmbeddingContext(
        modelPath: String,
        maxContextLength: Int,
        threads: Int
    ): Long

    fun generate(
        contextHandle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        tokenCallback: TokenCallback
    ): NativeGenerateResult

    fun cancel(contextHandle: Long)

    fun destroyContext(contextHandle: Long)

    fun embedText(contextHandle: Long, text: String): FloatArray?
}

internal object JniNativeRuntimePort : NativeRuntimePort {
    override fun createContext(
        modelPath: String,
        maxContextLength: Int,
        threads: Int
    ): Long {
        return NativeInferenceEngine.createContext(
            modelPath,
            maxContextLength,
            threads
        )
    }

    override fun createEmbeddingContext(
        modelPath: String,
        maxContextLength: Int,
        threads: Int
    ): Long {
        return NativeInferenceEngine.createEmbeddingContext(
            modelPath,
            maxContextLength,
            threads
        )
    }

    override fun generate(
        contextHandle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        tokenCallback: TokenCallback
    ): NativeGenerateResult {
        return NativeInferenceEngine.generate(
            contextHandle,
            prompt,
            maxTokens,
            temperature,
            tokenCallback
        )
    }

    override fun cancel(contextHandle: Long) {
        NativeInferenceEngine.cancel(contextHandle)
    }

    override fun destroyContext(contextHandle: Long) {
        NativeInferenceEngine.destroyContext(contextHandle)
    }

    override fun embedText(contextHandle: Long, text: String): FloatArray? {
        return NativeInferenceEngine.embedText(contextHandle, text)
    }
}
