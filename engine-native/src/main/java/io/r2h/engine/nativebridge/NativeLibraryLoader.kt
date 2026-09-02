package io.r2h.engine.nativebridge

import android.util.Log

private const val TAG = "NativeLibraryLoader"
private const val LIBRARY_NAME = "r2h_native"

/**
 * Loads the native shared library exactly once, surfacing a clear diagnostic
 * if the .so is missing or the ABI is unsupported.
 *
 * Separated from [NativeInferenceEngine] so that tests can stub out loading
 * without triggering [System.loadLibrary] on the JVM.
 */
internal object NativeLibraryLoader {

    @Volatile private var loaded = false

    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary(LIBRARY_NAME)
                loaded = true
                Log.i(TAG, "Loaded $LIBRARY_NAME successfully")
            } catch (e: UnsatisfiedLinkError) {
                if (!isAndroidRuntime()) {
                    Log.w(TAG, "Skipping $LIBRARY_NAME load on host JVM unit test runtime")
                    return
                }
                // Surface as IllegalStateException so engine-core can convert it to a
                // typed EngineError(INTERNAL_ERROR) rather than crashing the process.
                throw IllegalStateException(
                    "Failed to load native library '$LIBRARY_NAME'. " +
                    "Verify NDK version 27.2.12479018 is installed and the ABI " +
                    "filter includes the device ABI (arm64-v8a required).",
                    e,
                )
            }
        }
    }

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("java.runtime.name", "").contains("Android", ignoreCase = true)
}
