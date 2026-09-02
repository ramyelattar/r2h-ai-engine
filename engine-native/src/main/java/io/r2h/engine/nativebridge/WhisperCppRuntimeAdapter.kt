package io.r2h.engine.nativebridge

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperCppRuntimeAdapter {
    fun runSmoke(modelFile: File, wavFile: File): RuntimeProbeResult {
        val started = System.currentTimeMillis()
        if (!modelFile.isFile) {
            return modelMissingProbeResult("whisper.cpp", 0L, modelFile.absolutePath)
        }
        if (!wavFile.isFile) {
            return modelMissingProbeResult("whisper.cpp", 0L, wavFile.absolutePath)
        }
        return try {
            val wav = readPcm16Wav(wavFile)
            runtimeProbeResultFromNativeJson(
                json = NativeRuntimeSmokeBridge.whisperCppSmoke(modelFile.absolutePath, wav.samples, wav.sampleRate),
                elapsedMs = System.currentTimeMillis() - started,
                fallbackBackend = "whisper.cpp",
            )
        } catch (missing: UnsatisfiedLinkError) {
            runtimeMissingProbeResult("whisper.cpp", System.currentTimeMillis() - started, "WHISPER_LIBRARY_MISSING", missing.message ?: missing.javaClass.simpleName)
        } catch (missing: NoClassDefFoundError) {
            runtimeMissingProbeResult("whisper.cpp", System.currentTimeMillis() - started, "WHISPER_BRIDGE_MISSING", missing.message ?: missing.javaClass.simpleName)
        } catch (t: Throwable) {
            smokeFailedProbeResult("whisper.cpp", System.currentTimeMillis() - started, t.javaClass.simpleName, t.message ?: t.javaClass.name)
        }
    }

    private fun readPcm16Wav(file: File): PcmAudio {
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
                        require(audioFormat == 1) { "Only PCM WAV is supported." }
                    }
                    "data" -> {
                        data = ByteArray(size)
                        wav.readFully(data)
                    }
                    else -> wav.skipBytes(size)
                }
                if (size % 2 == 1) wav.skipBytes(1)
            }
            require(sampleRate == 16000) { "Whisper smoke WAV must be 16 kHz; was $sampleRate." }
            require(channels == 1) { "Whisper smoke WAV must be mono; was $channels channels." }
            require(bitsPerSample == 16) { "Whisper smoke WAV must be PCM16; was $bitsPerSample bits." }
            val shorts = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = FloatArray(shorts.remaining())
            for (i in samples.indices) {
                samples[i] = shorts.get(i) / 32768.0f
            }
            return PcmAudio(samples, sampleRate)
        }
    }
}

private data class PcmAudio(val samples: FloatArray, val sampleRate: Int)

private fun RandomAccessFile.readAscii(length: Int): String =
    ByteArray(length).also { readFully(it) }.toString(Charsets.US_ASCII)

private fun RandomAccessFile.readIntLe(): Int =
    Integer.reverseBytes(readInt())

private fun RandomAccessFile.readShortLe(): Int =
    java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff
