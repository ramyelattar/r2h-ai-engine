package io.r2h.engine.api.model;

/** Runtime availability states for client diagnostics. */
public final class RuntimeStatus {
    public static final String AVAILABLE = "AVAILABLE";
    public static final String LOADING = "LOADING";
    public static final String MODEL_MISSING = "MODEL_MISSING";
    public static final String RUNTIME_MISSING = "RUNTIME_MISSING";
    public static final String INVALID_DESCRIPTOR = "INVALID_DESCRIPTOR";
    public static final String UNSUPPORTED_DEVICE = "UNSUPPORTED_DEVICE";
    public static final String ERROR = "ERROR";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String EXPERIMENTAL = "EXPERIMENTAL";

    private RuntimeStatus() {
    }
}
