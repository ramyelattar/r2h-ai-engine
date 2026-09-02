package io.r2h.engine.nativebridge

object R2hMtmdNativeBridge {
    init {
        NativeLibraryLoader.load()
    }

    external fun loadMultimodalModel(
        modelPath: String,
        mmprojPath: String,
        maxContextLength: Int,
        threads: Int,
    ): Long

    external fun generateFromImage(
        contextHandle: Long,
        prompt: String,
        imagePath: String,
        maxTokens: Int,
        temperature: Float,
        tokenCallback: TokenCallback,
    ): NativeGenerateResult

    external fun unloadMultimodalModel(contextHandle: Long)
}
