package io.kognis.tactical.core.llm

/** Model runner abstraction for streaming LLM backends. */
interface ModelRunner {
    fun createConversation(systemPrompt: String): Conversation
    fun unload()
}
