package io.r2h.engine.nativebridge

import java.io.File

class Qwen2VlMtmdRuntimeAdapter(
    private val maxContextLength: Int = 4096,
    private val threads: Int = defaultThreadCount(),
    private val defaultMaxTokens: Int = 96,
    private val defaultTemperature: Float = 0.2f,
) {
    data class Generation(
        val text: String,
        val nativeResult: NativeGenerateResult,
        val elapsedMs: Long,
    )

    private var handle: Long = 0L

    fun load(modelFile: File, mmprojFile: File): Boolean {
        require(modelFile.isFile) { "Qwen2VL model file missing: ${modelFile.absolutePath}" }
        require(mmprojFile.isFile) { "Qwen2VL mmproj file missing: ${mmprojFile.absolutePath}" }
        unload()
        handle = R2hMtmdNativeBridge.loadMultimodalModel(
            modelPath = modelFile.absolutePath,
            mmprojPath = mmprojFile.absolutePath,
            maxContextLength = maxContextLength,
            threads = threads,
        )
        return handle != 0L
    }

    fun generateFromImage(
        prompt: String,
        imageFile: File,
        maxTokens: Int = defaultMaxTokens,
        temperature: Float = defaultTemperature,
    ): Generation {
        val activeHandle = handle
        require(activeHandle != 0L) { "Qwen2VL mtmd runtime is not loaded." }
        require(prompt.isNotBlank()) { "Prompt is required for Qwen2VL mtmd generation." }
        require(imageFile.isFile) { "Image file missing: ${imageFile.absolutePath}" }
        val started = System.currentTimeMillis()
        val output = StringBuilder()
        val nativeResult = R2hMtmdNativeBridge.generateFromImage(
            contextHandle = activeHandle,
            prompt = prompt,
            imagePath = imageFile.absolutePath,
            maxTokens = maxTokens.coerceAtLeast(1),
            temperature = temperature.coerceIn(0f, 2f),
            tokenCallback = TokenCallback { token -> output.append(token) },
        )
        return Generation(
            text = output.toString().trim(),
            nativeResult = nativeResult,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    fun unload() {
        val activeHandle = handle
        if (activeHandle != 0L) {
            R2hMtmdNativeBridge.unloadMultimodalModel(activeHandle)
            handle = 0L
        }
    }

    private companion object {
        fun defaultThreadCount(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)
    }
}
