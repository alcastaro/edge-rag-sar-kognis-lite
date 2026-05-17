package io.kognis.tactical.data.learning

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * KV store for learner preferences — tone, pace, default difficulty bias, preferred
 * language, last-touched topic, etc. Keys are stable string identifiers; values are
 * stringly-typed (parse-time conversion in the consumer).
 *
 * Indexed on `key` for O(log n) reads. Updated-timestamp tracks staleness.
 */
@Entity
data class LearningPreference(
    @Id var id: Long = 0,
    @Index var key: String = "",
    var value: String = "",
    var updatedTs: Long = System.currentTimeMillis(),
) {
    constructor() : this(0, "", "", 0L)
}

object LearningPreferenceKeys {
    const val TONE = "tone"                 // "supportive" / "neutral" / "strict"
    const val PACE = "pace"                 // "slow" / "medium" / "fast"
    const val DIFFICULTY_BIAS = "difficulty_bias"  // "easier" / "match" / "harder"
    const val LANGUAGE = "language"         // "es" / "en"
    const val LAST_TOPIC = "last_topic"     // topic id of most recent session
    const val LAST_SESSION_ID = "last_session_id"  // for cross-session continuity
}
