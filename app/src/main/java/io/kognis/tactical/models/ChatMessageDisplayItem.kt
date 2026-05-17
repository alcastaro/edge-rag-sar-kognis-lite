package io.kognis.tactical.models

import kotlinx.serialization.Serializable

/**
 * Data model for chat messages displayed in the UI. Role is stored as a
 * String ("USER", "ASSISTANT", "SYSTEM") so kotlinx.serialization can
 * round-trip the message — the enum from the runtime layer is converted
 * at the boundary.
 */
@Serializable
data class ChatMessageDisplayItem(
    val role: String,
    val text: String,
    val reasoning: String? = null,
    /** RAG audit metadata as JSON string (null if not an assistant message or RAG data unavailable) */
    val ragInfo: String? = null,
    /** Tokens and time stats JSON string (null if not provided) */
    val generationStats: String? = null,
    /** CoT audit JSON — populated after all markers are emitted for this message */
    val cotAuditJson: String? = null,
    /** User feedback rating: "up", "down", or null */
    val feedbackRating: String? = null
)
