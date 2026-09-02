package io.r2h.engine.core

import io.r2h.engine.api.IR2hGenerateCallback
import io.r2h.engine.api.model.GenerateRequest

/**
 * Internal representation of a generation job held in [RequestQueue].
 *
 * Wraps the public [GenerateRequest] together with the callback binder and the
 * UID of the calling process. The UID is captured at enqueue time (not at
 * dequeue time) because [android.os.Binder.getCallingUid] returns the calling
 * process UID only while executing on the Binder thread that received the call.
 *
 * @param request    The public contract object received over AIDL.
 * @param callback   The client-side binder to deliver results to.
 * @param callerUid  UID of the process that submitted this request.
 */
internal data class InferenceRequest(
    val request: GenerateRequest,
    val callback: IR2hGenerateCallback,
    val callerUid: Int,
)
