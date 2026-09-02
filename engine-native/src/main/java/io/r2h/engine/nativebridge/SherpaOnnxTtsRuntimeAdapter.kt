package io.r2h.engine.nativebridge

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File

private const val TAG = "SherpaOnnxTts"

data class TtsSynthesisResult(
    val outputWav: File,
    val sampleRate: Int,
    val sampleCount: Int,
    val durationSeconds: Double,
    val voiceId: String,
)

class SherpaOnnxTtsRuntimeAdapter {
    fun runSmoke(englishModelDir: File, arabicModelDir: File, outputWav: File): RuntimeProbeResult {
        val started = System.currentTimeMillis()
        val modelDir = englishModelDir.takeIf { it.isDirectory } ?: arabicModelDir
        if (!modelDir.isDirectory) {
            return modelMissingProbeResult("Sherpa-ONNX TTS", 0L, "Missing selected Piper model dir.")
        }
        val onnx = modelDir.listFiles()?.firstOrNull { it.extension == "onnx" }
            ?: return modelMissingProbeResult("Sherpa-ONNX TTS", 0L, "Missing .onnx in ${modelDir.absolutePath}")
        return try {
            SherpaNativeLibraries.load()
            val config = getOfflineTtsConfig(
                modelDir = modelDir.absolutePath,
                modelName = onnx.name,
                acousticModelName = "",
                vocoder = "",
                voices = "",
                lexicon = "",
                dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                numThreads = 2,
            )
            val tts = OfflineTts(config = config)
            try {
                val audio = tts.generate("runtime smoke", sid = 0, speed = 1.0f)
                if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                    smokeFailedProbeResult(
                        "Sherpa-ONNX TTS",
                        System.currentTimeMillis() - started,
                        "EMPTY_AUDIO",
                        "Sherpa generated no audio samples.",
                    )
                } else {
                    outputWav.parentFile?.mkdirs()
                    audio.save(outputWav.absolutePath)
                    RuntimeProbeResult(
                        status = RuntimeProbeStatus.AVAILABLE,
                        backendLabel = "Sherpa-ONNX TTS",
                        outputPreview = "samples=${audio.samples.size} sampleRate=${audio.sampleRate} wav=${outputWav.name}",
                        elapsedMs = System.currentTimeMillis() - started,
                        errorCode = null,
                        errorMessage = null,
                    )
                }
            } finally {
                tts.release()
            }
        } catch (missing: UnsatisfiedLinkError) {
            runtimeMissingProbeResult("Sherpa-ONNX TTS", System.currentTimeMillis() - started, "SHERPA_LIBRARY_MISSING", missing.message ?: missing.javaClass.simpleName)
        } catch (missing: NoClassDefFoundError) {
            runtimeMissingProbeResult("Sherpa-ONNX TTS", System.currentTimeMillis() - started, "SHERPA_CLASS_MISSING", missing.message ?: missing.javaClass.simpleName)
        } catch (t: Throwable) {
            smokeFailedProbeResult("Sherpa-ONNX TTS", System.currentTimeMillis() - started, t.javaClass.simpleName, t.message ?: t.javaClass.name)
        }
    }

    fun synthesize(modelDir: File, text: String, outputWav: File, voiceId: String): TtsSynthesisResult {
        require(modelDir.isDirectory) { "Missing Piper voice directory: ${modelDir.absolutePath}" }
        val onnx = modelDir.listFiles()?.firstOrNull { it.extension == "onnx" }
            ?: error("Missing Piper .onnx in ${modelDir.absolutePath}")
        val espeakData = File(modelDir, "espeak-ng-data")
        require(espeakData.isDirectory) { "Missing espeak-ng-data in ${modelDir.absolutePath}" }

        SherpaNativeLibraries.load()
        val config = getOfflineTtsConfig(
            modelDir = modelDir.absolutePath,
            modelName = onnx.name,
            acousticModelName = "",
            vocoder = "",
            voices = "",
            lexicon = "",
            dataDir = espeakData.absolutePath,
            dictDir = "",
            ruleFsts = "",
            ruleFars = "",
            numThreads = 2,
        )
        val tts = OfflineTts(config = config)
        try {
            val audio = tts.generate(text, sid = 0, speed = 1.0f)
            require(audio.samples.isNotEmpty()) { "Sherpa/Piper generated no samples." }
            require(audio.sampleRate > 0) { "Sherpa/Piper returned invalid sample rate ${audio.sampleRate}." }
            outputWav.parentFile?.mkdirs()
            audio.save(outputWav.absolutePath)
            val duration = audio.samples.size.toDouble() / audio.sampleRate.toDouble()
            Log.i(
                TAG,
                "operation=TTS status=COMPLETED sampleRate=${audio.sampleRate} " +
                    "samples=${audio.samples.size} durationMs=${(duration * 1000.0).toLong()}",
            )
            return TtsSynthesisResult(
                outputWav = outputWav,
                sampleRate = audio.sampleRate,
                sampleCount = audio.samples.size,
                durationSeconds = duration,
                voiceId = voiceId,
            )
        } finally {
            tts.release()
        }
    }
}

private object SherpaNativeLibraries {
    @Volatile
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        System.loadLibrary("onnxruntime")
        System.loadLibrary("sherpa-onnx-jni")
        loaded = true
    }
}
