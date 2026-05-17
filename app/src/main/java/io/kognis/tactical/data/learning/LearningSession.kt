package io.kognis.tactical.data.learning

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * One adaptive-learning session — bound to a curriculum, with start/close timestamps
 * and an optional short summary written on close. Cross-session memory promotion uses
 * [summaryShortText] as the textual digest that survives.
 */
@Entity
data class LearningSession(
    @Id var id: Long = 0,
    @Index var curriculumId: String = "",
    var startTs: Long = System.currentTimeMillis(),
    var closeTs: Long = 0L,
    var summaryShortText: String = "",
    var language: String = "es",
    var turnCount: Int = 0,
) {
    constructor() : this(0, "", 0L, 0L, "", "es", 0)
    val isClosed: Boolean get() = closeTs > 0L
}
