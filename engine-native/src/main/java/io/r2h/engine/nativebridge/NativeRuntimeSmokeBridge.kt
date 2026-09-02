package io.r2h.engine.nativebridge

object NativeRuntimeSmokeBridge {
    init {
        NativeLibraryLoader.load()
    }

    external fun yoloNcnnSmoke(paramPath: String, binPath: String): String

    external fun whisperCppSmoke(modelPath: String, pcmF32: FloatArray, sampleRate: Int): String

    external fun qwenImageMtmdSmoke(
        modelPath: String,
        mmprojPath: String,
        rgb: ByteArray,
        width: Int,
        height: Int,
    ): String
}
