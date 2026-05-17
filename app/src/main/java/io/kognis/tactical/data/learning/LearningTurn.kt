package io.kognis.tactical.data.learning

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * A single conversation turn within a learning session — raw text + role + token count.
 * Indexed on `sessionId` so the prompt builder can fetch the last N turns in ~0.1 ms.
 *
 * Role values: "user" / "assistant" / "skill_result" (the last for synthetic turns the
 * app inserts after dispatching a SKILL: tag, so the agent sees its own outcomes).
 */
@Entity
data class LearningTurn(
    @Id var id: Long = 0,
    @Index var sessionId: Long = 0,
    var turnIndex: Int = 0,
    var role: String = "user",
    var text: String = "",
    var tokens: Int = 0,
    var ts: Long = System.currentTimeMillis(),
) {
    constructor() : this(0, 0, 0, "user", "", 0, 0L)
}
