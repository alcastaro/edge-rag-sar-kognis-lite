package io.kognis.tactical.core.llm

/** Drop-in replacement for ai.liquid.leap.ModelRunner */
interface ModelRunner {
    fun createConversation(systemPrompt: String): Conversation
    fun unload()
}
