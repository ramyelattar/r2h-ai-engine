package io.r2h.engine.api;

import io.r2h.engine.api.model.EngineError;
import io.r2h.engine.api.model.ModalInferenceResult;

oneway interface IModalInferenceCallback {
    void onResult(in ModalInferenceResult result);
    void onError(in EngineError error);
}
