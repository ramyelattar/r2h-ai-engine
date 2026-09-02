package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A single turn in a conversation between a user and the AI assistant.
 *
 * [role] must be one of "user", "assistant", or "system". The engine does not
 * validate the role string — callers are responsible for using the correct values.
 *
 * [epochMs] is wall-clock time; 0 means not provided. The engine uses it only
 * for ordering when history is assembled into a prompt.
 */
@Parcelize
data class ConversationTurn(
    val role: String,
    val content: String,
    val epochMs: Long = 0L,
) : Parcelable {

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
    }
}
