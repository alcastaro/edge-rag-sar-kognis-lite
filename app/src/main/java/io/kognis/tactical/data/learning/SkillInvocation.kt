package io.kognis.tactical.data.learning

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Audit log of skill invocations — every time the agent emitted a `SKILL: {...}`
 * tag and the app dispatched it. Used for:
 *   - debugging (was the skill emitted? did it parse?)
 *   - rate-limiting (don't quiz the same topic 3 times in 5 turns)
 *   - the LearnerPanel "Recent activity" view
 */
@Entity
data class SkillInvocation(
    @Id var id: Long = 0,
    @Index var sessionId: Long = 0,
    var skill: String = "",
    var argsJson: String = "",
    var outcomeJson: String = "",
    var ts: Long = System.currentTimeMillis(),
) {
    constructor() : this(0, 0, "", "", "", 0L)
}
