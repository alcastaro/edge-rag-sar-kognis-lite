package io.kognis.tactical.data.learning

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Packed summary of a span of turns. Three kinds:
 *   - "rolling"        — produced every 8 turns by the deriver worker
 *   - "session"        — produced on session close
 *   - "cross_session"  — produced when promoting a session summary to long-term memory
 *
 * Indexed on `sessionId` for fast lookup. `coverFromTurn` / `coverToTurn` describe the
 * turn range the summary represents so the prompt builder can dedupe overlap.
 */
@Entity
data class LearningSummary(
    @Id var id: Long = 0,
    @Index var sessionId: Long = 0,
    var kind: String = "rolling",
    var coverFromTurn: Int = 0,
    var coverToTurn: Int = 0,
    var text: String = "",
    var ts: Long = System.currentTimeMillis(),
) {
    constructor() : this(0, 0, "rolling", 0, 0, "", 0L)
}
