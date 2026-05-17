package io.kognis.tactical.core.llm

/** Chat message wrapper for streaming LLM runtimes. */
data class ChatMessage(
    val role: Role,
    val textContent: String,
) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}
