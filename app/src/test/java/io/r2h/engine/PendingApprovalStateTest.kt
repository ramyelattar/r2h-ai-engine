package io.r2h.engine

import io.r2h.engine.tools.ToolRiskLevel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PendingApprovalStateTest {
    private fun approval(id: String = "approval-1") = PendingApprovalState(
        approvalId = id,
        toolId = "calendar",
        title = "Create calendar event",
        detail = "Create the reviewed event",
        risk = ToolRiskLevel.HIGH,
        action = ApprovalActionDescriptor(ApprovalActionType.CALENDAR_INSERT, "reviewed task"),
    )

    @Test
    fun `pending approval remains available after saved state recreation`() {
        val codec = MainSavedStateCodec()
        val encoded = codec.encode(MainUiState(pendingApproval = approval()))

        val restored = codec.decode(encoded.values)

        assertEquals(approval(), restored.pendingApproval)
    }

    @Test
    fun `approval can be claimed for execution only once`() {
        val machine = ApprovalStateMachine(approval())

        val first = machine.claim("approval-1")
        val duplicate = machine.claim("approval-1")

        assertNotNull(first)
        assertEquals(ApprovalStatus.EXECUTING, first?.status)
        assertNull(duplicate)
    }

    @Test
    fun `stale approval id cannot consume a replacement`() {
        val machine = ApprovalStateMachine(approval("new-approval"))

        assertNull(machine.claim("old-approval"))
        assertEquals(ApprovalStatus.PENDING, machine.current()?.status)
        assertEquals("new-approval", machine.current()?.approvalId)
    }

    @Test
    fun `concurrent approval taps produce one execution claim`() {
        val machine = ApprovalStateMachine(approval())
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(
                List(32) { Callable { machine.claim("approval-1") } },
            ).map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it != null })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `completion clears only the executing matching approval`() {
        val machine = ApprovalStateMachine(approval())
        machine.claim("approval-1")

        assertEquals(true, machine.complete("approval-1"))
        assertNull(machine.current())
        assertEquals(false, machine.complete("approval-1"))
    }
}
