package io.r2h.engine.nativebridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

class QwenImageRuntimeAdapter {
    fun runSmoke(modelFile: File, mmprojFile: File, imageFile: File): RuntimeProbeResult {
        val started = System.currentTimeMillis()
        if (!modelFile.isFile) {
            return modelMissingProbeResult("llama.cpp mtmd", 0L, modelFile.absolutePath)
        }
        if (!mmprojFile.isFile) {
            return modelMissingProbeResult("llama.cpp mtmd", 0L, mmprojFile.absolutePath)
        }
        if (!imageFile.isFile) {
            return modelMissingProbeResult("llama.cpp mtmd", 0L, imageFile.absolutePath)
        }
        return try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: return smokeFailedProbeResult("llama.cpp mtmd", System.currentTimeMillis() - started, "IMAGE_DECODE_FAILED", imageFile.absolutePath)
            val rgb = bitmap.toRgbByteArray()
            runtimeProbeResultFromNativeJson(
                json = NativeRuntimeSmokeBridge.qwenImageMtmdSmoke(
                    modelPath = modelFile.absolutePath,
                    mmprojPath = mmprojFile.absolutePath,
                    rgb = rgb,
                    width = bitmap.width,
                    height = bitmap.height,
                ),
                elapsedMs = System.currentTimeMillis() - started,
                fallbackBackend = "llama.cpp mtmd",
            )
        } catch (missing: UnsatisfiedLinkError) {
            runtimeMissingProbeResult("llama.cpp mtmd", System.currentTimeMillis() - started, "MTMD_LIBRARY_MISSING", missing.message ?: missing.javaClass.simpleName)
        } catch (missing: NoClassDefFoundError) {
            runtimeMissingProbeResult("llama.cpp mtmd", System.currentTimeMillis() - started, "MTMD_BRIDGE_MISSING", missing.message ?: missing.javaClass.simpleName)
        } catch (t: Throwable) {
            smokeFailedProbeResult("llama.cpp mtmd", System.currentTimeMillis() - started, t.javaClass.simpleName, t.message ?: t.javaClass.name)
        }
    }

    private fun Bitmap.toRgbByteArray(): ByteArray {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = ByteArray(width * height * 3)
        var out = 0
        for (pixel in pixels) {
            rgb[out++] = ((pixel shr 16) and 0xff).toByte()
            rgb[out++] = ((pixel shr 8) and 0xff).toByte()
            rgb[out++] = (pixel and 0xff).toByte()
        }
        return rgb
    }
}
