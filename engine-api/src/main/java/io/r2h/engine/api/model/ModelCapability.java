package io.r2h.engine.api.model;

/**
 * Public capability labels exposed to clients and dashboards.
 *
 * These constants are string labels, not runtime proof. The authoritative
 * availability state is EngineTruthSnapshot.
 */
public final class ModelCapability {
    public static final String TEXT_GENERATION = "TEXT_GENERATION";
    public static final String EMBEDDING = "EMBEDDING";
    public static final String RERANKING = "RERANKING";
    public static final String SPEECH_TO_TEXT = "SPEECH_TO_TEXT";
    public static final String TEXT_TO_SPEECH = "TEXT_TO_SPEECH";
    public static final String IMAGE_UNDERSTANDING = "IMAGE_UNDERSTANDING";
    public static final String MULTIMODAL = "MULTIMODAL";

    private ModelCapability() {
    }
}
