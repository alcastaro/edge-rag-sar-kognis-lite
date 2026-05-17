package io.kognis.tactical.core.llm

/** Drop-in replacement for ai.liquid.leap.message.MessageResponse */
sealed class MessageResponse {
    data class Chunk(val text: String) : MessageResponse()
}
