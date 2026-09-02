package io.r2h.engine.nativebridge

import android.util.Log
import com.k2fsa.sherpa.onnx.AudioEvent
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "SherpaOnnxAudioTagging"
private const val BACKEND_LABEL = "Sherpa-ONNX CED audio tagging"

data class AudioTaggingResult(
    val audioFile: File,
    val sampleRate: Int,
    val durationSeconds: Double,
    val events: List<AudioTaggingEvent>,
)

data class AudioTaggingEvent(
    val label: String,
    val index: Int,
    val confidence: Float,
)

class SherpaOnnxAudioTaggingRuntimeAdapter {
    fun open(modelFile: File, labelsFile: File, topK: Int): AudioTaggingSession {
        require(modelFile.isFile) { "Missing CED model file: ${modelFile.absolutePath}" }
        require(labelsFile.isFile) { "Missing CED labels file: ${labelsFile.absolutePath}" }

        SherpaAudioTaggingNativeLibraries.load()
        val config = AudioTaggingConfig(
            model = AudioTaggingModelConfig(
                ced = modelFile.absolutePath,
                numThreads = 2,
                debug = false,
            ),
            labels = labelsFile.absolutePath,
            topK = topK,
        )
        val tagger = AudioTagging(config = config)
        Log.i(
            TAG,
            "operation=AUDIO_TAGGING status=LOADED topK=$topK",
        )
        return AudioTaggingSession(tagger, modelFile, labelsFile, topK)
    }
}

class AudioTaggingSession internal constructor(
    private val tagger: AudioTagging,
    val modelFile: File,
    val labelsFile: File,
    val topK: Int,
) : Closeable {
    @Synchronized
    fun tag(wavFile: File): AudioTaggingResult {
        require(wavFile.isFile) { "Missing proof WAV file: ${wavFile.absolutePath}" }
        val wav = readPcm16WavAsMonoFloat(wavFile)
        val stream = tagger.createStream()
        try {
            stream.acceptWaveform(wav.samples, wav.sampleRate)
            val events = tagger.compute(stream, topK)
                .map(AudioEvent::toResultEvent)
            Log.i(
                TAG,
                "operation=AUDIO_TAGGING status=COMPLETED eventCount=${events.size} " +
                    "sampleCount=${wav.samples.size} sampleRate=${wav.sampleRate} topK=$topK",
            )
            return AudioTaggingResult(
                audioFile = wavFile,
                sampleRate = wav.sampleRate,
                durationSeconds = wav.samples.size.toDouble() / wav.sampleRate.toDouble(),
                events = events,
            )
        } finally {
            stream.release()
        }
    }

    override fun close() {
        tagger.release()
    }
}

private fun AudioEvent.toResultEvent(): AudioTaggingEvent =
    AudioTaggingEvent(label = name, index = index, confidence = prob)

private data class AudioTaggingPcmAudio(val samples: FloatArray, val sampleRate: Int)

private fun readPcm16WavAsMonoFloat(file: File): AudioTaggingPcmAudio {
    RandomAccessFile(file, "r").use { wav ->
        require(wav.readAscii(4) == "RIFF") { "Not a RIFF WAV file: ${file.absolutePath}" }
        wav.skipBytes(4)
        require(wav.readAscii(4) == "WAVE") { "Not a WAVE file: ${file.absolutePath}" }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var data = ByteArray(0)

        while (wav.filePointer < wav.length()) {
            val chunk = wav.readAscii(4)
            val size = wav.readIntLe()
            when (chunk) {
                "fmt " -> {
                    val audioFormat = wav.readShortLe()
                    channels = wav.readShortLe()
                    sampleRate = wav.readIntLe()
                    wav.skipBytes(6)
                    bitsPerSample = wav.readShortLe()
                    if (size > 16) wav.skipBytes(size - 16)
                    require(audioFormat == 1) { "Only uncompressed PCM WAV is supported." }
                }
                "data" -> {
                    data = ByteArray(size)
                    wav.readFully(data)
                }
                else -> wav.skipBytes(size)
            }
            if (size % 2 == 1) wav.skipBytes(1)
        }

        require(sampleRate > 0) { "WAV sample rate was not found." }
        require(channels > 0) { "WAV channel count was not found." }
        require(bitsPerSample == 16) { "Only PCM16 WAV is supported; found $bitsPerSample bits." }
        require(data.isNotEmpty()) { "WAV data chunk is empty." }

        val shorts = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frameCount = shorts.remaining() / channels
        val samples = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var mixed = 0f
            for (channel in 0 until channels) {
                mixed += shorts.get(frame * channels + channel) / 32768.0f
            }
            samples[frame] = mixed / channels.toFloat()
        }
        return AudioTaggingPcmAudio(samples, sampleRate)
    }
}

private fun RandomAccessFile.readAscii(length: Int): String =
    ByteArray(length).also { readFully(it) }.toString(Charsets.US_ASCII)

private fun RandomAccessFile.readIntLe(): Int =
    Integer.reverseBytes(readInt())

private fun RandomAccessFile.readShortLe(): Int =
    java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff

private object SherpaAudioTaggingNativeLibraries {
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
