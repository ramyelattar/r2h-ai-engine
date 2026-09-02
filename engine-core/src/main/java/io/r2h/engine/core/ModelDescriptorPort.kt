package io.r2h.engine.core

fun interface ModelDescriptorPort {
    fun resolveDescriptor(modelId: String): ModelDescriptor?
}
