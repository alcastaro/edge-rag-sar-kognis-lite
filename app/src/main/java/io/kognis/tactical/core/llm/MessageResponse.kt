package io.kognis.tactical.core.llm

/** Streamed message response chunk emitted by a Conversation. */
sealed class MessageResponse {
    data class Chunk(val text: String) : MessageResponse()
}
