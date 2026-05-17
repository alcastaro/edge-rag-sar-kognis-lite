package io.kognis.tactical.core.llm

/** Drop-in replacement for ai.liquid.leap.message.ChatMessage */
data class ChatMessage(
    val role: Role,
    val textContent: String,
) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}
