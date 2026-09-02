package io.r2h.engine.automation

import io.r2h.engine.MainSavedStateCodec
import io.r2h.engine.MainUiState
import io.r2h.engine.UiContentPersistence
import io.r2h.engine.tools.ToolAuditStore
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSummaryPrivacyTest {
    @Test
    fun `notification summary remains visible but audit representation contains metadata only`() {
        val title = "SECRET_NOTIFICATION_CANARY_45d2"
        val body = "SECRET_NOTIFICATION_BODY_CANARY_77a1"

        val summary = NotificationSummaryFormatter.format(
            listOf(CapturedNotification("example.package", title, body)),
        )
        val audit = summary.toAuditEntry(timestampMs = 123L)
        val persistedAuditFields = listOf(
            audit.toolId,
            audit.actionSummary,
            audit.result,
            audit.rawDetails,
        ).joinToString("\n")

        assertTrue(summary.displayText.contains(title))
        assertTrue(summary.displayText.contains(body))
        assertFalse(persistedAuditFields.contains(title))
        assertFalse(persistedAuditFields.contains(body))
        assertTrue(persistedAuditFields.contains("capturedRecords=1"))
        assertTrue(persistedAuditFields.contains("contentPersisted=false"))
    }

    @Test
    fun `volatile notification result is omitted from process SavedState`() {
        val notification = "SECRET_NOTIFICATION_CANARY_45d2"
        val codec = MainSavedStateCodec()

        val encoded = codec.encode(
            MainUiState(
                lastAgentResult = notification,
                lastAgentResultPersistence = UiContentPersistence.VOLATILE,
            ),
        )
        val persisted = encoded.values.values.joinToString("\n")

        assertFalse(persisted.contains(notification))
        assertFalse(codec.decode(encoded.values).lastAgentResult.contains(notification))
    }

    @Test
    fun `durable tool audit file receives notification counts but never notification content`() {
        val title = "SECRET_NOTIFICATION_CANARY_45d2"
        val body = "SECRET_NOTIFICATION_BODY_CANARY_77a1"
        val directory = Files.createTempDirectory("notification-audit-privacy").toFile()
        try {
            val auditFile = directory.resolve("tool-audit.jsonl")
            val summary = NotificationSummaryFormatter.format(
                listOf(CapturedNotification("example.package", title, body)),
            )

            assertTrue(ToolAuditStore(auditFile).add(summary.toAuditEntry(timestampMs = 123L)))
            val persisted = auditFile.readText()

            assertFalse(persisted.contains(title))
            assertFalse(persisted.contains(body))
            assertTrue(persisted.contains("capturedRecords=1"))
            assertTrue(persisted.contains("contentPersisted=false"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
