package io.r2h.engine.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.r2h.engine.nativebridge.RuntimeProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "QwenTextSmoke"

@RunWith(AndroidJUnit4::class)
class QwenTextRuntimeSmokeInstrumentedTest {
    @Test
    fun qwenTextCreateContextAndGenerateMustPassBeforeAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runner = RuntimeSmokeTestRunner(context)
        val result = runner.runQwenTextSmoke()
        val evidence = runner.evidenceFile()

        Log.i(TAG, "Qwen text smoke result: $result")
        Log.i(
            TAG,
            "Pull evidence with: adb shell run-as ${context.packageName} " +
                "cat ${evidence.absolutePath} > qwen-text-smoke-result.json",
        )

        assertEquals(RuntimeProbeStatus.AVAILABLE, result.status)
    }
}
