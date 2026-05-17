package io.kognis.tactical.data.learning

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Cached parse of a curriculum JSON module. Loaded on first session start of a
 * given `curriculumId`; subsequent sessions read straight from ObjectBox.
 *
 * `caseStudiesJson` and `quizSeedsJson` keep the raw JSON sub-arrays so the
 * prompt builder + skill dispatcher can re-parse selectively without re-reading
 * the asset file.
 */
@Entity
data class CurriculumModule(
    @Id var id: Long = 0,
    @Index var curriculumId: String = "",
    @Index var moduleId: String = "",
    var topic: String = "",
    var difficulty: String = "beginner",
    var summary: String = "",
    var caseStudiesJson: String = "[]",
    var quizSeedsJson: String = "[]",
    var loadedTs: Long = System.currentTimeMillis(),
) {
    constructor() : this(0, "", "", "", "beginner", "", "[]", "[]", 0L)
}
