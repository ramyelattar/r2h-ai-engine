package io.r2h.engine.core

import io.r2h.engine.api.model.ConversationTurn
import io.r2h.engine.api.model.SessionContext
import io.r2h.engine.api.model.ToolDefinition

/**
 * Builds the structured text prompt sent to the local model for session orchestration.
 *
 * Design constraints:
 * - Output must be parseable by [AiDecisionParser] regardless of model family.
 * - Prompt must fit within a configurable token budget. The builder truncates
 *   conversation history (oldest first) when [maxHistoryTurns] is exceeded.
 * - No external dependencies — pure Kotlin string assembly.
 *
 * The prompt instructs the model to respond in a strict JSON format so that
 * [AiDecisionParser] can reliably extract the decision type and arguments.
 */
class SessionPromptBuilder(
    private val maxHistoryTurns: Int = 10,
) {

    /**
     * Returns the full prompt string to submit to the inference backend.
     * When [context.systemInstruction] is non-blank it replaces the default
     * system preamble entirely.
     */
    fun build(context: SessionContext, tools: List<ToolDefinition>): String {
        val sb = StringBuilder()

        val systemBlock = buildSystemBlock(context, tools)
        sb.append(systemBlock)
        sb.append("\n\n")

        val historyTurns = context.conversationHistory.takeLast(maxHistoryTurns)
        if (historyTurns.isNotEmpty()) {
            sb.append("## Conversation History\n")
            historyTurns.forEach { turn ->
                sb.append(formatTurn(turn))
                sb.append("\n")
            }
            sb.append("\n")
        }

        sb.append("## User Request\n")
        sb.append(context.userMessage.trim())
        sb.append("\n\n")

        sb.append(buildResponseInstruction(tools))
        return sb.toString()
    }

    private fun buildSystemBlock(context: SessionContext, tools: List<ToolDefinition>): String {
        if (context.systemInstruction.isNotBlank()) {
            return context.systemInstruction.trim()
        }

        val sb = StringBuilder()
        sb.append("You are an AI assistant integrated into the application \"${context.appId}\".")
        sb.append(" Your role is to decide what action to take in response to the user's request.")

        if (context.appState.isNotBlank() && context.appState != "{}") {
            sb.append("\n\n## Current Application State\n")
            sb.append(context.appState.trim())
        }

        if (tools.isNotEmpty()) {
            sb.append("\n\n## Available Tools\n")
            sb.append("The following tools are available. You may only use tools from this list.\n\n")
            tools.forEachIndexed { i, tool ->
                sb.append("${i + 1}. **${tool.name}**")
                if (tool.category.isNotBlank()) sb.append(" [${tool.category}]")
                sb.append("\n")
                sb.append("   ${tool.description}\n")
                if (tool.parameterSchema.isNotBlank() && tool.parameterSchema != "{}") {
                    sb.append("   Parameters: ${tool.parameterSchema}\n")
                }
            }
        } else {
            sb.append("\n\nNo tools are available. Respond with a text answer only.")
        }

        return sb.toString()
    }

    private fun formatTurn(turn: ConversationTurn): String {
        val roleLabel = when (turn.role.lowercase()) {
            ConversationTurn.ROLE_USER      -> "User"
            ConversationTurn.ROLE_ASSISTANT -> "Assistant"
            ConversationTurn.ROLE_SYSTEM    -> "System"
            else                            -> turn.role.replaceFirstChar(Char::uppercase)
        }
        return "$roleLabel: ${turn.content.trim()}"
    }

    private fun buildResponseInstruction(tools: List<ToolDefinition>): String {
        val sb = StringBuilder()
        sb.append("## Instructions\n")

        if (tools.isEmpty()) {
            sb.append("Respond to the user's request directly.\n\n")
            sb.append("Respond ONLY with valid JSON in exactly this format:\n")
            sb.append("""{"decision":"TEXT_RESPONSE","text_response":"<your answer>","reasoning":"<brief explanation>"}""")
            return sb.toString()
        }

        sb.append("Select the most appropriate action from the available tools, or respond directly if no tool is needed.\n\n")
        sb.append("Respond ONLY with valid JSON in one of these exact formats:\n\n")

        sb.append("Single tool call:\n")
        sb.append("""{"decision":"TOOL_CALL","tool_name":"<tool name>","tool_args":{"param":"value"},"reasoning":"<brief explanation>"}""")
        sb.append("\n\n")

        sb.append("Multi-step plan:\n")
        sb.append("""{"decision":"MULTI_STEP","steps":[{"step":1,"tool":"<name>","args":{},"description":"<what this step does>"}],"reasoning":"<brief explanation>"}""")
        sb.append("\n\n")

        sb.append("Direct text answer (no tool needed):\n")
        sb.append("""{"decision":"TEXT_RESPONSE","text_response":"<your answer>","reasoning":"<brief explanation>"}""")
        sb.append("\n\n")

        sb.append("Need more information:\n")
        sb.append("""{"decision":"CLARIFICATION","text_response":"<your question>"}""")
        sb.append("\n\n")

        sb.append("No action:\n")
        sb.append("""{"decision":"NO_OP","reasoning":"<brief explanation>"}""")
        sb.append("\n\n")

        sb.append("Do not include any text outside the JSON. Do not wrap in markdown code blocks.")
        return sb.toString()
    }
}
