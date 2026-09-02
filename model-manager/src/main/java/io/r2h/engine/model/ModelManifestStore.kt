package io.r2h.engine.model

/** Inactive compatibility wrapper; construction requires an already-owned manifest. */
internal class ModelManifestStore(
    private val manifest: ModelManifest,
) {

    fun snapshot(): ModelRegistrySnapshot = manifest.snapshot()
}
