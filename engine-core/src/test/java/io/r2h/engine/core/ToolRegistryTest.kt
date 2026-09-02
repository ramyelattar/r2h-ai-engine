package io.r2h.engine.core

import io.r2h.engine.api.model.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ToolRegistry].
 *
 * Covers:
 * - register / replace built-in tools
 * - unregister (exists and absent)
 * - get by name (found and not found)
 * - getAll returns alphabetically sorted list
 * - isEmpty before and after registration
 * - blank-name registration is rejected
 * - resolveForSession with empty session tools (returns built-ins)
 * - resolveForSession with session overrides (session wins same-name)
 * - resolveForSession with new-only session tools (added to catalog)
 * - resolveForSession when registry is empty (returns session tools only)
 */
class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        registry = ToolRegistry()
    }

    // ── register ───────────────────────────────────────────────────────────────

    @Test
    fun `register stores tool and get retrieves it`() {
        val tool = tool("capture_image", "Take a photo")
        registry.register(tool)
        assertEquals(tool, registry.get("capture_image"))
    }

    @Test
    fun `register replaces existing tool with same name`() {
        registry.register(tool("zoom", "Zoom in"))
        val updated = tool("zoom", "Zoom in or out")
        registry.register(updated)
        assertEquals("Zoom in or out", registry.get("zoom")?.description)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `register with blank name throws IllegalArgumentException`() {
        registry.register(tool("   ", "desc"))
    }

    // ── unregister ─────────────────────────────────────────────────────────────

    @Test
    fun `unregister returns true and removes tool`() {
        registry.register(tool("flash", "Toggle flash"))
        val removed = registry.unregister("flash")
        assertTrue(removed)
        assertNull(registry.get("flash"))
    }

    @Test
    fun `unregister returns false for absent tool`() {
        val removed = registry.unregister("nonexistent")
        assertFalse(removed)
    }

    // ── get ────────────────────────────────────────────────────────────────────

    @Test
    fun `get returns null when registry is empty`() {
        assertNull(registry.get("any"))
    }

    @Test
    fun `get returns null for unregistered name`() {
        registry.register(tool("flip", "Flip camera"))
        assertNull(registry.get("missing"))
    }

    // ── getAll ─────────────────────────────────────────────────────────────────

    @Test
    fun `getAll returns empty list when nothing registered`() {
        assertTrue(registry.getAll().isEmpty())
    }

    @Test
    fun `getAll returns all tools sorted alphabetically by name`() {
        registry.register(tool("zoom", "Zoom"))
        registry.register(tool("apply_filter", "Apply filter"))
        registry.register(tool("capture", "Capture"))

        val names = registry.getAll().map { it.name }
        assertEquals(listOf("apply_filter", "capture", "zoom"), names)
    }

    // ── isEmpty ────────────────────────────────────────────────────────────────

    @Test
    fun `isEmpty returns true when no tools registered`() {
        assertTrue(registry.isEmpty())
    }

    @Test
    fun `isEmpty returns false after registration`() {
        registry.register(tool("t", "desc"))
        assertFalse(registry.isEmpty())
    }

    @Test
    fun `isEmpty returns true after all tools unregistered`() {
        registry.register(tool("t", "desc"))
        registry.unregister("t")
        assertTrue(registry.isEmpty())
    }

    // ── resolveForSession ──────────────────────────────────────────────────────

    @Test
    fun `resolveForSession with empty session tools returns all built-ins sorted`() {
        registry.register(tool("zoom", "Zoom"))
        registry.register(tool("apply_filter", "Apply filter"))

        val resolved = registry.resolveForSession(emptyList())
        assertEquals(listOf("apply_filter", "zoom"), resolved.map { it.name })
    }

    @Test
    fun `resolveForSession session tool overrides built-in with same name`() {
        registry.register(ToolDefinition("mirror", "Built-in mirror"))
        val sessionMirror = ToolDefinition("mirror", "Session mirror")
        val resolved = registry.resolveForSession(listOf(sessionMirror))

        val mirrorInResult = resolved.single { it.name == "mirror" }
        assertEquals("Session mirror", mirrorInResult.description)
    }

    @Test
    fun `resolveForSession session-only tools appear in result alongside built-ins`() {
        registry.register(tool("capture", "Capture"))
        val sessionTool = tool("apply_ar", "Apply AR effect")
        val resolved = registry.resolveForSession(listOf(sessionTool))

        val names = resolved.map { it.name }
        assertTrue("apply_ar should be in resolved list", "apply_ar" in names)
        assertTrue("capture should be in resolved list", "capture" in names)
    }

    @Test
    fun `resolveForSession result is sorted alphabetically by name`() {
        registry.register(tool("zoom", "Zoom"))
        registry.register(tool("capture", "Capture"))
        val sessionTools = listOf(tool("apply_filter", "Apply filter"))

        val names = registry.resolveForSession(sessionTools).map { it.name }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `resolveForSession with empty registry returns session tools only sorted`() {
        val sessionTools = listOf(
            tool("zoom", "Zoom"),
            tool("capture", "Capture"),
        )
        val names = registry.resolveForSession(sessionTools).map { it.name }
        assertEquals(listOf("capture", "zoom"), names)
    }

    @Test
    fun `resolveForSession multiple session overrides all applied`() {
        registry.register(tool("a", "Built-in A"))
        registry.register(tool("b", "Built-in B"))

        val sessionTools = listOf(
            tool("a", "Session A"),
            tool("b", "Session B"),
        )
        val resolved = registry.resolveForSession(sessionTools)
        assertEquals(2, resolved.size)
        assertTrue(resolved.all { it.description.startsWith("Session") })
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun tool(name: String, description: String = "desc"): ToolDefinition =
        ToolDefinition(name = name, description = description)
}
