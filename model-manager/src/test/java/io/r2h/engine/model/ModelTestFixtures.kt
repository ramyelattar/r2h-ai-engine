package io.r2h.engine.model

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal fun minimalGgufBytes(
    version: Int = 3,
    tensorCount: Long = 0,
    metadataCount: Long = 0,
): ByteArray = ByteBuffer.allocate(24)
    .order(ByteOrder.LITTLE_ENDIAN)
    .put(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
    .putInt(version)
    .putLong(tensorCount)
    .putLong(metadataCount)
    .array()

internal fun File.writeMinimalGguf(version: Int = 3, suffix: ByteArray = byteArrayOf()): File = apply {
    parentFile?.mkdirs()
    writeBytes(minimalGgufBytes(version = version) + suffix)
}

internal fun sha256Of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun sha256Of(file: File): String = sha256Of(file.readBytes())

internal fun modelRecord(
    id: String = "model-1",
    displayName: String = "Test Model",
    absolutePath: String = "/data/user/0/io.r2h.engine/files/models/$id.gguf",
    sizeBytes: Long = 24L,
    sha256: String = "ab".repeat(32),
    quantization: String = "Q4_K_M",
) = LocalModelRecord(
    modelId = id,
    displayName = displayName,
    absolutePath = absolutePath,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    quantization = quantization,
)
