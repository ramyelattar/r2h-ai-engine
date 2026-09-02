package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Inference tuning parameters attached to a single [GenerateRequest].
 *
 * All fields have sensible defaults chosen for interactive chat. Pass null for
 * [GenerateRequest.sessionConfig] to accept the engine's built-in defaults; supply
 * a SessionConfig only when the caller needs to override specific parameters.
 *
 * @param maxTokens   Hard cap on the number of tokens the engine will generate.
 *                    Must be > 0. The engine enforces its own ceiling independently;
 *                    the effective limit is min(maxTokens, engineCeiling).
 * @param temperature Sampling temperature in [0.0, 2.0]. 0.0 = greedy (deterministic),
 *                    1.0 = standard sampling, > 1.0 = more creative / less coherent.
 * @param systemPrompt Optional system-level instruction prepended to the prompt
 *                    before inference. Empty string means no system prompt.
 *                    Must never contain user-supplied content that has not been
 *                    sanitized — the engine does not sanitize prompts.
 */
@Parcelize
data class SessionConfig(
    val maxTokens: Int,
    val temperature: Float,
    val systemPrompt: String,
) : Parcelable
