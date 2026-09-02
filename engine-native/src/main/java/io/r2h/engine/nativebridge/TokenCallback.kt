package io.r2h.engine.nativebridge

/**
 * Streaming token callback invoked by the native inference loop for each generated token.
 *
 * Declared as a SAM-compatible functional interface so it can be passed to JNI as a
 * jobject and invoked from C++ via [JNIEnv::CallVoidMethod] without additional wrappers.
 *
 * Threading contract: [onToken] is called on whatever thread [NativeInferenceEngine.generate]
 * is executing on — always a background thread (Dispatchers.IO in engine-core). The
 * implementation must not block; heavy work should be dispatched to another scope.
 */
fun interface TokenCallback {
    fun onToken(token: String)
}
