package io.r2h.engine.core

import io.r2h.engine.api.model.ToolDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry of tools that the AI may call in an orchestrated session.
 *
 * Tools are keyed by [ToolDefinition.name]. Registering a tool with a name that
 * already exists replaces the previous definition (last-write-wins). This is
 * intentional — it allows feature flag updates to change tool descriptions
 * without restarting the engine.
 *
 * The registry holds definitions that come from two sources:
 *  - Engine-level built-ins registered at service startup by the `:app` module
 *  - Per-request overrides supplied in [io.r2h.engine.api.model.SessionContext.availableTools]
 *
 * Built-ins provide the fallback catalog. Per-request overrides in SessionContext
 * take precedence because [SessionOrchestrator] merges them at call time.
 */
class ToolRegistry {

    private val builtins = ConcurrentHashMap<String, ToolDefinition>()

    /** Registers or replaces a built-in tool. */
    fun register(tool: ToolDefinition) {
        require(tool.name.isNotBlank()) { "Tool name must not be blank." }
        builtins[tool.name] = tool
    }

    /** Removes a built-in tool. Returns true if it existed. */
    fun unregister(name: String): Boolean = builtins.remove(name) != null

    /** Returns the built-in tool with the given name, or null if not registered. */
    fun get(name: String): ToolDefinition? = builtins[name]

    /** Returns all registered built-in tools sorted by name. */
    fun getAll(): List<ToolDefinition> =
        builtins.values.toList().sortedBy { it.name }

    /** Returns true when no built-in tools are registered. */
    fun isEmpty(): Boolean = builtins.isEmpty()

    /**
     * Returns the effective tool catalog for a session. Per-request tools from
     * [sessionTools] override built-ins with the same name; built-ins fill the
     * remaining slots.
     */
    fun resolveForSession(sessionTools: List<ToolDefinition>): List<ToolDefinition> {
        if (sessionTools.isEmpty()) return getAll()

        // Merge: session tools override builtins; builtins fill the rest
        val merged = LinkedHashMap<String, ToolDefinition>()
        builtins.forEach { (name, tool) -> merged[name] = tool }
        sessionTools.forEach { tool -> merged[tool.name] = tool }
        return merged.values.toList().sortedBy { it.name }
    }
}
