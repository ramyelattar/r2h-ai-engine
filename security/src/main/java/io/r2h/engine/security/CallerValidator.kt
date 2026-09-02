package io.r2h.engine.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

private const val TAG = "CallerValidator"
private val SHA256_HEX = Regex("^[0-9A-F]{64}$")

/**
 * Resolves the packages and current APK signing identity attached to a UID.
 *
 * The Android implementation uses GET_SIGNING_CERTIFICATES, available across
 * the application's complete minSdk range. Package names are descriptive only;
 * authorization is based on the complete current signer set.
 */
internal interface PackageSigningIdentitySource {
    fun packagesForUid(uid: Int): List<String>
    fun currentSignerSha256(packageName: String): Set<String>
}

internal class AndroidPackageSigningIdentitySource(
    private val packageManager: PackageManager,
) : PackageSigningIdentitySource {
    override fun packagesForUid(uid: Int): List<String> =
        packageManager.getPackagesForUid(uid)?.toList().orEmpty()

    @Suppress("DEPRECATION")
    override fun currentSignerSha256(packageName: String): Set<String> {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signingInfo = packageInfo.signingInfo ?: return emptySet()
        return signingInfo.apkContentsSigners
            .orEmpty()
            .mapTo(linkedSetOf()) { signature -> sha256Hex(signature.toByteArray()) }
    }
}

/**
 * Fail-closed defense-in-depth validation for Binder callers.
 *
 * The service's signature permission remains the outer perimeter. This class
 * independently requires every package associated with the incoming UID to
 * have a complete current signer set matching an explicitly trusted signing
 * identity. The engine APK's own current signer set is the default trust anchor.
 */
class CallerValidator internal constructor(
    private val identitySource: PackageSigningIdentitySource,
    trustedSigningIdentities: Set<Set<String>>,
) {
    private val trustedSigningIdentities: Set<Set<String>> = trustedSigningIdentities
        .mapNotNull(::canonicalSigningIdentityOrNull)
        .toSet()

    constructor(context: Context) : this(
        identitySource = AndroidPackageSigningIdentitySource(context.packageManager),
        trustedSigningIdentities = resolveTrustedSigningIdentities(context),
    )

    fun validate(callingUid: Int): ValidationResult {
        if (trustedSigningIdentities.isEmpty()) {
            safeLog("Rejected UID $callingUid: trust policy is empty")
            return ValidationResult.Denied("TRUST_POLICY_EMPTY")
        }

        val packages = try {
            identitySource.packagesForUid(callingUid)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .sorted()
        } catch (_: Exception) {
            safeLog("Rejected UID $callingUid: package resolution failed")
            return ValidationResult.Denied("CALLER_IDENTITY_UNRESOLVABLE")
        }
        if (packages.isEmpty()) {
            safeLog("Rejected UID $callingUid: no packages resolved")
            return ValidationResult.Denied("CALLER_IDENTITY_UNRESOLVABLE")
        }

        for (packageName in packages) {
            val identity = try {
                canonicalSigningIdentityOrNull(identitySource.currentSignerSha256(packageName))
            } catch (_: Exception) {
                safeLog("Rejected UID $callingUid: certificate lookup failed")
                return ValidationResult.Denied("CALLER_CERTIFICATE_UNRESOLVABLE")
            }
            if (identity == null) {
                safeLog("Rejected UID $callingUid: signing identity missing")
                return ValidationResult.Denied("CALLER_CERTIFICATE_UNRESOLVABLE")
            }
            if (identity !in trustedSigningIdentities) {
                safeLog("Rejected UID $callingUid: signing identity mismatch")
                return ValidationResult.Denied("SIGNING_IDENTITY_NOT_TRUSTED")
            }
        }

        return ValidationResult.Allowed(packages.first())
    }

    fun isTrustedPackage(packageName: String): Boolean {
        if (trustedSigningIdentities.isEmpty() || packageName.isBlank()) return false
        return try {
            canonicalSigningIdentityOrNull(identitySource.currentSignerSha256(packageName))
                ?.let(trustedSigningIdentities::contains)
                ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun safeLog(message: String) {
        runCatching { Log.d(TAG, message) }
    }
}

object TrustedClientCatalog {
    data class TrustedClient(
        val packageName: String,
        val displayName: String,
        /** Canonical uppercase SHA-256 hex without separators. */
        val certificateSha256: String? = null,
    )

    val externalApps: List<TrustedClient> = emptyList()
}

sealed interface ValidationResult {
    data class Allowed(val packageName: String) : ValidationResult
    data class Denied(val reason: String) : ValidationResult
}

private fun resolveTrustedSigningIdentities(context: Context): Set<Set<String>> {
    val source = AndroidPackageSigningIdentitySource(context.packageManager)
    val hostIdentity = try {
        canonicalSigningIdentityOrNull(source.currentSignerSha256(context.packageName))
    } catch (_: Exception) {
        null
    } ?: return emptySet()

    val explicitCompanionIdentities = TrustedClientCatalog.externalApps
        .mapNotNull { client ->
            client.certificateSha256
                ?.let(::canonicalDigestOrNull)
                ?.let(::setOf)
        }
    return (listOf(hostIdentity) + explicitCompanionIdentities).toSet()
}

private fun canonicalSigningIdentityOrNull(digests: Set<String>): Set<String>? {
    if (digests.isEmpty()) return null
    val canonical = digests.mapNotNull(::canonicalDigestOrNull).toSet()
    return canonical.takeIf { it.size == digests.size }
}

private fun canonicalDigestOrNull(digest: String): String? =
    digest.trim().uppercase().takeIf(SHA256_HEX::matches)

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = CharArray(digest.size * 2)
    val alphabet = "0123456789ABCDEF"
    digest.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        hex[index * 2] = alphabet[value ushr 4]
        hex[index * 2 + 1] = alphabet[value and 0x0F]
    }
    return String(hex)
}
