package io.r2h.engine.model

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelPackVerifierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `verify fails closed when sha256 mismatches`() {
        val file = tmp.newFile("model.gguf")
        file.writeText("real bytes", Charsets.UTF_8)

        val artifact = ModelPackArtifact(
            path = "models/qwen2vl/model.gguf",
            sizeBytes = file.length(),
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            extension = ".gguf",
        )

        val result = ModelPackVerifier().verify(file, artifact)

        assertTrue(result is ModelPackVerificationResult.Sha256Mismatch)
        assertTrue(result.reason.startsWith("SHA256_MISMATCH"))
    }
}
