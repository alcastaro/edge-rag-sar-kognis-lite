package io.kognis.tactical.data.learning

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Per-topic mastery score in [0.0, 1.0]. Updated via exponential moving average
 * when the agent emits a `mark_mastery` skill or when a quiz is answered.
 *
 * `decayTs` lets the deriver worker apply forgetting (mastery decreases by 5%
 * after 14 days untouched) so the model surfaces stale topics for review.
 *
 * `nSeen` / `nCorrect` track raw counts for the learner panel UI.
 */
@Entity
data class TopicMastery(
    @Id var id: Long = 0,
    @Index var topic: String = "",
    var mastery: Double = 0.0,
    var nSeen: Int = 0,
    var nCorrect: Int = 0,
    var updatedTs: Long = System.currentTimeMillis(),
    var decayTs: Long = System.currentTimeMillis(),
) {
    constructor() : this(0, "", 0.0, 0, 0, 0L, 0L)

    /** EMA update with alpha=0.3 — recent performance weighted higher. */
    fun applyOutcome(success: Boolean): TopicMastery {
        val newScore = 0.7 * mastery + 0.3 * (if (success) 1.0 else 0.0)
        return copy(
            mastery = newScore.coerceIn(0.0, 1.0),
            nSeen = nSeen + 1,
            nCorrect = nCorrect + (if (success) 1 else 0),
            updatedTs = System.currentTimeMillis(),
            decayTs = System.currentTimeMillis(),
        )
    }
}
