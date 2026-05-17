package io.kognis.tactical.core.llm

import kotlinx.coroutines.flow.Flow

/** Drop-in replacement for ai.liquid.leap.Conversation */
interface Conversation {
    fun generateResponse(message: ChatMessage): Flow<MessageResponse>

    /**
     * Release native resources (e.g., LiteRT-LM Conversation handle, KV-cache).
     * Default no-op for runners that defer cleanup to ModelRunner.unload().
     * Callers should invoke this before dropping the reference.
     */
    fun close() {}
}
