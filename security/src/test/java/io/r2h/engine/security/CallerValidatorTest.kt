package io.r2h.engine.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerValidatorTest {

    @Test
    fun `trusted signing identity is allowed`() {
        val source = FakePackageSigningIdentitySource(
            packagesByUid = mapOf(1001 to listOf("io.r2h.client")),
            signersByPackage = mapOf("io.r2h.client" to setOf(TRUSTED_DIGEST)),
        )

        val result = CallerValidator(source, setOf(setOf(TRUSTED_DIGEST))).validate(1001)

        assertEquals(ValidationResult.Allowed("io.r2h.client"), result)
    }

    @Test
    fun `untrusted signing identity is denied`() {
        val source = FakePackageSigningIdentitySource(
            packagesByUid = mapOf(1002 to listOf("io.r2h.untrusted")),
            signersByPackage = mapOf("io.r2h.untrusted" to setOf(UNTRUSTED_DIGEST)),
        )

        val result = CallerValidator(source, setOf(setOf(TRUSTED_DIGEST))).validate(1002)

        assertDenied(result, "SIGNING_IDENTITY_NOT_TRUSTED")
    }

    @Test
    fun `empty trusted identity set denies every caller`() {
        val source = FakePackageSigningIdentitySource(
            packagesByUid = mapOf(1003 to listOf("io.r2h.client")),
            signersByPackage = mapOf("io.r2h.client" to setOf(TRUSTED_DIGEST)),
        )

        val result = CallerValidator(source, emptySet()).validate(1003)

        assertDenied(result, "TRUST_POLICY_EMPTY")
    }

    @Test
    fun `package resolution failure is denied`() {
        val source = FakePackageSigningIdentitySource(
            packagesFailure = SecurityException("package lookup denied"),
        )

        val result = CallerValidator(source, setOf(setOf(TRUSTED_DIGEST))).validate(1004)

        assertDenied(result, "CALLER_IDENTITY_UNRESOLVABLE")
    }

    @Test
    fun `certificate lookup exception is denied`() {
        val source = FakePackageSigningIdentitySource(
            packagesByUid = mapOf(1005 to listOf("io.r2h.client")),
            signerFailures = mapOf("io.r2h.client" to SecurityException("certificate lookup failed")),
        )

        val result = CallerValidator(source, setOf(setOf(TRUSTED_DIGEST))).validate(1005)

        assertDenied(result, "CALLER_CERTIFICATE_UNRESOLVABLE")
    }

    @Test
    fun `all packages sharing a uid must satisfy the signing policy`() {
        val source = FakePackageSigningIdentitySource(
            packagesByUid = mapOf(
                1006 to listOf("io.r2h.client", "io.r2h.client.shared"),
                1007 to listOf("io.r2h.client", "io.r2h.untrusted.shared"),
            ),
            signersByPackage = mapOf(
                "io.r2h.client" to setOf(TRUSTED_DIGEST),
                "io.r2h.client.shared" to setOf(TRUSTED_DIGEST),
                "io.r2h.untrusted.shared" to setOf(UNTRUSTED_DIGEST),
            ),
        )
        val validator = CallerValidator(source, setOf(setOf(TRUSTED_DIGEST)))

        assertEquals(ValidationResult.Allowed("io.r2h.client"), validator.validate(1006))
        assertDenied(validator.validate(1007), "SIGNING_IDENTITY_NOT_TRUSTED")
    }

    @Test
    fun `multi-signer package must match the complete trusted signer set`() {
        val source = FakePackageSigningIdentitySource(
            packagesByUid = mapOf(
                1008 to listOf("io.r2h.complete"),
                1009 to listOf("io.r2h.partial"),
            ),
            signersByPackage = mapOf(
                "io.r2h.complete" to setOf(TRUSTED_DIGEST, SECOND_TRUSTED_DIGEST),
                "io.r2h.partial" to setOf(TRUSTED_DIGEST),
            ),
        )
        val validator = CallerValidator(
            source,
            setOf(setOf(TRUSTED_DIGEST, SECOND_TRUSTED_DIGEST)),
        )

        assertEquals(ValidationResult.Allowed("io.r2h.complete"), validator.validate(1008))
        assertDenied(validator.validate(1009), "SIGNING_IDENTITY_NOT_TRUSTED")
    }

    private fun assertDenied(result: ValidationResult, reason: String) {
        assertTrue(result is ValidationResult.Denied)
        assertEquals(reason, (result as ValidationResult.Denied).reason)
    }

    private class FakePackageSigningIdentitySource(
        private val packagesByUid: Map<Int, List<String>> = emptyMap(),
        private val signersByPackage: Map<String, Set<String>> = emptyMap(),
        private val packagesFailure: RuntimeException? = null,
        private val signerFailures: Map<String, RuntimeException> = emptyMap(),
    ) : PackageSigningIdentitySource {
        override fun packagesForUid(uid: Int): List<String> {
            packagesFailure?.let { throw it }
            return packagesByUid[uid].orEmpty()
        }

        override fun currentSignerSha256(packageName: String): Set<String> {
            signerFailures[packageName]?.let { throw it }
            return signersByPackage[packageName].orEmpty()
        }
    }

    private companion object {
        const val TRUSTED_DIGEST = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val SECOND_TRUSTED_DIGEST = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
        const val UNTRUSTED_DIGEST = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    }
}
