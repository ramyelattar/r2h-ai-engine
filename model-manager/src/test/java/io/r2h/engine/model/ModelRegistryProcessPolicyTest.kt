package io.r2h.engine.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryProcessPolicyTest {

    @Test
    fun `only the package engine process is the authoritative writer`() {
        assertTrue(ModelRegistryProcessPolicy.isAuthoritative("io.r2h.engine", "io.r2h.engine:engine"))
        assertFalse(ModelRegistryProcessPolicy.isAuthoritative("io.r2h.engine", "io.r2h.engine"))
        assertFalse(ModelRegistryProcessPolicy.isAuthoritative("io.r2h.engine", "io.r2h.engine:other"))
        assertFalse(ModelRegistryProcessPolicy.isAuthoritative("io.r2h.engine", null))
    }
}
