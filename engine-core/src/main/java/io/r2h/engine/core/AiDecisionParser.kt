package io.r2h.engine.core

import android.util.Log
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "AiDecisionParser"

/**
 * Parses the raw text output of the local model into a typed [AiDecision].
 *
 * Local LLMs frequently produce output with:
 *  - Markdown code fences (```json ... ```)
 *  - Trailing text after the JSON object
 *  - Extra whitespace or newlines
 *  - Truncated JSON when the model hits its token limit
 *
 * This parser applies the following fallback chain:
 *  1. Try to parse the text directly as JSON.
 *  2. Strip markdown code fences and retry.
 *  3. Extract the first `{...}` block from the text and retry.
 *  4. Fall back to [AiDecision.TextResponse] with the raw text as content.
 *
 * No exception ever escapes this class — all parse failures return a
 * [AiDecision.TextResponse] or [AiDecision.NoOp].
 */
class AiDecisionParser {

    fun parse(rawOutput: String): AiDecision {
        if (rawOutput.isBlank()) return AiDecision.NoOp

        val candidates = extractJsonCandidates(rawOutput.trim())
        for (candidate in candidates) {
            val decision = tryParse(candidate)
            if (decision != null) return decision
        }

        // All parse attempts failed — treat raw output as a text response
        Log.d(TAG, "Could not parse model output as JSON; treating as TEXT_RESPONSE")
        return AiDecision.TextResponse(text = rawOutput.trim())
    }

    private fun extractJsonCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()

        // 1. Raw text as-is
        candidates.add(text)

        // 2. Strip markdown code fences: ```json...``` or ```...```
        val fencePattern = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?})\\s*```")
        fencePattern.find(text)?.groups?.get(1)?.value?.let { candidates.add(it) }

        // 3. Extract first {...} block (handles trailing text after the JSON)
        val firstBrace = text.indexOf('{')
        if (firstBrace >= 0) {
            val lastBrace = text.lastIndexOf('}')
            if (lastBrace > firstBrace) {
                candidates.add(text.substring(firstBrace, lastBrace + 1))
            }
        }

        return candidates
    }

    @Suppress("ReturnCount")
    private fun tryParse(jsonText: String): AiDecision? {
        val obj = try {
            JSONObject(jsonText)
        } catch (_: JSONException) {
            return null
        }

        val decision = obj.optString("decision", "").uppercase()
        if (decision.isBlank()) return null

        val reasoning = obj.optString("reasoning", "")

        return when (decision) {
            "TOOL_CALL" -> parseToolCall(obj, reasoning)
            "MULTI_STEP" -> parseMultiStep(obj, reasoning)
            "TEXT_RESPONSE" -> AiDecision.TextResponse(
                text = obj.optString("text_response", ""),
                reasoning = reasoning,
            )
            "CLARIFICATION" -> AiDecision.Clarification(
                question = obj.optString("text_response", ""),
            )
            "NO_OP" -> AiDecision.NoOp
            else -> {
                Log.w(
                    TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.AGENT,
                        status = DiagnosticStatus.REJECTED,
                        inputContent = decision,
                        errorCode = DiagnosticErrorCode.INVALID_INPUT,
                    ),
                )
                null
            }
        }
    }

    private fun parseToolCall(obj: JSONObject, reasoning: String): AiDecision.ToolCall? {
        val toolName = obj.optString("tool_name", "").trim()
        if (toolName.isBlank()) {
            Log.w(TAG, "TOOL_CALL decision missing tool_name")
            return null
        }
        val args = parseStringMap(obj.optJSONObject("tool_args"))
        val confidence = obj.optDouble("confidence", 1.0).toFloat().coerceIn(0f, 1f)
        return AiDecision.ToolCall(
            toolName = toolName,
            args = args,
            reasoning = reasoning,
            confidence = confidence,
        )
    }

    private fun parseMultiStep(obj: JSONObject, reasoning: String): AiDecision.MultiStep? {
        val stepsArray = obj.optJSONArray("steps") ?: run {
            Log.w(TAG, "MULTI_STEP decision missing steps array")
            return null
        }
        val steps = mutableListOf<AiDecision.MultiStep.Step>()
        for (i in 0 until stepsArray.length()) {
            val stepObj = stepsArray.optJSONObject(i) ?: continue
            val toolName = stepObj.optString("tool", "").trim()
            if (toolName.isBlank()) continue
            steps.add(
                AiDecision.MultiStep.Step(
                    index = stepObj.optInt("step", i + 1) - 1,
                    toolName = toolName,
                    args = parseStringMap(stepObj.optJSONObject("args")),
                    description = stepObj.optString("description", ""),
                ),
            )
        }
        if (steps.isEmpty()) {
            Log.w(TAG, "MULTI_STEP decision had no valid steps")
            return null
        }
        val confidence = obj.optDouble("confidence", 1.0).toFloat().coerceIn(0f, 1f)
        return AiDecision.MultiStep(steps = steps, reasoning = reasoning, confidence = confidence)
    }

    private fun parseStringMap(jsonObject: JSONObject?): Map<String, String> {
        jsonObject ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        jsonObject.keys().forEach { key ->
            val value = jsonObject.opt(key)
            if (value != null && value !is JSONObject && value !is JSONArray) {
                result[key] = value.toString()
            } else if (value is JSONObject || value is JSONArray) {
                // Nested objects are serialised back to JSON string so callers
                // can deserialise them according to their tool's schema
                result[key] = value.toString()
            }
        }
        return result
    }
}
