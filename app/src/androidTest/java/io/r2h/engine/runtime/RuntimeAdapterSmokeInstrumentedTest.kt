package io.r2h.engine.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.r2h.engine.nativebridge.RuntimeProbeResult
import io.r2h.engine.nativebridge.RuntimeProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "RuntimeAdapterSmoke"

@RunWith(AndroidJUnit4::class)
class RuntimeAdapterSmokeInstrumentedTest {
    @Test
    fun yoloOnnxSmokeMustPassBeforeAvailable() =
        assertAvailable("yolo-onnx-smoke-result.json") { it.runYoloOnnxSmoke() }

    @Test
    fun rerankerSmokeMustPassBeforeAvailable() =
        assertAvailable("reranker-smoke-result.json") { it.runRerankerSmoke() }

    @Test
    fun yoloNcnnSmokeMustPassBeforeAvailable() =
        assertAvailable("yolo-ncnn-smoke-result.json") { it.runYoloNcnnSmoke() }

    @Test
    fun whisperCppSmokeMustPassBeforeAvailable() =
        assertAvailable("stt-smoke-result.json") { it.runSttSmoke() }

    @Test
    fun sherpaTtsSmokeMustPassBeforeAvailable() =
        assertAvailable("tts-smoke-result.json") { it.runTtsSmoke() }

    @Test
    fun qwenImageSmokeMustPassBeforeAvailable() =
        assertAvailable("qwen-image-smoke-result.json") { it.runQwenImageSmoke() }

    private fun assertAvailable(
        evidenceName: String,
        smoke: (RuntimeSmokeTestRunner) -> RuntimeProbeResult,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runner = RuntimeSmokeTestRunner(context)
        val result = smoke(runner)
        val evidence = runner.evidenceFile(evidenceName)

        Log.i(TAG, "$evidenceName result: $result")
        Log.i(
            TAG,
            "Pull evidence with: adb shell run-as ${context.packageName} " +
                "cat ${evidence.absolutePath} > $evidenceName",
        )

        assertEquals(result.errorMessage, RuntimeProbeStatus.AVAILABLE, result.status)
    }
}
