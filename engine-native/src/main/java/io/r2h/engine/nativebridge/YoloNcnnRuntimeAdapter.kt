package io.r2h.engine.nativebridge

import java.io.File

class YoloNcnnRuntimeAdapter {
    fun runSmoke(paramFile: File, binFile: File): RuntimeProbeResult {
        val started = System.currentTimeMillis()
        if (!paramFile.isFile) {
            return modelMissingProbeResult("NCNN", 0L, paramFile.absolutePath)
        }
        if (!binFile.isFile) {
            return modelMissingProbeResult("NCNN", 0L, binFile.absolutePath)
        }
        return try {
            runtimeProbeResultFromNativeJson(
                json = NativeRuntimeSmokeBridge.yoloNcnnSmoke(paramFile.absolutePath, binFile.absolutePath),
                elapsedMs = System.currentTimeMillis() - started,
                fallbackBackend = "NCNN",
            )
        } catch (missing: UnsatisfiedLinkError) {
            runtimeMissingProbeResult("NCNN", System.currentTimeMillis() - started, "NCNN_LIBRARY_MISSING", missing.message ?: missing.javaClass.simpleName)
        } catch (missing: NoClassDefFoundError) {
            runtimeMissingProbeResult("NCNN", System.currentTimeMillis() - started, "NCNN_BRIDGE_MISSING", missing.message ?: missing.javaClass.simpleName)
        } catch (t: Throwable) {
            smokeFailedProbeResult("NCNN", System.currentTimeMillis() - started, t.javaClass.simpleName, t.message ?: t.javaClass.name)
        }
    }
}
